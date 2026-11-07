import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer, type Server } from "node:http";
import { mkdtempSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AddressInfo } from "node:net";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";

const PORT = 18145;
const GOOD_KEY = "aai-good-key";
const base = `http://127.0.0.1:${PORT}`;

let assembly: Server;
let assemblyUrl: string;
const seenAuth: (string | undefined)[] = [];
const seenPaths: string[] = [];
let bridge: BridgeServer;
let store: string;

const tmpFile = (name: string) => join(mkdtempSync(join(tmpdir(), "lenscode-aai-")), name);
const form = (fields: Record<string, string>) => ({
  method: "POST",
  headers: { "content-type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams(fields).toString(),
});
const aaiForm = (fields: Record<string, string>) => form({ provider: "assemblyai", ...fields });

before(async () => {
  // Stands in for streaming.assemblyai.com's token endpoint.
  assembly = createServer((req, res) => {
    seenAuth.push(req.headers.authorization);
    seenPaths.push(req.url ?? "");
    if (req.headers.authorization !== GOOD_KEY) {
      res.writeHead(401, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "Unauthorized" }));
      return;
    }
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ token: "temp-token-123", expires_in_seconds: 60 }));
  });
  assembly.listen(0, "127.0.0.1");
  await once(assembly, "listening");
  assemblyUrl = `http://127.0.0.1:${(assembly.address() as AddressInfo).port}`;

  store = tmpFile("assemblyai.json");
  bridge = new BridgeServer(
    loadConfig({
      LENSCODE_PORT: String(PORT),
      LENSCODE_PAIRING_STORE: tmpFile("devices.json"),
      LENSCODE_GEMINI_STORE: tmpFile("gemini.json"),
      LENSCODE_ASSEMBLYAI_STORE: store,
      LENSCODE_ASSEMBLYAI_UPSTREAM: assemblyUrl,
    } as NodeJS.ProcessEnv),
  );
  await bridge.start();
});

after(() => {
  bridge.close();
  assembly.close();
});

test("without a key the token route answers 503 and the page says so", async () => {
  const res = await fetch(`${base}/assemblyai/token`);
  assert.equal(res.status, 503);
  assert.match(((await res.json()) as any).error, /\/setup/);
  assert.match(await (await fetch(`${base}/setup`)).text(), /show an error until a key is added/);
});

test("a rejected key is not saved", async () => {
  const res = await fetch(`${base}/setup`, aaiForm({ apiKey: "wrong" }));
  assert.equal(res.status, 400);
  assert.match(await res.text(), /AssemblyAI rejected that key/);
  assert.equal(await fetch(`${base}/assemblyai/token`).then((r) => r.status), 503);
});

test("a good key is saved 0600, and the token route returns a temporary token, never the key", async () => {
  const res = await fetch(`${base}/setup`, aaiForm({ apiKey: GOOD_KEY }));
  assert.equal(res.status, 200);
  assert.match(await res.text(), /AssemblyAI key saved/);
  assert.equal(statSync(store).mode & 0o777, 0o600);
  assert.equal(JSON.parse(readFileSync(store, "utf8")).apiKey, GOOD_KEY);

  const tok = await fetch(`${base}/assemblyai/token`);
  assert.equal(tok.status, 200);
  const raw = await tok.text();
  assert.ok(!raw.includes(GOOD_KEY), "the permanent key must not reach the headset");
  const body = JSON.parse(raw);
  assert.equal(body.token, "temp-token-123");
  assert.equal(body.model, "universal-streaming-english");
  assert.equal(body.url, `${assemblyUrl.replace("http", "ws")}/v3/ws`);
  // The upstream call carried the key in the header, not the URL.
  assert.equal(seenAuth.at(-1), GOOD_KEY);
  assert.match(seenPaths.at(-1)!, /^\/v3\/token\?expires_in_seconds=\d+$/);
});

test("a chosen model is reported with the token", async () => {
  await fetch(`${base}/setup`, aaiForm({ apiKey: GOOD_KEY, model: "universal-streaming-multilingual" }));
  const body = (await (await fetch(`${base}/assemblyai/token`)).json()) as any;
  assert.equal(body.model, "universal-streaming-multilingual");
  assert.equal(bridge.assemblyStatus().model, "universal-streaming-multilingual");
});

test("a model name with odd characters is refused", async () => {
  const res = await fetch(`${base}/setup`, aaiForm({ apiKey: GOOD_KEY, model: "bad model;" }));
  assert.equal(res.status, 400);
});

test("the Gemini form is unaffected by the AssemblyAI one", async () => {
  const res = await fetch(`${base}/setup`, form({ apiKey: "" }));
  assert.equal(res.status, 400);
  assert.match(await res.text(), /Gemini API key/);
});

test("removing the key turns the route off again", async () => {
  const res = await fetch(`${base}/setup`, aaiForm({ action: "clear" }));
  assert.equal(res.status, 200);
  assert.equal(await fetch(`${base}/assemblyai/token`).then((r) => r.status), 503);
});

test("cross-site form posts are refused", async () => {
  const res = await fetch(`${base}/setup`, {
    ...aaiForm({ apiKey: GOOD_KEY }),
    headers: { "content-type": "application/x-www-form-urlencoded", origin: "http://evil.example" },
  });
  assert.equal(res.status, 403);
});
