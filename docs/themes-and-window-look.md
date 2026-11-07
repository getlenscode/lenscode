# Themes and window look

Theme rules and the limits of the Home Space window.

- Themes live in `ui/Theme.kt` (`ThemeId`, palettes, corner radii); the picker is Settings → App theme. **Terminal** (default) is square corners, thin outlines, flat translucent brown fills and monospace type. Small corners must go through `lensShape(dp)`, never `RoundedCornerShape(dp)` directly, or a sharp theme gets stray rounded corners.
- The app runs in Home Space (`FULL_SPACE_ENABLED = false` in `MainActivity.kt`; don't switch it on without asking). A Home Space window is opaque (transparent pixels render black) and its outer rounded frame is drawn by the XR system. Setting `scene.mainPanelEntity.cornerRadius` is accepted and reads back, but the frame does not change (tried 2026-10-20). So panels sit on a themed backdrop gradient (`windowBg`/`windowBgAlt`) instead of the room; real see-through needs `SpatialPanel`s in Full Space.
- Headset screenshots: `adb -s <dev> exec-out screencap -p` prints a "multiple displays" warning before the PNG, so strip everything before the `\x89PNG` signature. A 3840x1200 all-black capture means the headset is asleep (`dumpsys power | grep mWakefulness`), not that the app is broken.
