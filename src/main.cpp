// Star Wars Episode I: Racer - N64 Static Recompilation
// Main entry point
//
// "Now THIS is podracing!" - Anakin Skywalker

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>
#include <algorithm>
#include <chrono>
#include <fstream>
#include <sstream>
#include <thread>
#include <atomic>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <dbghelp.h>
#endif

#include <SDL.h>
#if defined(_WIN32)
#include <SDL_syswm.h>
#elif defined(__ANDROID__)
#include <SDL_system.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <dlfcn.h>
#endif

#include "recomp.h"
#include "recomp_hooks.h"
#include "librecomp/game.hpp"
#include "librecomp/overlays.hpp"
#include "librecomp/rsp.hpp"
#include "librecomp/addresses.hpp"
#include "ultramodern/ultramodern.hpp"
#include "ultramodern/ultra64.h"
#include "ultramodern/renderer_context.hpp"
#include "ultramodern/error_handling.hpp"
#include "ultramodern/events.hpp"
#include "ultramodern/input.hpp"
#include "ultramodern/threads.hpp"

// =============================================================================
// External declarations
// =============================================================================

// From section_table.cpp
extern SectionTableEntry section_table[];
extern const size_t num_sections;

// From rt64_render_context.cpp
extern std::unique_ptr<ultramodern::renderer::RendererContext> create_swe1r_render_context(
    uint8_t* rdram, ultramodern::renderer::WindowHandle window_handle, bool developer_mode);

// The recompiled entrypoint function
extern "C" void recomp_entrypoint(uint8_t* rdram, recomp_context* ctx);

// From audio.cpp
extern void swe1r_queue_samples(int16_t* samples, size_t num_samples);
extern size_t swe1r_get_frames_remaining();
extern void swe1r_set_frequency(uint32_t freq);

// RDRAM base — captured in on_game_init so the crash handler can translate
// native fault addresses back into N64 virtual addresses.
static uint8_t* g_rdram_base = nullptr;

// RACER PATCH: MMIO interception helper is now defined in recomp.h so the
// recompiled .c files can see it.  (Moved from main.cpp.)

// SI DMA: the game's __osSiRawStartDma is COP0/MMIO based and can't be
// recompiled. It moves a 64-byte block between DRAM and PIF RAM and raises an
// SI interrupt on completion.
//
//   a0 = direction (1 = DRAM->PIF write, 0 = PIF->DRAM read)
//   a1 = DRAM buffer address
//
// There is no PIF hardware here, so on a read we synthesize the controller
// status block the game's detection code (func_800899E8) expects: 8 bytes per
// channel, 4 channels. Controller 0 is reported present (standard pad, no pak);
// channels 1-3 report "no controller" via the error bits in byte 2.
//   byte0 pad | byte1 txsize | byte2 rxsize/error | byte3 cmd
//   byte4 type_lo | byte5 type_hi | byte6 status | byte7 pad
// The parser checks (byte2 & 0xC0) for an error, reads type from byte4/5 and
// the pak/status flag from byte6.
extern "C" void __osSiRawStartDma_recomp(uint8_t* rdram, recomp_context* ctx) {
    int32_t  dir = (int32_t)ctx->r4;
    uint32_t buf = (uint32_t)ctx->r5;

    if (dir == 0 && buf != 0) {
        // PIF -> DRAM read: fill in the controller status response.
        // The block is word-aligned, so big-endian-packed word writes land the
        // bytes correctly for the recompiled MEM_B (XOR 3) accessors.
        auto w32 = [&](uint32_t addr, uint32_t value) {
            *(uint32_t*)(rdram + (addr - 0x80000000u)) = value;
        };
        for (int ch = 0; ch < 4; ch++) {
            uint32_t c = buf + ch * 8;
            if (ch == 0) {
                w32(c + 0, 0xFF010300u); // pad, tx=1, rx=3 (no error), cmd=0
                w32(c + 4, 0x050000FFu); // type=0x0005 (standard pad), no pak
            } else {
                w32(c + 0, 0xFF018300u); // byte2=0x83 -> error bit set
                w32(c + 4, 0xFFFFFFFFu); // no controller on this channel
            }
        }
        printf("[si] __osSiRawStartDma read  -> controller status written to 0x%08X\n", buf);
    } else {
        printf("[si] __osSiRawStartDma write -> dir=%d buf=0x%08X (acked)\n", dir, buf);
    }

    // No real hardware to wait on — signal SI completion immediately.
    ultramodern::send_si_message();
    ctx->r2 = 0; // success
}

// RACER PATCH (2026-10-20, Claude): the game's __osMotorAccess(pfs, on) at 0x8008B1B0 (tools/post_recomp_patches.py
// REDIRECTS). The runtime has separate start/stop entry points with the same a0 = pfs.
extern "C" void osMotorStart_recomp(uint8_t* rdram, recomp_context* ctx);
extern "C" void osMotorStop_recomp(uint8_t* rdram, recomp_context* ctx);
extern "C" void racer_osMotorAccess(uint8_t* rdram, recomp_context* ctx) {
    if ((int32_t)ctx->r5 != 0) {
        osMotorStart_recomp(rdram, ctx);
    } else {
        osMotorStop_recomp(rdram, ctx);
    }
}

// RACER PATCH (2026-10-21, Claude): the game's osPiReadIo(devAddr, u32* data) (func_8008A710). Its raw cart read is
// stubbed, so the asset loader read 0 for the table count and every load returned NULL. devAddr is a ROM offset
// (the game ORs in osRomBase itself); reads past the 32 MB ROM return 0 and are logged.
extern "C" void racer_osPiReadIo(uint8_t* rdram, recomp_context* ctx) {
    uint32_t rom_off = (uint32_t)ctx->r4 & 0x0FFFFFFC;
    if (rom_off < 0x02000000) {
        recomp::do_rom_pio(rdram, ctx->r5, recomp::rom_base + rom_off);
    } else {
        static std::atomic<uint32_t> s_bad{0};
        if (++s_bad <= 10) {
            printf("[pi-readio] devAddr 0x%08X outside the ROM - returning 0\n", (uint32_t)ctx->r4);
        }
        MEM_W(0, ctx->r5) = 0;
    }
    ctx->r2 = 0;
}

// RACER PATCH: MMIO absorption hook for N64 device-register writes.
// On Android the 4 GB virtual address space is only partially mapped
// (512 MB RDRAM).  N64 device registers at ~0xA4XXXXXX translate to offsets
// > 512 MB and are unmapped.  This function is called by rdram_mmio_hook in
// the patched MEM_W macro.  It returns a pointer to a silent scratch word;
// writing through that pointer is a no-op that doesn't crash the game.
// 2026-10-21 (Claude): 64 bytes, 16-aligned. It was one int32_t, but SD/LD go through the same hook and write 8 bytes,
// which overwrote whatever static came next.
alignas(16) static uint8_t s_mmio_dummy[64];          // silent scratch for device writes

extern "C" void* rdram_mmio_write(uint32_t dev_offset) {
    // TODO(S5): when we know which registers matter, add per-register logic here.
    // For now, absorb all device writes silently so the boot code doesn't SIGSEGV.
    (void)dev_offset;
    return s_mmio_dummy;
}

// RACER PATCH (2026-10-21, Claude): an N64 address whose RDRAM offset lands BEYOND the 4 GB reservation (a pointer that
// was not sign-extended, or garbage). Before every access size went through rdram_mmio_hook, byte/halfword stores like
// this wrote straight into the native heap. Logs the first 20 (grep "[bad-addr]") and absorbs the access.
alignas(16) static uint8_t s_bad_scratch[64];
extern "C" void* rdram_bad_access(uint64_t n64_addr) {
    static std::atomic<uint32_t> s_count{0};
    uint32_t n = ++s_count;
    if (n <= 20 || (n % 100000) == 0) {
        // The hook is inlined into the recompiled function, so our return address is inside it: `racer.cmd sym <off>`.
        uintptr_t off = 0;
#if defined(__ANDROID__)
        // Debug (-O0) builds do not inline rdram_mmio_hook: frame 1 is the recompiled game function that called it.
        void* ra = __builtin_return_address(1);
        Dl_info info{};
        if (dladdr(ra, &info) && info.dli_fbase) {
            off = (uintptr_t)ra - (uintptr_t)info.dli_fbase;
        }
#endif
        printf("[bad-addr] #%u N64 address 0x%016llX is outside RDRAM (not sign-extended?) - access absorbed;"
               " code at libmain.so+0x%zx (racer.cmd sym 0x%zx)\n",
               n, (unsigned long long)n64_addr, (size_t)off, (size_t)off);
    }
    return s_bad_scratch;
}

// =============================================================================
// ROM Hash: Star Wars Episode I: Racer (US)
// CRC1: 0x72F70398  CRC2: 0x6556A98B
// =============================================================================
constexpr uint64_t SWE1R_ROM_HASH = 0x9B89D0F9EBC12D32ULL; // XXH3_64bits of baserom.z64

// =============================================================================
// Global state
// =============================================================================
static SDL_Window* g_window = nullptr;
static SDL_GameController* g_controller = nullptr;

// =============================================================================
// RSP Callbacks
// =============================================================================

// No-op RSP microcode for any task type we have no recompiled microcode for.
// Returning nullptr makes ultramodern's run_task abort the whole process, so
// instead we pretend the task completed (RspExitReason::Broke). Graphics tasks
// (M_GFXTASK) never reach here; submit_rsp_task routes those to RT64.
static RspExitReason swe1r_noop_ucode(uint8_t* /*rdram*/, uint32_t /*ucode_addr*/) {
    return RspExitReason::Broke;
}

// RACER PATCH (2026-10-21, Claude): Racer's audio microcode (libultra aspMain, ROM 0x9A050), recompiled by RSPRecomp
// from aspMain.toml into rsp/aspMain.cpp.
RspExitReason aspMain(uint8_t* rdram, uint32_t ucode_addr);

RspUcodeFunc* get_rsp_microcode(const OSTask* task) {
    static uint32_t rsp_call_count = 0;
    rsp_call_count++;
    if (rsp_call_count <= 5 || (rsp_call_count % 120 == 0)) {
        fprintf(stderr, "[SWE1R] get_rsp_microcode #%u: type=%u ucode=0x%08X data=0x%08X\n",
                rsp_call_count, task->t.type,
                (uint32_t)task->t.ucode, (uint32_t)task->t.ucode_data);
    }
    // Only non-graphics tasks reach here. Racer has one audio microcode (task ucode 0x80099450).
    if (task->t.type == M_AUDTASK) {
        return aspMain;
    }
    return swe1r_noop_ucode;
}

// =============================================================================
// Renderer Callbacks
// =============================================================================

std::unique_ptr<ultramodern::renderer::RendererContext> create_render_context_callback(
    uint8_t* rdram, ultramodern::renderer::WindowHandle window_handle, bool developer_mode) {
    return create_swe1r_render_context(rdram, window_handle, developer_mode);
}

// =============================================================================
// GFX Callbacks (window management)
// =============================================================================

ultramodern::gfx_callbacks_t::gfx_data_t create_gfx() {
#if !defined(__ANDROID__)
    SDL_SetHint(SDL_HINT_WINDOWS_DPI_AWARENESS, "permonitorv2");
#endif
    SDL_SetHint(SDL_HINT_GAMECONTROLLER_USE_BUTTON_LABELS, "0");

    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER | SDL_INIT_AUDIO) < 0) {
        fprintf(stderr, "Failed to initialize SDL2: %s\n", SDL_GetError());
        std::exit(1);
    }

    printf("[SWE1R] SDL initialized: %s\n", SDL_GetCurrentVideoDriver());
    return nullptr;
}

#if defined(__ANDROID__)
// RACER PATCH (2026-10-21, Claude): Home / app switch crashed the game: Android destroys the window surface and RT64's
// present thread rebuilt the Vulkan surface from the missing native window (SIGSEGV in CreateAndroidSurfaceKHR, from
// plume VulkanSwapChain::resize). plume skips the rebuild while it is told the surface is not ready; tell it from
// SDL's background / foreground events. The watch runs on the thread that sends the event (SDL's Java thread),
// so the flag flips as soon as Android pauses or resumes the app.
extern "C" void plume_set_android_surface_ready(int ready);
static int swe1r_app_event_watch(void*, SDL_Event* event) {
    switch (event->type) {
        case SDL_APP_WILLENTERBACKGROUND:
            plume_set_android_surface_ready(0);
            printf("[SWE1R] app to background: Vulkan surface paused\n");
            break;
        case SDL_APP_DIDENTERFOREGROUND:
            plume_set_android_surface_ready(1);
            printf("[SWE1R] app to foreground: Vulkan surface resumed\n");
            break;
    }
    return 0;
}
#endif

ultramodern::renderer::WindowHandle create_window(ultramodern::gfx_callbacks_t::gfx_data_t) {
#if defined(__ANDROID__)
    SDL_AddEventWatch(swe1r_app_event_watch, nullptr);
    // 2026-10-21: landscape only (either way up). With a resizable window and no hint SDL lets Android rotate the
    // game to portrait, which overrides the manifest's screenOrientation. Must be set before SDL_CreateWindow.
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
    const uint32_t window_flags = SDL_WINDOW_RESIZABLE | SDL_WINDOW_VULKAN | SDL_WINDOW_SHOWN;
#else
    const uint32_t window_flags = SDL_WINDOW_RESIZABLE;
#endif
    g_window = SDL_CreateWindow(
        "Star Wars Episode I: Racer",
        SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
        1280, 960,
        window_flags
    );

    if (!g_window) {
        fprintf(stderr, "Failed to create window: %s\n", SDL_GetError());
        std::exit(1);
    }

    printf("[SWE1R] Window created\n");

#if defined(_WIN32)
    // Return the native HWND on Windows; SDL owns the Android native window.
    SDL_SysWMinfo wminfo;
    SDL_VERSION(&wminfo.version);
    SDL_GetWindowWMInfo(g_window, &wminfo);

    ultramodern::renderer::WindowHandle handle{};
    handle.window = wminfo.info.win.window;
    handle.thread_id = GetCurrentThreadId();
    return handle;
#else
    return g_window;
#endif
}

void update_gfx(ultramodern::gfx_callbacks_t::gfx_data_t) {
    // Process SDL events
    SDL_Event event;
    while (SDL_PollEvent(&event)) {
        switch (event.type) {
            case SDL_QUIT:
                ultramodern::quit();
                break;
            case SDL_KEYDOWN:
                if (event.key.keysym.sym == SDLK_ESCAPE) {
                    ultramodern::quit();
                }
                break;
        }
    }
}

// =============================================================================
// Input Callbacks (stub)
// =============================================================================

void poll_input() {
    SDL_GameControllerEventState(SDL_ENABLE);
    if (g_controller != nullptr && !SDL_GameControllerGetAttached(g_controller)) {
        SDL_GameControllerClose(g_controller);
        g_controller = nullptr;
    }
    if (g_controller == nullptr) {
        for (int index = 0; index < SDL_NumJoysticks(); index++) {
            if (SDL_IsGameController(index)) {
                g_controller = SDL_GameControllerOpen(index);
                if (g_controller != nullptr) {
                    break;
                }
            }
        }
    }
}

// Touch pad state written by the Java TouchPadView (android/app/src/main/java/.../TouchPadView.java) through JNI.
static std::atomic<uint32_t> g_touch_buttons{ 0 };
static std::atomic<float> g_touch_x{ 0.0f };
static std::atomic<float> g_touch_y{ 0.0f };

#if defined(__ANDROID__)
#include <jni.h>
extern "C" JNIEXPORT void JNICALL
Java_com_sp00nznet_swep1racer_SWE1RacerActivity_nativeSetTouchPad(JNIEnv*, jclass, jint buttons, jfloat x, jfloat y) {
    g_touch_buttons.store((uint32_t)buttons);
    g_touch_x.store((float)x);
    g_touch_y.store((float)y);
}

// 2026-10-21 (Claude): the local player's boost state, for the pad's BOOST button (fires the moment it is ready).
// Racer keeps a per-racer record array at 0x80118F90 (0x88 bytes each); record 0 is local player 1 when its tag at
// +4 is 'Locl', and +0x84 points to the racer object ('Test', same layout as the PC version). In that object
// +0x210 is the boost status: 0 not ready, 1 charging, 2 ready (written only by the boost-charge code, func_8006E454).
// Returns -1 when there is no local racer (menus, attract demo) or a pointer looks wrong.
extern "C" JNIEXPORT jint JNICALL
Java_com_sp00nznet_swep1racer_SWE1RacerActivity_nativeBoostState(JNIEnv*, jclass) {
    uint8_t* const rdram = g_rdram_base;
    if (rdram == nullptr) return -1;
    auto read32 = [rdram](uint32_t addr) -> uint32_t {
        return *(volatile uint32_t*)(rdram + (addr - 0x80000000u));
    };
    if (read32(0x80118F94) != 0x4C6F636Cu) return -1;            // 'Locl'
    const uint32_t obj = read32(0x80119014);
    if (obj < 0x80000400u || obj > 0x807FF000u || (obj & 3) != 0) return -1;
    if (read32(obj) != 0x54657374u) return -1;                    // 'Test'
    const uint32_t status = read32(obj + 0x210);
    return (status <= 2) ? (jint)status : -1;
}
#endif

bool get_input(int controller_num, uint16_t* buttons, float* x, float* y) {
    if (controller_num != 0) return false;

    *buttons = 0;
    *x = 0.0f;
    *y = 0.0f;

    // Keyboard (desktop testing)
    const uint8_t* keys = SDL_GetKeyboardState(nullptr);
    if (keys[SDL_SCANCODE_RETURN]) *buttons |= 0x8000; // A
    if (keys[SDL_SCANCODE_LSHIFT]) *buttons |= 0x4000; // B
    if (keys[SDL_SCANCODE_Z])      *buttons |= 0x2000; // Z
    if (keys[SDL_SCANCODE_SPACE])  *buttons |= 0x1000; // Start
    if (keys[SDL_SCANCODE_UP])     *buttons |= 0x0800; // D-Up
    if (keys[SDL_SCANCODE_DOWN])   *buttons |= 0x0400; // D-Down
    if (keys[SDL_SCANCODE_LEFT])   *buttons |= 0x0200; // D-Left
    if (keys[SDL_SCANCODE_RIGHT])  *buttons |= 0x0100; // D-Right
    if (keys[SDL_SCANCODE_Q])      *buttons |= 0x0020; // L
    if (keys[SDL_SCANCODE_E])      *buttons |= 0x0010; // R
    if (keys[SDL_SCANCODE_I])      *buttons |= 0x0008; // C-Up
    if (keys[SDL_SCANCODE_K])      *buttons |= 0x0004; // C-Down
    if (keys[SDL_SCANCODE_J])      *buttons |= 0x0002; // C-Left
    if (keys[SDL_SCANCODE_L])      *buttons |= 0x0001; // C-Right
    if (keys[SDL_SCANCODE_W]) *y += 1.0f;
    if (keys[SDL_SCANCODE_S]) *y -= 1.0f;
    if (keys[SDL_SCANCODE_A]) *x -= 1.0f;
    if (keys[SDL_SCANCODE_D]) *x += 1.0f;

    // Physical game controller
    if (g_controller != nullptr) {
        auto btn = [](SDL_GameControllerButton b) { return SDL_GameControllerGetButton(g_controller, b) != 0; };
        auto axis = [](SDL_GameControllerAxis a) { return SDL_GameControllerGetAxis(g_controller, a) / 32768.0f; };
        if (btn(SDL_CONTROLLER_BUTTON_A)) *buttons |= 0x8000;
        if (btn(SDL_CONTROLLER_BUTTON_B)) *buttons |= 0x4000;
        if (btn(SDL_CONTROLLER_BUTTON_X)) *buttons |= 0x0002;           // C-Left
        if (btn(SDL_CONTROLLER_BUTTON_Y)) *buttons |= 0x0008;           // C-Up
        if (btn(SDL_CONTROLLER_BUTTON_BACK)) *buttons |= 0x2000;        // Z
        if (btn(SDL_CONTROLLER_BUTTON_START)) *buttons |= 0x1000;
        if (btn(SDL_CONTROLLER_BUTTON_LEFTSHOULDER)) *buttons |= 0x0020;  // L
        if (btn(SDL_CONTROLLER_BUTTON_RIGHTSHOULDER)) *buttons |= 0x0010; // R
        if (btn(SDL_CONTROLLER_BUTTON_DPAD_UP)) *buttons |= 0x0800;
        if (btn(SDL_CONTROLLER_BUTTON_DPAD_DOWN)) *buttons |= 0x0400;
        if (btn(SDL_CONTROLLER_BUTTON_DPAD_LEFT)) *buttons |= 0x0200;
        if (btn(SDL_CONTROLLER_BUTTON_DPAD_RIGHT)) *buttons |= 0x0100;
        if (axis(SDL_CONTROLLER_AXIS_TRIGGERLEFT) > 0.30f) *buttons |= 0x2000;  // Z
        if (axis(SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > 0.30f) *buttons |= 0x0010; // R
        const float rx = axis(SDL_CONTROLLER_AXIS_RIGHTX);
        const float ry = axis(SDL_CONTROLLER_AXIS_RIGHTY);
        if (ry < -0.5f) *buttons |= 0x0008; // C-Up
        if (ry > 0.5f)  *buttons |= 0x0004; // C-Down
        if (rx < -0.5f) *buttons |= 0x0002; // C-Left
        if (rx > 0.5f)  *buttons |= 0x0001; // C-Right
        *x += axis(SDL_CONTROLLER_AXIS_LEFTX);
        *y -= axis(SDL_CONTROLLER_AXIS_LEFTY);
    }

    // On-screen touch pad (Android)
    *buttons |= (uint16_t)(g_touch_buttons.load() & 0xFFFF);
    const float tx = g_touch_x.load();
    const float ty = g_touch_y.load();
    if (tx * tx + ty * ty > 0.0025f) {
        *x = tx;
        *y = ty;
    }

    *x = std::clamp(*x, -1.0f, 1.0f);
    *y = std::clamp(*y, -1.0f, 1.0f);

    // 2026-10-21 (Claude): log every change of the pressed buttons the game reads (grep "[pad]"), to check that each
    // on-screen / gamepad button reaches the game.
    static uint16_t s_last_buttons = 0;
    if (*buttons != s_last_buttons) {
        printf("[pad] buttons 0x%04X (was 0x%04X)\n", *buttons, s_last_buttons);
        s_last_buttons = *buttons;
    }
    return true;
}

void set_rumble(int controller_num, bool rumble) {
    // TODO: SDL haptic feedback
}

ultramodern::input::connected_device_info_t get_connected_device_info(int controller_num) {
    // Player 1 is always present: the on-screen touch pad counts as a controller.
    if (controller_num == 0) {
        return { ultramodern::input::Device::Controller, ultramodern::input::Pak::None };
    }
    return { ultramodern::input::Device::None, ultramodern::input::Pak::None };
}

// =============================================================================
// Event Callbacks
// =============================================================================

void vi_callback() {
    // Called every VI interrupt
}

void gfx_init_callback() {
    printf("[SWE1R] Graphics initialized\n");
    // Start the game now that the renderer and VI thread are ready.
    // This must happen after start() has begun, not before, so the VI
    // thread gets a chance to set dummy VI state before is_game_started() is true.
    std::u8string game_id = u8"swe1racer";
    // The runtime tracks per-mode mods; this port has one base game mode.
    recomp::start_game(game_id, "default");
    printf("[SWE1R] Game started\n");
}

// =============================================================================
// Error Handling
// =============================================================================

void error_message_box(const char* msg) {
    fprintf(stderr, "[SWE1R ERROR] %s\n", msg);
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_ERROR, "SWE1Racer", "ERROR: %s", msg);
#endif
    if (g_window) {
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "SWE1R Error", msg, g_window);
    }
}

// =============================================================================
// Thread Naming
// =============================================================================

std::string get_game_thread_name(const OSThread* t) {
    return "SWE1R-Thread";
}

// =============================================================================
// Game Init Callback - initialize libultra state that stubbed boot code skips
// =============================================================================

void on_game_init(uint8_t* rdram, recomp_context* ctx) {
    // Capture the RDRAM base so the crash handler can map native fault
    // addresses back to N64 virtual addresses.
    g_rdram_base = rdram;
    // racer.cmd uses this line to translate crash fault addresses into N64 addresses.
    printf("[SWE1R] rdram base %p\n", (void*)rdram);

    // The game's boot code at 0x8008D284 initializes the PI (cartridge ROM) handle
    // and stores it at globals 0x800A7BC0 and 0x800A7FC0. That code is stubbed
    // because it contains COP0 instructions. We replicate the initialization here.

    // Initialize the PI handle struct at the pre-allocated RDRAM address
    int32_t handle_addr = recomp::cart_handle; // 0x80800000
    OSPiHandle* handle = (OSPiHandle*)(rdram + ((uint32_t)handle_addr - 0x80000000u));
    memset(handle, 0, sizeof(OSPiHandle));
    handle->type = 0; // PI_DOMAIN2 (cartridge)
    handle->baseAddress = 0xB0000000; // phys_to_k1(0x10000000) = cartridge ROM base
    handle->domain = 0;

    // Store the handle pointer in the game's global variables (big-endian u32 writes)
    // 0x800A7BC0 = __osCartRomHandle (used by PI access functions)
    // 0x800A7FC0 = __osPiTable (PI device linked list head)
    auto write32 = [&](uint32_t mips_addr, uint32_t value) {
        *(uint32_t*)(rdram + (mips_addr - 0x80000000u)) = value;
    };

    write32(0x800A7BC0, (uint32_t)handle_addr);
    write32(0x800A7FC0, (uint32_t)handle_addr);

    // Self-link the handle's next pointer (linked list of one element)
    // handle_addr = 0x80800000, so this writes to RDRAM offset 0x00800000 (8MB)
    write32((uint32_t)handle_addr, (uint32_t)handle_addr); // handle->unused (next ptr) = self

    printf("[SWE1R] PI handle initialized at 0x%08X\n", (uint32_t)handle_addr);

    // Initialize VI state for the scheduler thread.
    // The scheduler (func_8008BC30) calls static_0_800941D0 which reads a pointer
    // from 0x800A7F50 pointing to the "current VI mode" struct. It uses:
    //   +0x02 (halfword): retrace count — how many VI retraces before sending a response
    //   +0x10 (word): response queue — where to send the "frame done" message
    // osCreateViManager normally sets this up, but it's stubbed in the recomp.
    // Without this, the scheduler sends responses to its own queue (feedback loop)
    // and never yields to the game thread.
    //
    // We create a fake VI state at 0x800A7F00 (unused BSS near the pointer) and a
    // "sink" queue at 0x800A7E80 that absorbs the scheduler's responses.

    // Create the sink queue: osCreateMesgQueue(queue=0x800A7E80, buf=0x800A7E60, count=8)
    // We can't call osCreateMesgQueue_recomp here (no ctx), so write the struct directly.
    // OSMesgQueue layout (big-endian):
    //   +0x00: mtqueue (PTR) = 0 (unused)
    //   +0x04: fullqueue (PTR) = 0 (unused)
    //   +0x08: validCount (s32) = 0
    //   +0x0C: first (s32) = 0
    //   +0x10: msgCount (s32) = 8
    //   +0x14: msg (PTR) = 0x800A7E60 (buffer)
    write32(0x800A7E80 + 0x00, 0);
    write32(0x800A7E80 + 0x04, 0);
    write32(0x800A7E80 + 0x08, 0);
    write32(0x800A7E80 + 0x0C, 0);
    write32(0x800A7E80 + 0x10, 8);
    write32(0x800A7E80 + 0x14, 0x800A7E60);

    // Create fake VI state at 0x800A7F00
    // RDRAM uses native word order with XOR byte-swapping for sub-word access.
    // MEM_W reads/writes uint32_t directly. MEM_HU(0x02, base) reads the lower
    // halfword of the word at base. So to set the halfword at +0x02 = 1,
    // we write the word at +0x00 = 0x00000001 (lower half = 1, upper half = 0).
    memset(rdram + (0x800A7F00 - 0x80000000u), 0, 0x20);
    write32(0x800A7F00, 0x00000001);  // +0x00 word: lower halfword (+0x02) = retrace count 1
    write32(0x800A7F10, 0x800A7E80);  // +0x10: response queue = sink queue

    // Point the VI state pointer to our struct
    write32(0x800A7F50, 0x800A7F00);
    printf("[SWE1R] VI state initialized at 0x800A7F00, sink queue at 0x800A7E80\n");

    // osMemSize at 0x80000318 — IPL3 normally writes the detected RDRAM size
    // here, but we stub IPL3. SWE1R requires the Expansion Pak, so report 8MB.
    // The framebuffer allocator (func_80039A38) reads this to size buffers.
    // 0x318 is below the game's BSS clear (0x520+), so this value survives;
    // the framebuffer pointer table at 0x80114530 must NOT be seeded here, as
    // it sits inside the cleared range and is populated by func_80039A38.
    write32(0x80000318, 0x00800000);
    printf("[SWE1R] osMemSize=0x800000 written at 0x80000318\n");
}

// =============================================================================
#if defined(_WIN32)
// Map-file symbolizer
//
// Release builds have no useful PDB symbols for the recompiled funcs (SymFromAddr
// returns garbage like "wcsrchr"). The linker .map file, however, lists every
// func_XXXX with its preferred VA. We parse it at startup and resolve crash
// stack frames against it — automating the manual map lookup used during Phase 5.
// =============================================================================
struct MapSym { uint32_t rva; std::string name; };
static std::vector<MapSym> g_map_syms;
static constexpr uint64_t MAP_PREFERRED_BASE = 0x140000000ull;

static bool is_hex16_va(const std::string& s) {
    if (s.size() != 16 || s.compare(0, 10, "0000000140") != 0) return false;
    for (char c : s) if (!isxdigit((unsigned char)c)) return false;
    return true;
}

static void load_map_symbols() {
    char exePath[MAX_PATH];
    GetModuleFileNameA(NULL, exePath, MAX_PATH);
    std::filesystem::path mapPath = std::filesystem::path(exePath).replace_extension(".map");
    std::ifstream f(mapPath);
    if (!f) {
        fprintf(stderr, "[map] no symbol map at %s (crash traces won't be symbolized)\n",
                mapPath.string().c_str());
        return;
    }
    std::string line;
    while (std::getline(f, line)) {
        // Symbol lines look like: " 0001:00025e00   <name>   0000000140026e00 f i x.obj"
        std::istringstream iss(line);
        std::vector<std::string> tok;
        for (std::string t; iss >> t; ) tok.push_back(t);
        if (tok.size() < 3) continue;
        // tok[0] must be SECTION:OFFSET
        if (tok[0].size() != 13 || tok[0][4] != ':') continue;
        for (size_t i = 2; i < tok.size(); i++) {
            if (is_hex16_va(tok[i])) {
                uint64_t va = strtoull(tok[i].c_str(), nullptr, 16);
                g_map_syms.push_back({ (uint32_t)(va - MAP_PREFERRED_BASE), tok[i - 1] });
                break;
            }
        }
    }
    std::sort(g_map_syms.begin(), g_map_syms.end(),
              [](const MapSym& a, const MapSym& b) { return a.rva < b.rva; });
    fprintf(stderr, "[map] loaded %zu symbols from %s\n",
            g_map_syms.size(), mapPath.filename().string().c_str());
}

// Returns "name + 0xNNN" for a module-relative address, or empty if unknown.
static std::string symbolize_rva(uint32_t rva) {
    if (g_map_syms.empty()) return {};
    auto it = std::upper_bound(g_map_syms.begin(), g_map_syms.end(), rva,
                               [](uint32_t v, const MapSym& m) { return v < m.rva; });
    if (it == g_map_syms.begin()) return {};
    --it;
    char buf[320];
    snprintf(buf, sizeof(buf), "%s + 0x%X", it->name.c_str(), rva - it->rva);
    return buf;
}

// =============================================================================
// Main
// =============================================================================

static LONG WINAPI crash_handler(EXCEPTION_POINTERS* ep) {
    fprintf(stderr, "\n[CRASH] Exception 0x%08lX at address 0x%p\n",
            ep->ExceptionRecord->ExceptionCode,
            ep->ExceptionRecord->ExceptionAddress);
    if (ep->ExceptionRecord->ExceptionCode == EXCEPTION_ACCESS_VIOLATION) {
        uintptr_t fault = (uintptr_t)ep->ExceptionRecord->ExceptionInformation[1];
        fprintf(stderr, "[CRASH] Access violation %s address 0x%p\n",
                ep->ExceptionRecord->ExceptionInformation[0] ? "writing" : "reading",
                (void*)fault);
        // Translate the fault back into an N64 address using the RDRAM base.
        if (g_rdram_base) {
            int64_t off = (int64_t)(fault - (uintptr_t)g_rdram_base);
            // MEM_B uses XOR 3 byte-swizzling, so undo it for the printed addr.
            uint32_t n64 = (uint32_t)(off + 0x80000000);
            uint32_t n64_b = (uint32_t)((off ^ 3) + 0x80000000);
            const char* tag = (off >= -0x10000 && off < 0x00900000)
                              ? "in RDRAM" : "out of RDRAM";
            fprintf(stderr, "[CRASH] rdram_base=%p offset=%+lld (%s)\n",
                    (void*)g_rdram_base, (long long)off, tag);
            fprintf(stderr, "[CRASH] -> N64 word addr ~0x%08X  byte addr ~0x%08X\n",
                    n64, n64_b);
        } else {
            fprintf(stderr, "[CRASH] rdram_base not set yet\n");
        }
    }
    // Print module + offset for the crash address
    HMODULE hMod = NULL;
    GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                       (LPCSTR)ep->ExceptionRecord->ExceptionAddress, &hMod);
    if (hMod) {
        char modName[MAX_PATH];
        GetModuleFileNameA(hMod, modName, MAX_PATH);
        uintptr_t offset = (uintptr_t)ep->ExceptionRecord->ExceptionAddress - (uintptr_t)hMod;
        fprintf(stderr, "[CRASH] Module: %s + 0x%llX\n", modName, (unsigned long long)offset);
    }
    // Walk the stack and symbolize each frame against the linker .map. This
    // resolves the recompiled func_XXXX names that PDB-based SymFromAddr cannot.
    SymInitialize(GetCurrentProcess(), NULL, TRUE);
    uintptr_t modBase = (uintptr_t)GetModuleHandle(NULL);
    STACKFRAME64 frame = {};
    CONTEXT ctx = *ep->ContextRecord;
    frame.AddrPC.Offset = ctx.Rip;
    frame.AddrPC.Mode = AddrModeFlat;
    frame.AddrStack.Offset = ctx.Rsp;
    frame.AddrStack.Mode = AddrModeFlat;
    frame.AddrFrame.Offset = ctx.Rbp;
    frame.AddrFrame.Mode = AddrModeFlat;
    fprintf(stderr, "[CRASH] Stack trace (symbolized via .map):\n");
    for (int i = 0; i < 24; i++) {
        if (!StackWalk64(IMAGE_FILE_MACHINE_AMD64, GetCurrentProcess(),
                         GetCurrentThread(), &frame, &ctx, NULL,
                         SymFunctionTableAccess64, SymGetModuleBase64, NULL))
            break;
        uint64_t pc = frame.AddrPC.Offset;
        std::string name;
        if (pc >= modBase) name = symbolize_rva((uint32_t)(pc - modBase));
        if (!name.empty())
            fprintf(stderr, "  [%d] %s   (rva 0x%X)\n", i, name.c_str(),
                    (uint32_t)(pc - modBase));
        else
            fprintf(stderr, "  [%d] 0x%llX\n", i, (unsigned long long)pc);
    }
    fflush(stderr);
    return EXCEPTION_EXECUTE_HANDLER;
}
#endif

#if defined(__ANDROID__)
// =============================================================================
// Android: copy stdout/stderr into logcat (tag "SWE1Racer-stdio")
// =============================================================================
// Android drops printf/fprintf output. The runtime reports its fatal errors that
// way (quick_exit file:line, switch_error, do_break, "Failed to find function"),
// so without this bridge those messages are lost.
static int s_stdio_pipe[2] = { -1, -1 };

static void* swe1r_stdio_pump(void*) {
    char buf[2048];
    size_t used = 0;
    ssize_t n;
    while ((n = read(s_stdio_pipe[0], buf + used, sizeof(buf) - 1 - used)) > 0) {
        used += (size_t)n;
        buf[used] = '\0';
        char* start = buf;
        char* nl;
        while ((nl = strchr(start, '\n')) != nullptr) {
            *nl = '\0';
            if (*start != '\0') {
                __android_log_write(ANDROID_LOG_INFO, "SWE1Racer-stdio", start);
            }
            start = nl + 1;
        }
        size_t remaining = used - (size_t)(start - buf);
        if (remaining == sizeof(buf) - 1) {
            // A very long line with no newline: log it as is.
            __android_log_write(ANDROID_LOG_INFO, "SWE1Racer-stdio", buf);
            remaining = 0;
        } else if (remaining > 0 && start != buf) {
            memmove(buf, start, remaining);
        }
        used = remaining;
    }
    return nullptr;
}

// Give the pump thread time to log the last lines before the process ends.
static void swe1r_stdio_drain() {
    usleep(300 * 1000);
}

static void swe1r_redirect_stdio_to_logcat() {
    if (pipe(s_stdio_pipe) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "SWE1Racer", "stdio bridge: pipe() failed");
        return;
    }
    dup2(s_stdio_pipe[1], STDOUT_FILENO);
    dup2(s_stdio_pipe[1], STDERR_FILENO);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, swe1r_stdio_pump, nullptr) == 0) {
        pthread_detach(thread);
    }
    atexit(swe1r_stdio_drain);
    at_quick_exit(swe1r_stdio_drain);
    __android_log_print(ANDROID_LOG_INFO, "SWE1Racer", "stdio bridge active (tag SWE1Racer-stdio)");
}
#endif

int main(int argc, char* argv[]) {
    // Force unbuffered output so we see prints before crashes
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
#if defined(__ANDROID__)
    swe1r_redirect_stdio_to_logcat();
#endif
#if defined(_WIN32)
    load_map_symbols();
    SetUnhandledExceptionFilter(crash_handler);
#endif

    fprintf(stderr, "[DEBUG] main() entered\n");
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "SWE1Racer", "Native main entered");
#endif

    printf("===========================================\n");
    printf("  Star Wars Episode I: Racer\n");
    printf("  N64 Static Recompilation v%d.%d.%d\n", 0, 1, 0);
    printf("  \"Now THIS is podracing!\"\n");
    printf("===========================================\n\n");

    // Register game entry
    recomp::GameEntry game_entry{};
    game_entry.rom_hash = SWE1R_ROM_HASH;
    game_entry.internal_name = "STAR WARS EP1 RACER";
    game_entry.display_name = "STAR WARS - Episode I: Racer";
    game_entry.game_id = u8"swe1racer";
    game_entry.mod_game_id = "swe1racer";
    // 2026-10-21 (Claude): Racer uses 16 Kbit EEPROM (block numbers past 63; MTE caught the 512-byte 4K buffer
    // overflowing into the native heap). Eep16k = 2 KB buffer, osEepromProbe answers EEPROM_TYPE_16K.
    game_entry.save_type = recomp::SaveType::Eep16k;
    game_entry.is_enabled = true;
    game_entry.entrypoint_address = (gpr)(int32_t)0x80000400; // Sign-extend for MEM macros
    game_entry.entrypoint = recomp_entrypoint;
    game_entry.on_init_callback = on_game_init;

    // Register overlay/section tables
    recomp::overlays::overlay_section_table_data_t sections_data{};
    sections_data.code_sections = section_table;
    sections_data.num_code_sections = num_sections;
    sections_data.total_num_sections = num_sections;

    recomp::overlays::overlays_by_index_t overlays_data{};
    overlays_data.table = nullptr;
    overlays_data.len = 0;

#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "SWE1Racer",
        "==> Before register_overlays: sections=%zu, overlays=%zu, table=%p",
        sections_data.num_code_sections, overlays_data.len, overlays_data.table);
#endif
    recomp::overlays::register_overlays(sections_data, overlays_data);
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "SWE1Racer", "==> After register_overlays OK");
#endif

    if (!recomp::register_game(game_entry)) {
        fprintf(stderr, "Failed to register game!\n");
#if defined(__ANDROID__)
        __android_log_print(ANDROID_LOG_ERROR, "SWE1Racer", "Game registration failed");
#endif
        return 1;
    }
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "SWE1Racer", "Game registration succeeded");
#endif

    printf("[SWE1R] Game registered: %zu sections, %zu functions in main section\n",
           num_sections, section_table[0].num_funcs);

    // Keep all mutable runtime data inside the app sandbox on Android.
#if defined(__ANDROID__)
    const char* internal_storage = SDL_AndroidGetInternalStoragePath();
    if (internal_storage == nullptr) {
        fprintf(stderr, "[SWE1R] Android internal storage path is unavailable\n");
#if defined(__ANDROID__)
        __android_log_print(ANDROID_LOG_ERROR, "SWE1Racer", "Android internal storage path unavailable");
#endif
        return 1;
    }
    const std::filesystem::path config_path = std::filesystem::path(internal_storage) / "swe1racer";
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "SWE1Racer", "Using config path: %s", config_path.string().c_str());
#endif
#else
    const std::filesystem::path config_path = std::filesystem::current_path();
#endif
    std::filesystem::create_directories(config_path);
    recomp::register_config_path(config_path);

    // Load the user's own ROM before the renderer or game thread starts.
    std::u8string game_id = u8"swe1racer";
    if (!recomp::load_stored_rom(game_id)) {
#if defined(__ANDROID__)
        const std::filesystem::path rom_path = config_path / "roms" / "selected.z64";
        std::filesystem::create_directories(rom_path.parent_path());
        const Uint64 deadline = SDL_GetTicks64() + 180000;
        printf("[SWE1R] Waiting for a user-provided ROM in Android's document picker\n");
#if defined(__ANDROID__)
        __android_log_print(ANDROID_LOG_INFO, "SWE1Racer", "Waiting for ROM at %s", rom_path.string().c_str());
#endif
        while (!std::filesystem::is_regular_file(rom_path) && SDL_GetTicks64() < deadline) {
            SDL_Delay(100);
        }
#if defined(__ANDROID__)
        __android_log_print(ANDROID_LOG_INFO, "SWE1Racer", "ROM wait ended; file exists=%d", std::filesystem::is_regular_file(rom_path));
#endif
#else
        const std::filesystem::path rom_path = std::filesystem::current_path() / "baserom.z64";
#endif
        const recomp::RomValidationError rom_result = recomp::select_rom(rom_path, game_id);
        if (rom_result != recomp::RomValidationError::Good) {
            fprintf(stderr, "[SWE1R] ROM selection failed (%d): %s\n", int(rom_result), rom_path.string().c_str());
#if defined(__ANDROID__)
            __android_log_print(ANDROID_LOG_ERROR, "SWE1Racer", "ROM selection failed: %d at %s", int(rom_result), rom_path.string().c_str());
#endif
            return 1;
        }
    }
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "SWE1Racer", "ROM loaded; continuing runtime startup");
#endif

    // Build the configuration
    recomp::Configuration cfg{};
    cfg.project_version = { 0, 1, 0, "-alpha" };

    // RSP callbacks
    recomp::rsp::callbacks_t rsp_callbacks{};
    rsp_callbacks.get_rsp_microcode = get_rsp_microcode;
    cfg.rsp_callbacks = rsp_callbacks;

    // Renderer callbacks
    ultramodern::renderer::callbacks_t renderer_callbacks{};
    renderer_callbacks.create_render_context = create_render_context_callback;
    cfg.renderer_callbacks = renderer_callbacks;

    // Audio callbacks
    ultramodern::audio_callbacks_t audio_callbacks{};
    audio_callbacks.queue_samples = swe1r_queue_samples;
    audio_callbacks.get_frames_remaining = swe1r_get_frames_remaining;
    audio_callbacks.set_frequency = swe1r_set_frequency;
    cfg.audio_callbacks = audio_callbacks;

    // Input callbacks
    ultramodern::input::callbacks_t input_callbacks{};
    input_callbacks.poll_input = poll_input;
    input_callbacks.get_input = get_input;
    input_callbacks.set_rumble = set_rumble;
    input_callbacks.get_connected_device_info = get_connected_device_info;
    cfg.input_callbacks = input_callbacks;

    // GFX callbacks (window management)
    ultramodern::gfx_callbacks_t gfx_callbacks{};
    gfx_callbacks.create_gfx = create_gfx;
    gfx_callbacks.create_window = create_window;
    gfx_callbacks.update_gfx = update_gfx;
    cfg.gfx_callbacks = gfx_callbacks;

    // Events callbacks
    ultramodern::events::callbacks_t events_callbacks{};
    events_callbacks.vi_callback = vi_callback;
    events_callbacks.gfx_init_callback = gfx_init_callback;
    cfg.events_callbacks = events_callbacks;

    // Error handling
    ultramodern::error_handling::callbacks_t error_callbacks{};
    error_callbacks.message_box = error_message_box;
    cfg.error_handling_callbacks = error_callbacks;

    // Threads
    ultramodern::threads::callbacks_t threads_callbacks{};
    threads_callbacks.get_game_thread_name = get_game_thread_name;
    cfg.threads_callbacks = threads_callbacks;

    printf("[SWE1R] Starting runtime...\n");
    printf("  \"It's working! IT'S WORKING!\" - Anakin Skywalker\n\n");

    // Game is started from gfx_init_callback after renderer is ready.
    // This blocks until the game exits.
    recomp::start(cfg);

    // Cleanup
    if (g_window) {
        SDL_DestroyWindow(g_window);
    }
    SDL_Quit();

    printf("\n[SWE1R] Shutdown complete.\n");
    return 0;
}
