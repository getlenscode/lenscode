#!/usr/bin/env bash
# Types text into Aura's voice-command review popup as if it had been
# recognized from speech — for testing without talking out loud (e.g. in a
# public place). Requires the debug-only INJECT_VOICE_COMMAND broadcast
# receiver in MainActivity.kt (registered only when BuildConfig.DEBUG),
# which lands the text in exactly the same review card real speech does —
# you still tap Submit/Cancel on-screen. Nothing is auto-dispatched.
#
# Usage:
#   scripts/voice-inject.sh "open diff"
#   scripts/voice-inject.sh -s emulator-5554 "navigate to code and open line 20 in package.json"
#
# Targets the aura_xr_tablet AVD by default (the only correctly-configured
# profile — see docs/paseo-migration-plan.md; the other AVD, "aura_xr", is a
# broken portrait stub and should not be used). Boots it if it isn't running.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD_NAME="aura_xr_tablet"
SERIAL=""

usage() {
  echo "Usage: $0 [-s <device-serial>] \"text to inject\"" >&2
  exit 1
}

while getopts ":s:" opt; do
  case "$opt" in
    s) SERIAL="$OPTARG" ;;
    *) usage ;;
  esac
done
shift $((OPTIND - 1))

TEXT="${1:-}"
[ -z "$TEXT" ] && usage

if [ -z "$SERIAL" ]; then
  # Prefer an already-running emulator over booting a new one.
  SERIAL=$("$ADB" devices | awk '/^emulator-/{print $1; exit}')
fi

if [ -z "$SERIAL" ]; then
  echo "No emulator running — booting $AVD_NAME…" >&2
  nohup "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-load > /tmp/aura-emulator-inject.log 2>&1 &
  "$ADB" wait-for-device
  SERIAL=$("$ADB" devices | awk '/^emulator-/{print $1; exit}')
  echo "Waiting for boot to finish…" >&2
  until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
  done
fi

echo "Target: $SERIAL" >&2

# The text has to survive two shell layers (this script's shell, then the
# device's own /system/bin/sh once adb forwards the command) — quoting
# survives that trip unreliably (confirmed live: both `"text with spaces"`
# and `'text with spaces'` got silently mangled or dropped mid-flight), but
# a backslash-escaped space does. So: escape spaces, don't quote the value.
ESCAPED_TEXT="${TEXT// /\\ }"

# shellcheck disable=SC2086
"$ADB" -s "$SERIAL" shell am broadcast -a dev.aura.xr.INJECT_VOICE_COMMAND --es text $ESCAPED_TEXT

echo "Sent: \"$TEXT\" — check the headset/emulator screen for the review popup." >&2
