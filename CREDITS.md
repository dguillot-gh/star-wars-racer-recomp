# Credits

This Android port of Star Wars Episode I: Racer stands on other people's work:

- **sp00nznet/racer** - the original N64Recomp setup for Racer (symbols, recomp config, desktop runtime glue).
- **N64Recomp / N64ModernRuntime** (Mr-Wiseguy and contributors) - the static recompiler and the libultra runtime.
- **RT64** (DarioSamo and contributors) - the N64 renderer; **plume** - its graphics API layer.
- **BanjoRecomp-Android** (AurelioB) - the Android forks of N64ModernRuntime and RT64 this project builds against, and
  the SDLActivity/Gradle/NDK layout.
- **Goemon64Recomp-Android** (ogdanimal) - Android N64Recomp reference: the per-task RSP state reset in
  `src/rt64_render_context.cpp` `send_dl`, the RT64 Android data-path notes, the Vulkan device-requirement notes, and
  the audio microcode (`aspMain.toml`) layout used as the model for Racer's sound. https://github.com/ogdanimal/Goemon64Recomp-Android
- **Zelda64Recomp** (Mr-Wiseguy and contributors) - the original RT64 render-context pattern the above follow.
- **SW_RACER_RE** (tim-tim707) - reverse-engineering notes for the PC version of Racer.
- **SDL2** (libsdl-org) - window, input and the Android SDLActivity.
- **Ghidra** (NSA) - decompiler used to read game functions while porting.
- **Qwen 3.6 / 3.8 27B** (local model) and **Claude** (Anthropic) did the Android porting work with the project owner;
  `docs/ANDROID-FIXES.md` says who found which fix.

Racer is (c) Lucasfilm / LucasArts. No game data is included; bring your own ROM.
