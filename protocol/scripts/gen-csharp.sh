#!/usr/bin/env bash
# Generates C# DTOs for the Unity client from the emitted JSON Schemas.
# Output lands in client-unity/Assets/Scripts/Protocol/Generated (checked in).
set -euo pipefail
cd "$(dirname "$0")/.."

npm run --silent schema

OUT_DIR="../client-unity/Assets/Scripts/Protocol/Generated"
mkdir -p "$OUT_DIR"

for name in XrRequest XrServerMessage; do
  npx --yes quicktype \
    --src-lang schema \
    --lang cs \
    --namespace Aura.Protocol \
    --framework NewtonSoft \
    --features attributes-only \
    --top-level "$name" \
    --out "$OUT_DIR/$name.cs" \
    "schema/$name.schema.json"
  echo "generated $OUT_DIR/$name.cs"
done
