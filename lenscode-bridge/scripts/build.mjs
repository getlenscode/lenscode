// Bundles the bridge into one runnable file, dist/lenscode-bridge.js, so it can be
// published and run with `npx`. @lenscode/protocol is a local workspace package that no
// registry install could resolve, so it is bundled in; the real npm dependencies stay
// external and are installed normally from package.json.
import { build } from "esbuild";
import { chmodSync, mkdirSync, rmSync } from "node:fs";

rmSync("dist", { recursive: true, force: true });
mkdirSync("dist", { recursive: true });

await build({
  entryPoints: ["src/main.ts"],
  outfile: "dist/lenscode-bridge.js",
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node22",
  external: ["ws", "zod", "@getpaseo/*"],
  banner: { js: "#!/usr/bin/env node" },
  logLevel: "info",
});
chmodSync("dist/lenscode-bridge.js", 0o755);
