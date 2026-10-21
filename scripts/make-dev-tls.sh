#!/usr/bin/env bash
# Creates a local certificate authority and a server certificate for lenscode-bridge,
# so the bridge can run in prod mode (TLS) on your own network and a debug build of
# the app can connect to it over wss://.
#
#   ./scripts/make-dev-tls.sh 192.168.0.10          # one or more IPs and/or host names
#   ./scripts/make-dev-tls.sh --out ./tls my-mac.local 192.168.0.10
#
# Output (default ~/.config/lenscode-bridge/tls/):
#   ca.pem       the CA certificate — install THIS on the headset (see below)
#   ca.key       the CA's private key — keep it here, never copy it anywhere
#   server.pem / server.key   what the bridge serves
#
# Run the bridge with:
#   LENSCODE_MODE=prod LENSCODE_TLS_CERT=<dir>/server.pem LENSCODE_TLS_KEY=<dir>/server.key npm run dev
#
# This is for development. A published app's users need a certificate from a public
# CA (or a tunnel that provides one) because a store build cannot trust your private CA.
# The address must match what the app dials: a certificate for 192.168.0.10 is not
# valid for any other IP, so re-run this when the address changes.

set -euo pipefail

OUT="$HOME/.config/lenscode-bridge/tls"
NAMES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) NAMES+=("$1"); shift ;;
  esac
done
[ ${#NAMES[@]} -gt 0 ] || { echo "give at least one IP or host name (see --help)" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }

mkdir -p "$OUT"
chmod 700 "$OUT"

# Subject alternative names: Android verifies the host it dialed against these, and an
# IP must appear as an IP entry (not DNS) to match.
SAN=""
for n in "${NAMES[@]}"; do
  if [[ "$n" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then SAN+="IP:$n,"; else SAN+="DNS:$n,"; fi
done
SAN="${SAN%,}"

# Reuse an existing CA so the headset only has to trust it once, even if the
# server certificate is reissued for a new address.
if [ ! -f "$OUT/ca.pem" ] || [ ! -f "$OUT/ca.key" ]; then
  openssl genrsa -out "$OUT/ca.key" 2048 2>/dev/null
  openssl req -x509 -new -key "$OUT/ca.key" -sha256 -days 3650 \
    -subj "/CN=LensCode dev CA" -out "$OUT/ca.pem"
  echo "created CA: $OUT/ca.pem"
fi

openssl genrsa -out "$OUT/server.key" 2048 2>/dev/null
openssl req -new -key "$OUT/server.key" -subj "/CN=${NAMES[0]}" -out "$OUT/server.csr"
EXT="$(mktemp)"
printf 'subjectAltName=%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$SAN" > "$EXT"
openssl x509 -req -in "$OUT/server.csr" -CA "$OUT/ca.pem" -CAkey "$OUT/ca.key" -CAcreateserial \
  -days 397 -sha256 -extfile "$EXT" -out "$OUT/server.pem" 2>/dev/null
rm -f "$EXT" "$OUT/server.csr"
chmod 600 "$OUT/ca.key" "$OUT/server.key"

echo "server certificate for: $SAN"
echo "files in $OUT"
