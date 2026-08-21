import { test, before } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { WorkspaceFiles } from "../src/files.ts";

let root: string;
let files: WorkspaceFiles;

/** Synthetic repo: 5,000+ files across nested dirs (spec §61 scale floor). */
before(() => {
  root = mkdtempSync(join(tmpdir(), "aura-repo-"));
  const dirs = ["src/services", "src/api/auth", "src/components", "src/models", "tests"];
  for (const d of dirs) mkdirSync(join(root, d), { recursive: true });

  writeFileSync(join(root, "src/services/auth.service.ts"),
    "export async function login(email: string) {\n  return createSession(email);\n}\n");
  writeFileSync(join(root, "src/api/auth/middleware.ts"),
    "export function validateToken(jwt: string) {\n  // JWT validation happens here\n}\n");
  writeFileSync(join(root, "tests/login.spec.ts"), "test('login', () => {});\n");
  writeFileSync(join(root, "package.json"), "{}\n");
  mkdirSync(join(root, "node_modules/somepkg"), { recursive: true });
  writeFileSync(join(root, "node_modules/somepkg/index.js"), "ignored\n");

  // Filler to cross the 5,000-file line.
  for (let d = 0; d < 50; d++) {
    const dir = join(root, "src/gen", `mod${d}`);
    mkdirSync(dir, { recursive: true });
    for (let f = 0; f < 100; f++) {
      writeFileSync(join(dir, `file${f}.ts`), `export const v${f} = ${f};\n`);
    }
  }
  files = new WorkspaceFiles(root);
});

test("listDir is lazy, sorted, and hides ignored dirs", async () => {
  const entries = await files.listDir(".");
  const names = entries.map((e) => e.name);
  assert.ok(names.includes("src"));
  assert.ok(names.includes("tests"));
  assert.ok(!names.includes("node_modules"));
  assert.equal(entries[0].kind, "dir", "dirs sort first");

  const src = await files.listDir("src/services");
  assert.equal(src[0].name, "auth.service.ts");
  assert.equal(src[0].language, "typescript");
});

test("path escapes are refused", async () => {
  await assert.rejects(() => files.listDir("../../"), /outside the workspace/);
  await assert.rejects(() => files.openFile("../../etc/passwd"), /outside the workspace/);
});

test("openFile returns content and metadata", async () => {
  const f = await files.openFile("src/services/auth.service.ts");
  assert.equal(f.language, "typescript");
  assert.ok(f.content.includes("createSession"));
  assert.equal(f.truncated, false);
});

test("exact filename query ranks the file first", async () => {
  const hits = await files.search("auth.service.ts", 20);
  assert.equal(hits[0].path, "src/services/auth.service.ts");
});

test("filename-without-extension query ranks the file first", async () => {
  const hits = await files.search("auth.service", 20);
  assert.equal(hits[0].path, "src/services/auth.service.ts");
});

test("text query falls through to content search", async () => {
  const hits = await files.search("JWT validation", 20);
  const hit = hits.find((h) => h.path.endsWith("middleware.ts"));
  assert.ok(hit, "content match found via ripgrep");
  assert.equal(hit!.kind, "text");
});

test("search over 5,000+ files meets the latency target", async () => {
  await files.search("warmup", 5); // build the file-list cache first

  const started = performance.now();
  const hits = await files.search("login", 20);
  const tookMs = performance.now() - started;

  assert.ok(hits.some((h) => h.path.includes("login")), "login files found");
  // Spec §60: indexed search <500 ms P95. Generous single-shot bound here;
  // the real P95 harness lands with the M6 index.
  assert.ok(tookMs < 500, `search took ${tookMs.toFixed(0)}ms (target <500ms)`);
});
