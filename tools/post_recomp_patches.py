#!/usr/bin/env python3
"""
post_recomp_patches.py — re-apply hand-written redirects that can't be expressed
in symbols.toml (i.e. routing a recompiled game function to a different
ultramodern *_recomp reimplementation).

Run after every N64Recomp regeneration, together with fix_fallthroughs.py:
    N64Recomp.exe recomp.toml
    py tools/fix_fallthroughs.py
    py tools/post_recomp_patches.py

Each entry redirects a function's body to call <target>(rdram, ctx) and return,
discarding the recompiled body. Idempotent.

Why these can't be symbols.toml renames:
- func_80087D70 is the game's osPiStartDma, but the name "osPiStartDma" is
  already taken by another copy (0x80095820). Routing it to ultramodern's
  osPiStartDma_recomp bypasses the stubbed PI-manager thread so DMAs actually
  complete (otherwise the game blocks forever on the DMA reply queue).
"""
import re, glob, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RECOMP_DIR = os.path.join(ROOT, "RecompiledFuncs")
MARKER = "@post-recomp-redirect"

# func name -> ultramodern reimplementation to call instead
REDIRECTS = {
    "func_80087D70": "osPiStartDma_recomp",   # game osPiStartDma -> bypass dead PI manager
    # Android 2026-10-20 (Claude): the game's own osSetThreadPri. The init thread (func_8002F550) starts the game
    # thread (pri 10) and then calls osSetThreadPri(NULL, 0) on itself before parking in pause_self. The recompiled
    # copy only edits the game's private run queue (__osRunQueue 0x800A7BB8 / __osRunningThread 0x800A7BC0), so the
    # runtime never switches to the game thread: it never runs (0 CPU in simpleperf). The runtime version lowers the
    # caller's priority and then switches to the higher-priority game thread.
    "func_8008BFB0": "osSetThreadPri_recomp",
    # Android 2026-10-20 (Claude): game libultra copies that recomp.toml STUBS (empty bodies) although the game uses
    # their results. Identified from Ghidra pseudo-C of each stub. A stub leaves v0 = garbage and drops the side effect.
    "func_8008C930_exception": "osSendMesg_recomp",   # (first+valid)%count store + wake; scheduler func_8008BC30 sends with it
    "func_8008C7B0": "osJamMesg_recomp",              # same shape, stores at the front of the queue
    "func_8008C770": "osViGetCurrentFramebuffer_recomp",  # returns __osViCurr->framep; func_80085D4C picks its draw buffer by it
    "func_80090660": "osGetTime_recomp",              # osGetCount() delta + 64-bit base; used by func_80089870
    "func_8008C280": "osEepromProbe_recomp",          # SI status & 0xC000 -> 1 (4K) / 2 (16K); game_entry.save_type = Eep4k
    "func_8008C100_dispatch": "osEepromLongRead_recomp",   # loops osEepromRead per 8-byte block
    "func_8008C190": "osEepromLongWrite_recomp",      # loops osEepromWrite per 8-byte block with an osSetTimer wait
    # Controller/pak/rumble: the game's copies talk to the fake PIF in src/main.cpp, which reports a pad but never
    # returns buttons. The runtime versions read the host input callbacks (get_input in main.cpp).
    "func_80089800": "osContSetCh_recomp",            # __osMaxControllers = min(ch, 4); last cmd = 0xFE
    "func_80089870": "osContInit_recomp",             # osGetTime wait, request status, __osContGetInitData
    "func_80089BB0": "osContStartReadData_recomp",    # last cmd = 1 (CONT_CMD_READ)
    "static_0_80089C34": "osContGetReadData_recomp",  # real start 0x80089C34 (func_80089C44 is its fallthrough tail)
    "func_80089D90": "osPfsInitPak_recomp",           # __osPfsGetStatus, set queue/channel, check id
    "func_8008B424": "osMotorInit_recomp",            # writes 0xFE/0x80 to the pak probe address
    "func_8008B1B0": "racer_osMotorAccess",           # __osMotorAccess(pfs, on): src/main.cpp -> osMotorStart/Stop_recomp
    # VI: the game's copies only write __osViNext (0x800A7F54) in RDRAM, which the runtime never reads -> no frame is
    # ever presented ("framebuffer swaps #0"). Runtime versions update ultramodern's VI state that update_screen uses.
    "func_8008A2F0": "osViSetMode_recomp",            # __osViNext->modep = a0; state = 1; control = modep->comRegs.ctrl
    "func_8008A360": "osViSwapBuffer_recomp",         # __osViNext->framep = a0; state |= 0x10
    "func_8008A3B0": "osViBlack_recomp",              # state |= 0x20 / &= ~0x20 (func_8008A420 split off in symbols.toml)
    # Android 2026-10-21 (Claude): the game's osPiReadIo(devAddr, *data). Its raw read (0x80093890 ->
    # osWritebackDCache_80093898) is in the stub list, so *data was never written: the asset table count at ROM
    # 0x141E200 read as 0 and every model/part load (func_800305F4) returned NULL -> black screen after the legal
    # screen and the 2 s NULL list scans ([bad-addr] 0x80000000). src/main.cpp reads the ROM word instead.
    "func_8008A710": "racer_osPiReadIo",
    # Android 2026-10-21 (Claude): the game's spin-wait for the last graphics task -> same loop with a 0.2 ms sleep
    # (src/main.cpp). The spin held the big core at 100% and throttled the phone.
    "func_8002E034": "racer_wait_gfx_done",
    # Android 2026-10-21 (Claude): the game's AI copies write the audio-interface registers (0xA4500000), which the
    # memory hook absorbs, so no buffer ever reached SDL. The runtime versions feed src/audio.cpp.
    "func_8008815C": "osAiSetFrequency_recomp",       # DACRATE/BITRATE from a0 (Hz), returns the real rate
    "func_800882B0": "osAiSetNextBuffer_recomp",      # AI_DRAM_ADDR = virt-to-phys(a0), AI_LEN = a1
    "static_0_800883E0": "osAiGetLength_recomp",      # return AI_LEN
    "static_0_8008ADA0": "osAiGetStatus_recomp",      # return AI_STATUS
}

def main():
    patched = 0
    for fp in glob.glob(os.path.join(RECOMP_DIR, "funcs_*.c")):
        lines = open(fp, encoding="utf-8", errors="replace").read().split("\n")
        out = []
        i = 0
        changed = False
        while i < len(lines):
            out.append(lines[i])
            m = re.match(r'^RECOMP_FUNC\s+void\s+(\w+)\s*\(', lines[i])
            if m and m.group(1) in REDIRECTS:
                name = m.group(1); target = REDIRECTS[name]
                # copy the two prologue decl lines, then inject the redirect
                # (only if not already injected)
                lookahead = "\n".join(lines[i:i+8])
                if MARKER in lookahead:
                    i += 1; continue
                # emit decls (next 2 non-empty lines are the hi/lo + c1cs decls)
                j = i + 1
                while j < len(lines) and lines[j].strip() in (
                        "uint64_t hi = 0, lo = 0, result = 0;", "int c1cs = 0;"):
                    out.append(lines[j]); j += 1
                out.append(f"    // {MARKER}: route to {target}")
                out.append(f"    {target}(rdram, ctx);")
                out.append("    return;")
                i = j; changed = True; patched += 1
                continue
            i += 1
        if changed:
            open(fp, "w", encoding="utf-8").write("\n".join(out))
    print(f"post-recomp redirects applied: {patched}")

if __name__ == "__main__":
    main()
