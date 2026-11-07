# Android XR app: build, install, release

How to build the Android XR client, put it on the headset, and make a Play Store build. Trust and TLS rules for the bridge connection are in [tls-and-bridge-trust.md](tls-and-bridge-trust.md).

## Build, install, run

```bash
cd client-android
./gradlew :app:assembleDebug -PbridgeUrl=ws://<mac-ip>:8137
adb -s <device-ip>:<port> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-ip>:<port> shell am force-stop com.lenscode.app
adb -s <device-ip>:<port> shell monkey -p com.lenscode.app -c android.intent.category.LAUNCHER 1
```

- My real addresses are in `CLAUDE.local.md` (gitignored, personal; imported by `CLAUDE.md`).
- Replace `<mac-ip>` with the Mac's LAN IP and `<device-ip>:<port>` with the XR device from `adb devices`. Both change: the LAN IP with the network, the wireless-debugging port on every restart.
- `bridgeUrl` is the Mac's LAN IP (a literal IP; `adb reverse` can't help). It is only the first-run default: the address saved in the app (Settings → Bridge connection, typed or dictated) wins, so a changed IP needs no rebuild.
- Reinstalling resets the Follow Me toggle to off.

## Release build (Play Store)

- Play takes an **AAB**: `cd client-android && ./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`. (`:app:assembleRelease` gives an APK for side-loading.)
- Signing reads `client-android/keystore.properties` (gitignored, as are `*.jks`): `storeFile=` (relative to `client-android/`), `storePassword=`, `keyAlias=`, `keyPassword=`. Without that file the build still works but is **unsigned**, and Play/adb reject it.
- Create the upload key once and back it up (losing it means a support request to Google): `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`. Enroll in Play App Signing so Google holds the real app-signing key.
- Bump `versionCode` in `app/build.gradle.kts` for every upload (Play rejects a repeat); `versionName` is the semver shown to people (currently `0.1.0-alpha.N`).
- The bridge address is set on the headset (Settings → Bridge connection; the Speak button takes a dictated IP), so none is baked into the release.
