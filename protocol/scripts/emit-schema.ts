/**
 * Emits the XR protocol as JSON Schema (schema/xr-protocol.v1.json).
 * The JSON Schema is the input for C# DTO generation (scripts/gen-csharp.sh).
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { z } from "zod";
import { PROTOCOL_VERSION, XrRequest, XrServerMessage } from "../src/index.ts";

const dir = join(dirname(fileURLToPath(import.meta.url)), "..", "schema");
mkdirSync(dir, { recursive: true });

// Combined document: reference copy + version pin for both sides.
const combined = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  title: "Aura XR Protocol",
  protocolVersion: PROTOCOL_VERSION,
  schemas: {
    request: z.toJSONSchema(XrRequest),
    serverMessage: z.toJSONSchema(XrServerMessage),
  },
};
writeFileSync(
  join(dir, "xr-protocol.v1.json"),
  JSON.stringify(combined, null, 2) + "\n",
);

// Standalone schemas: codegen inputs (quicktype needs a top-level type each).
for (const [name, schema] of [
  ["XrRequest", z.toJSONSchema(XrRequest)],
  ["XrServerMessage", z.toJSONSchema(XrServerMessage)],
] as const) {
  writeFileSync(
    join(dir, `${name}.schema.json`),
    JSON.stringify({ title: name, ...schema }, null, 2) + "\n",
  );
}
console.log(`wrote schemas to ${dir}`);
