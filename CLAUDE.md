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
- `bridgeUrl` is the Mac's LAN IP (a literal IP; `adb reverse` can't help). It changes with the network, so re-check it.
- The wireless-debugging port changes on every restart: run `adb devices` for the current address, and update the values above.
- Reinstalling resets the Follow Me toggle to off.

## Bridge and Paseo

- Paseo daemon: `paseo daemon` (listens on `ws://127.0.0.1:6767/ws`).
- Bridge: `./scripts/setup-single-pc.sh` (port 8137). Config via `LENSCODE_PORT`, `LENSCODE_PASEO_URL`.
- Remote machines are all registered hosts reached over SSH; there is no special "local" machine in the app.

## Follow Me (non-obvious)

- `FollowingSubspace` + `FollowTarget.ArDevice` must use `FollowBehavior.Soft()`. `ArDevice` is not compatible with `Tight` (nothing renders). The layout is pushed ~0.6 m in front of the head (`followMeDistanceM`).
- Needs the `HEAD_TRACKING` permission and `DeviceTrackingMode.SPATIAL`.
- Debug builds: `adb shell am broadcast -a com.lenscode.app.DEBUG_FOLLOW_ME --ez on true --ef m 0.6` toggles it and sets the distance live.

## Website (`website/`)

- Dev server: `npm run dev` (http://localhost:3000, or 3001 if 3000 is busy).
- Deploy to Cloudflare (static): `website/scripts/deploy-cloudflare.sh` (`--domain <host>`, `--preview`, `--dry-run`, `--build-only`; see `--help`). Needs `npx wrangler login` or `CLOUDFLARE_API_TOKEN`.
- The workers.dev URL ends in the account subdomain; use a custom domain to hide it.
- `tests/rendered-html.test.mjs` still asserts the pre-pitch copy and fails until updated.
