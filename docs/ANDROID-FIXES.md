# Android port: what it took to run on a Pixel 8

Target: Google Pixel 8 (Tensor G3, Mali-G715, Vulkan 1.4, Android 16), package `com.sp00nznet.swep1racer`.
Base: sp00nznet/racer (desktop N64Recomp project), built against AurelioB's Android forks of N64ModernRuntime and
RT64 (see CREDITS.md). Work done 2026-10-20 to 2026-10-21 by the project owner with Qwen 3.6/3.8 27B (local model)
and Claude (Anthropic). "Found by" says who diagnosed each one.

Each fix: symptom -> cause -> change (files).

## Boot

1. **Crash on the first device-register write** (found by Qwen). Recompiled code wrote 0xA4xxxxxx registers straight
   into unmapped host memory (Android maps only 512 MB of the 4 GB RDRAM reservation).
   -> `lib/N64ModernRuntime/N64Recomp/include/recomp.h` memory hook + `rdram_mmio_write` scratch in `src/main.cpp`.
2. **Null VI mode in update_vi** (Qwen). -> guard in `ultramodern/src/events.cpp`.
3. **osSetEventMesg ignored OS_EVENT_VI** (Qwen). -> `ultramodern/src/events.cpp`.
4. **Game thread never ran** (Claude, simpleperf on the phone). The game's own osSetThreadPri copy (func_8008BFB0)
   only edits the game's private run queue. -> redirect to `osSetThreadPri_recomp` (`tools/post_recomp_patches.py`).
5. **printf printed from NULL / stubbed software** (Claude). `recomp.toml` stubbed 114 functions above 0x80087000, 87 of
   them plain software (printf `_Putfld`, fill-rect drawer, ...). -> un-stubbed; ldiv start fixed in `symbols.toml`.
6. **Stubbed libultra copies the game relies on** (Claude). -> 14 redirects to runtime versions: osSendMesg,
   osJamMesg, osViGetCurrentFramebuffer, osGetTime, EEPROM probe/long read/long write, controller (SetCh, Init,
   StartReadData, GetReadData), osPfsInitPak, osMotorInit, `__osMotorAccess` (`racer_osMotorAccess` in main.cpp).

## Renderer

7. **Garbage GBI commands (unknown opCode 0x3F)** (Claude). RSP state was never reset between tasks.
   -> `src/rt64_render_context.cpp` `send_dl` calls `rsp->reset()` (pattern from Goemon64Recomp-Android / Zelda64Recomp).
8. **"Failed to find function at 0x80044414"** (Claude). A function-pointer table (0x800A1FF4-0x800A2118) points into
   the middle of merged symbols. -> 12 functions split in `symbols.toml`.
9. **Mali shader-compiler crashes at startup** (Claude). RT64 compiled ubershader pipelines on several threads.
   -> `lib/rt64/src/render/rt64_raster_shader.cpp` one compile at a time on Android; ubershaders only
   (`rt64_state.cpp`, `src/rt64_render_context.cpp`).
10. **Heap corruption that showed up inside the Mali compiler** (Claude; caught with MTE on the Pixel 8).
    Racer uses 16 Kbit EEPROM; save_type was Eep4k (512-byte buffer) and the bounds check was an assert.
    -> `src/main.cpp` save_type Eep16k; `librecomp/src/pi.cpp` save_read/save_write refuse out-of-range.
    Also: byte/halfword accesses (MEM_B/MEM_H) bypassed the memory hook, KSEG1 RDRAM now mirrors RDRAM,
    out-of-range accesses are absorbed and logged as `[bad-addr]` (`recomp.h`, `src/main.cpp`).
11. **No frame ever presented** (Claude). The game's osViSetMode/SwapBuffer/Black copies only wrote RDRAM.
    -> redirects to the runtime versions; func_8008A420 split off.
12. **Black legal screen** (Claude). Racer leaves the VI blank flag on (its mode has hStart 0).
    -> `ultramodern/src/events.cpp` forces the NTSC hStart; `lib/rt64/src/hle/rt64_vi.cpp` fbAddress uses the real
    framebuffer width in Racer's 640-wide interlaced mode. **First picture: the legal screen.**

## After the legal screen

13. **Hang in unterminated lists** (Claude). The libaudio synth was still stubbed. -> 39 libaudio functions
    un-stubbed, 5 handler functions split in `symbols.toml`.
14. **NULL calls in alAudioFrame, VI given an audio struct** (Claude). `symbols.toml` named 4 libaudio functions with
    libultra names, so N64Recomp swapped in runtime functions (0x8008D510 "osViSetMode" = alSaveNew, 0x80090170,
    0x800903D0, 0x80090450). -> renamed to func_XXXXXXXX. Rule: never give a symbol a libultra name without
    checking its code.
15. **Hang after ~46 s** (Claude). `osGetCount_recomp` zero-extended; Racer's 64-bit clock counted a wrap on every
    read. -> sign-extend in `librecomp/src/ultra_translation.cpp`.
16. **Black screen after the legal screen + 2 s stalls** (Claude, 2026-10-21). The game's osPiReadIo copy
    (func_8008A710) calls a stubbed raw read, so word reads from ROM returned 0: the asset table count at ROM
    0x141E200 read as 0 and every model/part load returned NULL, then list scans walked from address 0
    (`[bad-addr] 0x80000000`). -> redirect to `racer_osPiReadIo` (`src/main.cpp`), which reads the ROM word.

17. **Picture squeezed into a narrow strip** (Claude, 2026-10-21). Racer draws 320x480 frames and shows them through
    an interlaced VI mode with width 640 and xScale 0.5, so each pixel is two dots wide. RT64 sized the picture from
    the 320x476 framebuffer as if pixels were square. -> `lib/rt64/src/render/rt64_vi_renderer.cpp` sizes it by the
    VI width; `rt64_state.cpp` / `rt64_workload_queue.cpp` pass the same 4:3 ratio as the aspect source.

## Build notes

- Native build type RelWithDebInfo (`android/app/build.gradle`); Debug (-O0) builds hid the heap corruption.
- `librecomp/src/overlays.cpp` get_function logs the caller and treats address 0 as a no-op (`[null-call]`).
- Still open: sound (audio microcode is a no-op; needs RSPRecomp of aspMain, model: Goemon64Recomp-Android).
