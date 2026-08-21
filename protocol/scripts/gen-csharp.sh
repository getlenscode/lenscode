#!/usr/bin/env bash
# Generates C# DTOs for the Unity client from the emitted JSON Schemas.
# Output lands in client-unity/Assets/Scripts/Protocol/Generated (checked in).
#
# IMPORTANT: XrRequest and XrServerMessage share helper types (Topic, enum
# converters, etc). Generating them in separate quicktype invocations emits
# duplicate non-partial classes in the same namespace, which Unity's compiler
# rejects (CS0111/CS0101) since all .cs files share one assembly. A single
# invocation across both schemas dedupes shared helpers correctly.
set -euo pipefail
cd "$(dirname "$0")/.."

npm run --silent schema

OUT_DIR="../client-unity/Assets/Scripts/Protocol/Generated"
mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/*.cs

npx --yes quicktype \
  --src-lang schema \
  --lang cs \
  --namespace Aura.Protocol \
  --framework NewtonSoft \
  --features attributes-only \
  --out "$OUT_DIR/XrProtocol.cs" \
  schema/XrRequest.schema.json schema/XrServerMessage.schema.json

echo "generated $OUT_DIR/XrProtocol.cs"
