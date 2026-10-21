# Notices

Star Wars Racer Recomp is a native Android port of *Star Wars Episode I: Racer* (Nintendo 64) by static
recompilation. It contains **no** game code or data: the game is recompiled from your own copy of the ROM.
*Star Wars Episode I: Racer* is © Lucasfilm Ltd. This project is not affiliated with or endorsed by Lucasfilm,
LucasArts, Disney or Nintendo.

## Where the code comes from

| Part | Origin | Licence |
|---|---|---|
| `symbols.toml` (the game's function map), `recomp.toml`, `src/main.cpp`, `src/rt64_render_context.cpp`, `src/audio.cpp`, `src/section_table.cpp`, `CMakeLists.txt`, `Makefile`, `tools/`, `docs/IDA_ANALYSIS_HANDOFF.md`, `docs/ORIGINAL-README.md` | [sp00nznet/racer](https://github.com/sp00nznet/racer) by sp00nznet. This port changed `symbols.toml` (function splits and renames), `recomp.toml` (stub list), the `src/` files, `CMakeLists.txt` and `tools/post_recomp_patches.py`; the rest is unchanged | MIT |
| `android/`, `include/recomp_hooks.h`, `cmake/`, `docs/ANDROID-FIXES.md`, the Android parts of the `src/` files | written for this port | MIT |
| `lib/N64ModernRuntime` (with `N64Recomp` inside) | [N64ModernRuntime](https://github.com/N64Recomp/N64ModernRuntime) and [N64Recomp](https://github.com/N64Recomp/N64Recomp) by Mr-Wiseguy and contributors, through [AurelioB's Android fork](https://github.com/AurelioB/N64ModernRuntime); this port's patches on the `racer-android` branches of dguillot-gh/N64ModernRuntime and dguillot-gh/N64Recomp | MIT |
| `lib/rt64` | [RT64](https://github.com/rt64/rt64) by DarioSamo and contributors, through [AurelioB's Android fork](https://github.com/AurelioB/rt64); this port's patches on the `racer-android` branch of dguillot-gh/rt64 | MIT |
| `lib/SDL2` | [SDL](https://libsdl.org) | zlib |

Patterns followed, with no code copied unless noted: the per-task RSP reset in `send_dl` and the audio-microcode plan
from [Goemon64Recomp-Android](https://github.com/ogdanimal/Goemon64Recomp-Android) (ogdanimal) and
[Zelda64Recomp](https://github.com/Zelda64Recomp/Zelda64Recomp); the Android Gradle/SDL layout from AurelioB's
BanjoRecomp-Android. [SW_RACER_RE](https://github.com/tim-tim707/SW_RACER_RE) (tim-tim707) notes on the PC version
were used as reference. [Ghidra](https://ghidra-sre.org) (NSA) was used to read game functions; it is not included.

Fixes were found by the project owner with Qwen (a local model) and Claude (Anthropic); `docs/ANDROID-FIXES.md` says
who found which.
