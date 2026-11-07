# LensCode

Android XR client (`client-android/`) -> `lenscode-bridge/` (Node/TS) -> Paseo daemon. Protocol schema in `protocol/`. Marketing/docs site in `website/`. Licensed GPL-3.0-or-later (root `LICENSE`).

## Quick start: build, install, run the Android app

```bash
cd client-android
./gradlew :app:assembleDebug -PbridgeUrl=ws://<mac-ip>:8137
adb -s <device-ip>:<port> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-ip>:<port> shell am force-stop com.lenscode.app
adb -s <device-ip>:<port> shell monkey -p com.lenscode.app -c android.intent.category.LAUNCHER 1
```

- My real addresses are in `CLAUDE.local.md` (gitignored, personal): @CLAUDE.local.md
- Both addresses change (the LAN IP with the network, the wireless-debugging port on every restart): get the device from `adb devices`. Details in [docs/android-build-and-release.md](docs/android-build-and-release.md).

## Rules that must not be broken

- **Bridge trust:** the store build refuses `ws://` (`secureOnly = !BuildConfig.DEBUG`); don't relax it. Never write a TrustManager that accepts everything. `PinnedTrustManager` stays an `X509ExtendedTrustManager`, and the fingerprint format stays identical in `selfSigned.ts` and `CertPin.kt`. Never commit `privkey.pem`, `*.key` or `*.pem`. See [docs/tls-and-bridge-trust.md](docs/tls-and-bridge-trust.md).
- **Keys live on the bridge, not the headset**: the headset only sees proxied calls and short-lived tokens. See [docs/voice-routing.md](docs/voice-routing.md) and [docs/speech-to-text.md](docs/speech-to-text.md).
- **Voice has no silent fallback:** a missing key or failed call raises the `VoiceProblemOverlay` popup and runs nothing; unknown machines, agents and models give a popup or a "did you mean" card, never a guess.
- **MCP control endpoint** stays on `127.0.0.1` (never the LAN). Keep the checks on tmux session names (`[A-Za-z0-9._-]{1,64}`), http(s) URLs and single-quoted folder paths. See [docs/mcp-implementation-notes.md](docs/mcp-implementation-notes.md).
- **Home Space:** `FULL_SPACE_ENABLED = false` in `MainActivity.kt`; don't switch it on without asking. Small corners go through `lensShape(dp)`, never `RoundedCornerShape(dp)`. See [docs/themes-and-window-look.md](docs/themes-and-window-look.md).
- **Releases:** bump `versionCode` in `app/build.gradle.kts` for every Play upload. Bump the bridge `version` and `BRIDGE_VERSION` together, and never publish the same npm version twice (`E409`). See [docs/bridge-publishing.md](docs/bridge-publishing.md).

## Docs

Read the one that matches the work; each is short.

**Build and release**
- [docs/android-build-and-release.md](docs/android-build-and-release.md): build, install, run; the Play Store AAB, signing and `versionCode`.
- [docs/tls-and-bridge-trust.md](docs/tls-and-bridge-trust.md): `--secure`, self-signed certificate pinning, public certificates, the local test CA.
- [docs/bridge-and-paseo.md](docs/bridge-and-paseo.md): ports, flags, running the bridge, LAN discovery (mDNS), `--tailscale`.
- [docs/bridge-publishing.md](docs/bridge-publishing.md): npm release steps for `lenscode-bridge`.

**Features**
- [docs/tasks-board.md](docs/tasks-board.md): the Tasks (Kanban) view and the Paseo Tasks plugin.
- [docs/voice-routing.md](docs/voice-routing.md): OpenRouter command routing, and switching machine, agent and model by voice.
- [docs/speech-to-text.md](docs/speech-to-text.md): AssemblyAI streaming dictation.
- [docs/browser-voice-address.md](docs/browser-voice-address.md): editing the Browser dialog's address by voice.
- [docs/claude-auto-mode.md](docs/claude-auto-mode.md): why new Claude agents start in Auto mode.
- [docs/mcp-implementation-notes.md](docs/mcp-implementation-notes.md): the MCP server's code layout and checks. The user guide is [docs/mcp-headset-control.md](docs/mcp-headset-control.md).
- [docs/themes-and-window-look.md](docs/themes-and-window-look.md): themes, the Home Space window, headset screenshots.
- [docs/follow-me.md](docs/follow-me.md): Follow Me requirements and the debug broadcast.
- [docs/website.md](docs/website.md): the `website/` dev server, Cloudflare deploy and icons.

**Existing background docs**
- [docs/architecture.md](docs/architecture.md), [docs/remote-setup-and-limits.md](docs/remote-setup-and-limits.md), [docs/agent-compatibility.md](docs/agent-compatibility.md), [docs/try-it-workflows.md](docs/try-it-workflows.md), [docs/paseo-migration-plan.md](docs/paseo-migration-plan.md), [docs/snip-and-agent-management-plan.md](docs/snip-and-agent-management-plan.md).
