# LensCode

Android XR client (`client-android/`) -> `lenscode-bridge/` (Node/TS) -> Paseo daemon. Protocol schema in `protocol/`. Marketing/docs site in `website/` (not tracked in git).

## Android XR app: build, install, run

```bash
cd client-android
./gradlew :app:assembleDebug -PbridgeUrl=ws://192.168.1.157:8137
adb -s 192.168.1.166:34347 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.166:34347 shell am force-stop com.lenscode.app
adb -s 192.168.1.166:34347 shell monkey -p com.lenscode.app -c android.intent.category.LAUNCHER 1
```

- Last used (2026-09-30): Mac/bridge `192.168.1.157:8137`, XR device `192.168.1.166:34347`.
- `bridgeUrl` is the Mac's LAN IP (a literal IP; `adb reverse` can't help). It changes with the network, so re-check it. It is only the first-run default now: the address saved in the app (Settings → Bridge connection, typed or dictated) wins, so a changed IP no longer needs a rebuild.
- The wireless-debugging port changes on every restart: run `adb devices` for the current address, and update the values above.
- Reinstalling resets the Follow Me toggle to off.

## Release build (Play Store)

- Play takes an **AAB**: `cd client-android && ./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`. (`:app:assembleRelease` gives an APK for side-loading.)
- Signing reads `client-android/keystore.properties` (gitignored, as are `*.jks`): `storeFile=` (relative to `client-android/`), `storePassword=`, `keyAlias=`, `keyPassword=`. Without that file the release build still works but is **unsigned**, and Play/adb will reject it.
- Create the upload key once and back it up (losing it means a support request to Google): `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`. Enroll in Play App Signing so Google holds the real app-signing key.
- Bump `versionCode` in `app/build.gradle.kts` for every upload (Play rejects a repeat); `versionName` is the semver shown to people (currently `0.1.0-alpha.N`).
- The bridge address is set on the headset (Settings → Bridge connection; the Speak button takes a dictated IP), so no address is baked into the release. Release builds deny cleartext: the app refuses `ws://` there with an explanation, so the bridge must run in prod mode with TLS (`--secure`) and be entered as `wss://host` (port 8443 is assumed for a secure address). The debug network config (`app/src/debug/res/xml/network_security_config.xml`) permits cleartext to any host, which is why debug accepts any LAN IP.
- TLS bridge for testing: `./scripts/make-dev-tls.sh <mac-ip>` makes a local CA + server cert in `~/.config/lenscode-bridge/tls/` (the cert is only valid for the address you give it). Run the bridge with `LENSCODE_MODE=prod LENSCODE_TLS_CERT=…/server.pem LENSCODE_TLS_KEY=…/server.key npm run dev`, enter `<mac-ip>` in the app (secure defaults to port 8443, so `wss://<mac-ip>:8443`), then pair on Home (the 6-digit code is printed in the bridge console). `test/tls.test.ts` covers this path automatically.
- Verified 2026-10-20: a debug build connected, paired and used the Gemini proxy over wss:// against a prod-mode bridge using that local CA. Debug builds only trust it if it is installed on the device (debug-only `user` trust in the debug network config; Android needs a screen lock before it installs a CA).
- **Self-signed + pinning (the store-friendly path):** `lenscode-bridge --secure` makes its own EC certificate (`~/.config/lenscode-bridge/self-signed/`, reused so pins survive restarts, renewed 30 days before expiry) and prints its fingerprint (first 80 bits of SHA-256, e.g. `510D-4148-395F-0618-6472`). On first contact the app probes the certificate (`PinnedTls.probeCertificate` — the handshake is recorded then deliberately failed, so no data is sent), shows `CertTrustOverlay`, and on confirmation stores the full SHA-256 in `PinStore` under `host:port`. After that `PinnedTrustManager` accepts the system's trust OR exactly that certificate; `PinnedHostnameVerifier` also accepts the pinned certificate for any host, so a changed IP keeps working. A changed certificate raises the same prompt with a red warning. Never write a TrustManager that accepts everything: Play flags it, and it defeats the point. `PinnedTrustManager` must stay an `X509ExtendedTrustManager` that passes the socket/engine variants through: debug builds have a `<domain-config>`, and then Android's trust manager rejects the plain 2-arg check for every certificate ("Domain specific configurations require that hostname aware checkServerTrusted ... is used"). The fingerprint format must stay identical in `lenscode-bridge/src/selfSigned.ts` and `net/CertPin.kt` (both have a test with the same vector).
- A public-CA certificate (`--tls-cert/--tls-key`: a domain + Let's Encrypt, `tailscale cert`, a tunnel) is still supported and needs no prompt. Never commit `privkey.pem`/`*.key`/`*.pem` (gitignored) — a shared private key lets anyone impersonate the bridge.

## Bridge and Paseo

- Paseo daemon: `paseo daemon` (listens on `ws://127.0.0.1:6767/ws`).
- Ports: plain `ws://` bridge = **8137**, secure (`--secure` / TLS) = **8443** (`defaultPort()` in `lenscode-bridge/src/config.ts`, mirrored by `DEFAULT_BRIDGE_PORT` / `DEFAULT_SECURE_BRIDGE_PORT` in `net/BridgeAddress.kt`). Typing a bare IP in the app picks the port from the scheme, so a store build (always secure) needs only the IP; `--port` overrides.
- Bridge: `./scripts/setup-single-pc.sh` (port 8137). Config via `LENSCODE_PORT`, `LENSCODE_PASEO_URL`. That script exits if the `paseo` CLI isn't on PATH even when the daemon is running; in that case `cd lenscode-bridge && npm run dev` does the same job.
- The bridge takes flags too (`npm run dev -- --port 9000`, `--tls-cert/--tls-key` for secure mode; `--help` lists them) and prints the address to enter in the app on startup.
- Distribution: `cd lenscode-bridge && npm run build` bundles `dist/lenscode-bridge.js` (esbuild; `@lenscode/protocol` is bundled in, the npm deps stay external), and `npm pack` makes the tarball. Verified 2026-10-20 by installing that tarball into an empty folder and running `lenscode-bridge` in dev and TLS modes. Published as the unscoped `lenscode-bridge` (the `@lenscode` npm scope does not exist, so `@lenscode/bridge` was refused with "Scope not found"); users run `npx lenscode-bridge`. **Published 2026-10-20 as `lenscode-bridge@0.1.0-alpha.1`** (verified: registry checksum matches, `npx lenscode-bridge --version` works from an empty folder). To release a new version: bump `version` here and `BRIDGE_VERSION` below, then `npm login` (if needed) and `cd lenscode-bridge && npm publish` — a version number can never be reused. Bump `version` in `lenscode-bridge/package.json` and `BRIDGE_VERSION` in `src/server.ts` together (a test checks they match).
- Remote machines are all registered hosts reached over SSH; there is no special "local" machine in the app.

## Gemini key (voice routing)

- Normal path: the key lives on the **bridge**, not the headset. Open `http://<mac-ip>:8137/setup` on a phone or PC and paste it (the headset's Settings panel shows this URL; the bridge also logs it at startup while no key is set). Saved to `~/.config/lenscode-bridge/gemini.json` (0600); a `GEMINI_API_KEY` env var also works, and a page-saved key wins over it.
- The headset calls Gemini through the bridge's `/gemini/*` proxy, which swaps in the real key. Settings turns green by itself via the `gemini_key_changed` event.
- Key precedence on the headset (`resolveGeminiKeySource`): adb-pushed `lenscode-config.json` > bridge key > build-time `-PgeminiApiKey`.
- Dev mode (default): `/setup` and the proxy are open to the LAN, like the WebSocket. Prod mode: `/setup` needs the 6-digit code the bridge prints, and the proxy needs a paired device's token.
- Non-obvious: Koog's base URL must end in `/` (`.../gemini/`). Ktor resolves its relative `v1beta/...` path like a browser, so without the slash it drops `/gemini` and the bridge 404s.

## Themes and window look

- Themes live in `ui/Theme.kt` (`ThemeId`, palettes, corner radii); the picker is Settings → App theme. **Terminal** (default) is square corners, thin outlines, flat translucent brown fills and monospace type. Small corners must go through `lensShape(dp)`, never `RoundedCornerShape(dp)` directly, or a sharp theme gets stray rounded corners.
- The app runs in Home Space (`FULL_SPACE_ENABLED = false` in `MainActivity.kt`; don't switch it on without asking). A Home Space window is opaque — transparent pixels render black — and its outer rounded frame is drawn by the XR system. Setting `scene.mainPanelEntity.cornerRadius` is accepted and reads back, but the frame does not change (tried 2026-10-20), and the Android XR docs pages for SceneCore/Compose/design say nothing about it. So panels sit on a themed backdrop gradient (`windowBg`/`windowBgAlt`) instead of the room; real see-through needs `SpatialPanel`s in Full Space.
- Headset screenshots: `adb -s <dev> exec-out screencap -p` prints a "multiple displays" warning before the PNG — strip everything before the `\x89PNG` signature. A 3840x1200 all-black capture means the headset is asleep (`dumpsys power | grep mWakefulness`), not that the app is broken.

## Follow Me (non-obvious)

- `FollowingSubspace` + `FollowTarget.ArDevice` must use `FollowBehavior.Soft()`. `ArDevice` is not compatible with `Tight` (nothing renders). The layout is pushed ~0.6 m in front of the head (`followMeDistanceM`).
- Needs the `HEAD_TRACKING` permission and `DeviceTrackingMode.SPATIAL`.
- Debug builds: `adb shell am broadcast -a com.lenscode.app.DEBUG_FOLLOW_ME --ez on true --ef m 0.6` toggles it and sets the distance live.

## Website (`website/`)

- Dev server: `npm run dev` (http://localhost:3000, or 3001 if 3000 is busy).
- Deploy to Cloudflare (static): `website/scripts/deploy-cloudflare.sh` (`--domain <host>`, `--preview`, `--dry-run`, `--build-only`; see `--help`). Needs `npx wrangler login` or `CLOUDFLARE_API_TOKEN`.
- The workers.dev URL ends in the account subdomain; use a custom domain to hide it.
- `tests/rendered-html.test.mjs` still asserts the pre-pitch copy and fails until updated.
