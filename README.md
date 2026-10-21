# Star Wars Racer Recomp

A native Android port of **Star Wars Episode I: Racer** (Nintendo 64), made by *static recompilation*: the game's own
MIPS code is translated to C with [N64Recomp](https://github.com/N64Recomp/N64Recomp) and compiled ahead of time for
the phone, instead of being emulated. Graphics go through [RT64](https://github.com/rt64/rt64) on Vulkan, and the
console's OS layer comes from [N64ModernRuntime](https://github.com/N64Recomp/N64ModernRuntime).

It is built on **[sp00nznet/racer](https://github.com/sp00nznet/racer)**, sp00nznet's N64Recomp project for this game:
the map of the game's functions, the recompiler setup and the runtime glue all come from there (see
[Credits](#credits-and-licence)). This repository adds the Android app and the fixes that took it from "display lists
reach the renderer" to a moving picture on a phone.

> **This repository contains no game data.** You recompile the game from your own legally dumped cartridge, and
> nothing made from it is ever committed or shared. Please do not share the APKs you build (they contain the game).

## Status

Work in progress, but playable. On a Google Pixel 8 a full Tournament race plays start to finish with the touch
pad, in 4:3, with sound, and the progress saves and loads again after the app is closed.

| Part | State |
|---|---|
| Boot, game loop, asset loading | works |
| Picture (RT64 on Vulkan, Mali-G715) | works, 4:3, textures correct; small garbage blocks below the picture corners |
| ROM import (system file picker) | works |
| On-screen touch pad | works (menus and a full race); every button checked; L removed (Racer does not use it); BOOST button (one thumb: hold to charge, fires by itself after 1.2 s, quick re-tap fires now) |
| Gamepad | implemented, not tested |
| Sound (audio microcode recompiled with RSPRecomp) | works; not yet checked for crackle or drift over long play |
| Saves (16 Kbit EEPROM) | works: Tournament progress survives closing the app |
| Home / app switch / screen lock | works: the game resumes where it was; landscape only |
| Start screen (ROM picker, Play) | works |
| Side menu: aspect 4:3 / 16:9 / 21:9 (true widescreen), sharp / smooth, resolution, FPS counter, pad options | works (in widescreen the HUD and menus stay 4:3-wide in the middle) |
| Pad editor, save states, HUD out at the wide edges | planned |

Every fix it took is listed with its cause in [docs/ANDROID-FIXES.md](docs/ANDROID-FIXES.md).

## Tested on

- **Phone:** Google Pixel 8 (Mali-G715, Android 16). Other arm64 phones with Vulkan should work but have not been tried.

## What you need

- Your own dump of the game (checked by hash when the app loads it):

  | Dump | SHA-1 |
  |---|---|
  | Star Wars Episode I: Racer (USA), `.z64` | `3542d5597c8a56ea8f5c63bceae97a24c4c08d58` |

- A Windows PC with Git, Python 3, Java 17, the Android SDK (platform 35) and NDK 28.2, CMake 3.22, and an N64Recomp
  build (`N64Recomp.exe`).
- An Android 7+ phone (arm64, Vulkan) and a USB cable.

## Building it

```
git clone --recursive https://github.com/dguillot-gh/star-wars-racer-recomp.git
```

1. Copy `recomp.toml` to `recomp.local.toml` and set `rom_file_path` to your ROM.
2. Recompile: run N64Recomp on `recomp.local.toml`, then `tools/fix_fallthroughs.py`, `tools/post_recomp_patches.py`
   and `tools/gen_lookup_table.py`. This writes `RecompiledFuncs/` (made from your ROM: never commit it).
   For sound, copy `aspMain.toml` to `aspMain.local.toml`, set `rom_file_path`, and run `RSPRecomp` on it
   (writes `rsp/aspMain.cpp`, also made from your ROM).
3. Build the app: `gradle -p android :app:assembleDebug` (set `ANDROID_HOME`, `ANDROID_NDK_HOME` and `JAVA_HOME`
   first). The APK is `android/app/build/outputs/apk/debug/app-debug.apk`.
4. Install it with `adb install -r` and open it: the start screen asks for your ROM (file picker; .z64, .v64 or
   .n64, checked by SHA-1), then Play.

## What the build does to your PC

Nothing outside the repository folder and your tool folders: N64Recomp reads your ROM (never changes it) and writes
`RecompiledFuncs/`; Gradle writes `android/app/build/` and downloads Android libraries from Google and Maven Central the
first time. Nothing is uploaded, installed system-wide, or left running.

## How it works

- `symbols.toml` lists the game's ~1,400 functions and `recomp.toml` says which ones are replaced by the runtime.
  N64Recomp turns each into a C function.
- `tools/post_recomp_patches.py` routes the game's own copies of some libultra functions (thread priority, messages,
  VI, controller, EEPROM, PI reads) to N64ModernRuntime, because the copies only update the game's private state.
- `src/main.cpp` is the host: ROM loading, the memory guard for device registers and bad pointers, saves, input.
  `src/rt64_render_context.cpp` hands display lists to RT64.
- `android/` is the Gradle app: an SDL2 activity, the ROM picker and the touch pad.

## Made with AI assistance

This port was built with AI help: the project owner worked with Qwen (a local model) and Claude (Anthropic), which found and wrote most of the fixes. Each fix in
[docs/ANDROID-FIXES.md](docs/ANDROID-FIXES.md) says who found it. Everything was tested on a real phone, but expect
rough edges, and please report what you find.

## Credits and licence

- **[sp00nznet/racer](https://github.com/sp00nznet/racer)** by sp00nznet: the original N64Recomp project for this game.
  From it this port uses the function map (`symbols.toml`), the recompiler config (`recomp.toml`), the host program
  (`src/main.cpp`, `src/rt64_render_context.cpp`, `src/audio.cpp`, `src/section_table.cpp`), the build files and the
  regen tools (`tools/fix_fallthroughs.py`, `tools/gen_lookup_table.py`, `tools/post_recomp_patches.py`). All of these
  were changed for Android; its other analysis tools are kept as they were. Its README is kept as
  [docs/ORIGINAL-README.md](docs/ORIGINAL-README.md).
- N64Recomp and N64ModernRuntime (Mr-Wiseguy and contributors), RT64 (DarioSamo and contributors), and AurelioB's
  Android forks of them, which the submodules here fork again with this port's patches. More in
  [NOTICE.md](NOTICE.md) and [CREDITS.md](CREDITS.md).

MIT ([LICENSE](LICENSE)), as sp00nznet's project; the licence's Scope section explains what it cannot cover.
*Star Wars Episode I: Racer* is © Lucasfilm Ltd.; this project is not affiliated with or endorsed by Lucasfilm,
LucasArts, Disney or Nintendo.
