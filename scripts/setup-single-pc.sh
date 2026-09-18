#!/usr/bin/env bash
# Single-PC setup for Aura Workspace.
#
# Use this when there is only one machine (e.g. "doraemon") and you want it
# to act as the local aura-bridge + Paseo host, with the phone/emulator
# connecting to it directly over the LAN — no ~/.ssh/config, no Remote tab,
# no SSH tunnel. See docs/architecture.md for why the SSH/Remote path exists
# at all and why the phone can't replace it.
#
# What this script does:
#   1. Checks that `paseo` is installed and that its daemon is reachable.
#   2. Installs aura-bridge's dependencies (protocol package + aura-bridge).
#   3. Prints this machine's LAN IP and the exact Android build command to
#      point the app at it.
#   4. Starts aura-bridge in the foreground (npm run dev).
#
# It does not install Paseo itself — installation differs by Paseo version;
# follow your installed version's daemon setup instructions.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE_DIR="$REPO_ROOT/aura-bridge"
PASEO_URL="${AURA_PASEO_URL:-ws://127.0.0.1:6767/ws}"
BRIDGE_PORT="${AURA_PORT:-8137}"

info()  { printf '\033[1;34m[setup]\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m[setup]\033[0m %s\n' "$*"; }
fail()  { printf '\033[1;31m[setup]\033[0m %s\n' "$*" >&2; exit 1; }

# --- 1. Paseo ---------------------------------------------------------------

if ! command -v paseo >/dev/null 2>&1; then
  fail "paseo CLI not found on PATH. Install it first (see your Paseo version's docs), then re-run this script."
fi
info "found paseo CLI: $(command -v paseo)"

PASEO_HOST_PORT="$(printf '%s' "$PASEO_URL" | sed -E 's#^ws://##; s#/ws$##')"
if command -v nc >/dev/null 2>&1 && nc -z "${PASEO_HOST_PORT%%:*}" "${PASEO_HOST_PORT##*:}" 2>/dev/null; then
  info "local Paseo daemon already listening on $PASEO_HOST_PORT"
else
  warn "local Paseo daemon not detected on $PASEO_HOST_PORT."
  warn "Start it first, e.g.:  paseo daemon"
  warn "(continuing — aura-bridge will retry the connection itself)"
fi

# --- 2. Install deps ----------------------------------------------------------

info "installing dependencies (protocol, aura-bridge)..."
( cd "$REPO_ROOT/protocol" && npm install --silent )
( cd "$BRIDGE_DIR" && npm install --silent )

# --- 3. LAN IP + Android build hint -----------------------------------------

LAN_IP="$(
  { ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null; } \
  || hostname -I 2>/dev/null | awk '{print $1}' \
  || echo "<your-lan-ip>"
)"

echo
info "this machine's LAN IP looks like: ${LAN_IP}"
info "build/install the Android app pointed at THIS PC (no bridge computer, no SSH):"
echo
echo "    cd \"$REPO_ROOT/client-android\""
echo "    ./gradlew installDebug -PbridgeUrl=\"ws://${LAN_IP}:${BRIDGE_PORT}\""
echo
info "phone and this PC must be on the same LAN/Wi-Fi for that address to resolve."
echo

# --- 4. Run aura-bridge -------------------------------------------------------

info "starting aura-bridge (AURA_PORT=$BRIDGE_PORT, AURA_PASEO_URL=$PASEO_URL)..."
info "leave this running; Ctrl+C to stop."
cd "$BRIDGE_DIR"
AURA_PORT="$BRIDGE_PORT" AURA_PASEO_URL="$PASEO_URL" npm run dev
