# Aura XR Client (Unity 6)

Unity 6 + OpenXR client for Android XR. Talks to `aura-bridge` over WebSocket
using XR protocol v1 (`../protocol`).

## First open (one-time editor setup)

1. Open this folder in Unity **6000.3.22f1** (installed via Unity Hub with the
   Android module). Package resolution pulls the Android XR OpenXR plug-in,
   XR Interaction Toolkit, XR Hands, and AR Foundation from `Packages/manifest.json`.
2. **Project Settings → XR Plug-in Management**: enable **OpenXR** for Android;
   under OpenXR features enable **Android XR** feature group, **Hand Tracking
   Subsystem**, and **Hand Interaction Profile**.
3. **Project Settings → Player (Android)**: Graphics API **Vulkan** only,
   minimum API level per Android XR requirements, ARM64.
4. **Graphics**: assign a URP pipeline asset (create default URP assets if absent).
5. Create scene `Assets/Scenes/Main.unity`:
   - `XR Origin (XR Rig)` from XR Interaction Toolkit samples
   - empty `AuraClient` GameObject with `Aura.Net.AuraClient`
   - world-space canvas ~1.2 m ahead with a TextMeshPro text; add
     `Aura.UI.StatusPanel` wired to the client and text.
6. Run `aura-bridge` on this machine (`npm run dev` in `../aura-bridge`), then
   enter Play mode (or deploy to the Android XR emulator). The panel should show
   `bridge ● connected · herdr ● connected` and live workspace/pane/agent counts.

## Generated protocol types

`Assets/Scripts/Protocol/Generated/` is produced by `../protocol/scripts/gen-csharp.sh`.
Do not edit by hand; regenerate after protocol changes.

## Android XR emulator

Install via Android Studio (SDK Manager → Android XR system image + emulator).
The bridge URL for an emulator is `ws://10.0.2.2:8137` (host loopback); set it on
the `AuraClient` component for emulator builds.
