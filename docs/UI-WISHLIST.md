# Android app wishlist

What the Android app does today, and what is still planned. Fixes to the game itself are in
[ANDROID-FIXES.md](ANDROID-FIXES.md).

## Done

- **Start screen** (`LauncherActivity`): Play, and a ROM picker that converts .v64 / .n64 dumps to .z64 and checks
  the SHA-1 of the supported USA dump. Shows whether a save exists. Original background art
  (`res/drawable/launcher_background.xml`, a dusk desert drawn for this port).
- **Game activity** (`SWE1RacerActivity`): its own `:game` process (SDL cannot start twice in one process), full
  screen, landscape only, Back asks before quitting.
- **On-screen pad** (`TouchPadView`): stick, A, B, Z, R, START and the four C buttons (L removed: Racer does not use
  it). Every button checked against the game's input.
- **BOOST button**: hold to accelerate and charge; it fires whenever the game reports the boost as ready
  (`nativeBoostState` reads the local racer's boost status from RAM), again and again while held. A quick re-tap
  fires at once.
- **Pad editor**: drag any control (BOOST too) to move it, tap it to resize it; Smaller / Bigger / Reset / Done.
  Saved as `pad_layout`.
- **Side menu** (`RacerMenu`, the ≡ button): Display (aspect 4:3 / 16:9 / 21:9, sharp / smooth, resolution 1x / 2x /
  3x / Fit, FPS counter), Controls (show pad, BOOST, move and resize controls, opacity, size), Game (quit).
- **Widescreen**: true 16:9 and 21:9 (RT64 draws more of the scene, nothing is stretched); the HUD keeps its real
  width in the middle 4:3 area, menus stay 4:3.
- **App icon**: an original vector podracer (adaptive icon, with an Android 7 fallback). No game art.

## Planned

1. **Keep the screen on and use the camera cutout area** (`FLAG_KEEP_SCREEN_ON`, cutout `SHORT_EDGES`).
2. **Pause when the app goes to the background**: today a race carries on while you are away. Press the game's START
   (pause) on `SDL_APP_WILLENTERBACKGROUND`, or stop the game clock until resume.
3. **Hide the pad when a gamepad is used**, back on the next touch.
4. **HUD out at the wide edges** in 16:9 / 21:9: move LAP / POS / the map / the speed dial to the screen edges by
   anchoring whole HUD groups (single rectangles cannot be moved: Racer draws text letter by letter).
5. **Save states and Continue** (native, bigger job): slots with thumbnails, then a Continue button on the start
   screen.
6. **Steady 60 fps at higher resolutions**: 1x holds about 60; 3x at 21:9 is about 50 on a warm phone. Next step:
   profile RT64's graphics thread.

## Style used by the app

| What | Value |
|---|---|
| Background | `0xFF101014` |
| Menu panel | `0xF0181820`, right side, width max(320dp, 40% of screen) |
| Row / chip off | `0xFF2A2A34`, 10dp corners, rows 48dp, chips 44dp, 6dp gap |
| Accent (section titles, chip on, switch on) | `0xFF8AB4FF` (chip on = black text) |
| Text | title 34sp bold white (26sp in landscape), header 22sp, rows 17sp, sections 13sp UPPERCASE, hints 12sp |
| Grey text | status `0xFFB0B0B8`, hint `0xFF9090A0`, dim `0xFF707078`, disabled `0xFF606068` on `0xFF202028` |
| Scrim | menu `0x55000000`, editor `0x66000000`; warning `0xFFFFD54F` |
