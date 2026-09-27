# LensCode XR Client (Unity 6)

Unity 6 + OpenXR client for Android XR. Talks to `lenscode-bridge` over WebSocket
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
   - empty `LensCodeClient` GameObject with `LensCode.Net.LensCodeClient`
   - world-space canvas ~1.2 m ahead with a TextMeshPro text; add
     `LensCode.UI.StatusPanel` wired to the client and text.
6. Run `lenscode-bridge` on this machine (`npm run dev` in `../lenscode-bridge`), then
   enter Play mode (or deploy to the Android XR emulator). The panel should show
   `bridge ● connected · herdr ● connected` and live workspace/pane/agent counts.

## Generated protocol types

`Assets/Scripts/Protocol/Generated/` is produced by `../protocol/scripts/gen-csharp.sh`.
Do not edit by hand; regenerate after protocol changes.

## M3 — spatial shell & interaction (scripts ready, scene wiring is manual)

`Assets/Scripts/Interaction/PinchGesture.cs` and `Assets/Scripts/Panels/`,
`Assets/Scripts/UI/` implement the interaction hierarchy from spec §9
(gaze → hover, pinch → select, pinch+hold → context menu, drag → move) on top
of the real XR Interaction Toolkit `XRBaseInteractable` events — verified
against the installed package source, not guessed. These need Editor-GUI scene
wiring that can't be scripted headlessly:

1. Add an `XRSimpleInteractable` + `PinchGesture` to each panel's grab handle,
   and an `LensCodePanel` on the panel root (assign a unique `panelId`).
2. Add one `WorkspaceLayoutController` to the scene, referencing all `LensCodePanel`s
   (or leave the array empty — it falls back to `FindObjectsByType`).
3. Build the agent strip: one `AgentStatusItem` (+ `XRSimpleInteractable` +
   `PinchGesture`) per slot, referenced by an `AgentStripController`.
4. Build the quick dock: one `DockButton` per view (`code`, `terminal`, `diff`,
   `git`, `agents`, `remote` — matching the mockup), referenced by a
   `DockController`.
5. XR rig: use the XR Interaction Toolkit's Android XR / hand-tracking sample
   rig so interactors come from real hand pinch (via `XR_EXT_hand_interaction`)
   in both the XR Device Simulator (editor) and on-device.

## Android XR emulator

Install via Android Studio (SDK Manager → Android XR system image + emulator).
The bridge URL for an emulator is `ws://10.0.2.2:8137` (host loopback); set it on
the `LensCodeClient` component for emulator builds.
