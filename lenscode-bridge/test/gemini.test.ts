import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer, type IncomingMessage, type Server } from "node:http";
import { mkdtempSync, readFileSync, statSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AddressInfo } from "node:net";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";

const DEV_PORT = 18143;
const PROD_PORT = 18144;
const GOOD_KEY = "AIzaGoodKey123";

interface Seen {
  method?: string;
  url?: string;
  apiKeyHeader?: string;
  body: string;
}
let google: Server;
let googleUrl: string;
const seen: Seen[] = [];
let dev: BridgeServer;
let prod: BridgeServer;
let devStore: string;

const bodyOf = (req: IncomingMessage) =>
  new Promise<string>((resolve) => {
    let s = "";
    req.on("data", (c) => (s += c));
    req.on("end", () => resolve(s));
  });

function tmpFile(name: string): string {
  return join(mkdtempSync(join(tmpdir(), "lenscode-gemini-")), name);
}

before(async () => {
  // Stands in for generativelanguage.googleapis.com.
  google = createServer(async (req, res) => {
    const body = await bodyOf(req);
    const apiKeyHeader = req.headers["x-goog-api-key"] as string | undefined;
    seen.push({ method: req.method, url: req.url, apiKeyHeader, body });
    if (apiKeyHeader !== GOOD_KEY) {
      res.writeHead(400, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: { status: "INVALID_ARGUMENT", message: "API key not valid" } }));
      return;
    }
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ echoed: body, path: req.url }));
  });
  google.listen(0, "127.0.0.1");
  await once(google, "listening");
  googleUrl = `http://127.0.0.1:${(google.address() as AddressInfo).port}`;

  devStore = tmpFile("gemini.json");
  dev = new BridgeServer(
    loadConfig({
      LENSCODE_PORT: String(DEV_PORT),
      LENSCODE_PAIRING_STORE: tmpFile("devices.json"),
      LENSCODE_GEMINI_STORE: devStore,
      LENSCODE_GEMINI_UPSTREAM: googleUrl,
    } as NodeJS.ProcessEnv),
  );
  await dev.start();

  prod = new BridgeServer(
    loadConfig({
      LENSCODE_PORT: String(PROD_PORT),
      LENSCODE_MODE: "prod",
      LENSCODE_INSECURE_PROD: "1",
      LENSCODE_PAIRING_STORE: tmpFile("devices.json"),
      LENSCODE_GEMINI_STORE: tmpFile("gemini.json"),
      LENSCODE_GEMINI_UPSTREAM: googleUrl,
    } as NodeJS.ProcessEnv),
  );
  await prod.start();
});

after(() => {
  dev.close();
  prod.close();
  google.close();
});

const form = (fields: Record<string, string>) => ({
  method: "POST",
  headers: { "content-type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams(fields).toString(),
});

const helloMsg = (id: string) => ({
  id,
  type: "hello",
  protocolVersion: 3,
  deviceId: "xr-glasses-1",
  clientInfo: { app: "test", version: "0", platform: "node" },
});

function nextMessage(ws: WebSocket, match: (m: any) => boolean): Promise<any> {
  return new Promise((resolve) => {
    const onMsg = (raw: WebSocket.RawData) => {
      const m = JSON.parse(raw.toString());
      if (match(m)) {
        ws.off("message", onMsg);
        resolve(m);
      }
    };
    ws.on("message", onMsg);
  });
}

test("before a key is set: setup page says so, proxy answers 503, hello reports unconfigured", async () => {
  const page = await fetch(`http://127.0.0.1:${DEV_PORT}/setup`);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /Not set/);

  const proxied = await fetch(`http://127.0.0.1:${DEV_PORT}/gemini/v1beta/models/m:generateContent`, {
    method: "POST",
    body: "{}",
  });
  assert.equal(proxied.status, 503);
  assert.match(((await proxied.json()) as any).error.message, /\/setup/);

  const ws = new WebSocket(`ws://127.0.0.1:${DEV_PORT}`);
  await once(ws, "open");
  const reply = nextMessage(ws, (m) => m.id === "h0");
  ws.send(JSON.stringify(helloMsg("h0")));
  assert.deepEqual((await reply).result.gemini, { configured: false, source: null, model: null });
  ws.close();
});

test("a rejected key is not saved", async () => {
  const res = await fetch(`http://127.0.0.1:${DEV_PORT}/setup`, form({ apiKey: "AIzaWrong" }));
  assert.equal(res.status, 400);
  assert.match(await res.text(), /Google rejected that key/);
  assert.equal(dev.gemini.status().configured, false);
  assert.equal(existsSync(devStore), false);
});

test("saving a good key persists it 0600, never echoes it, and pushes an event to connected headsets", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${DEV_PORT}`);
  await once(ws, "open");
  const event = nextMessage(ws, (m) => m.type === "event" && m.event === "gemini_key_changed");

  const res = await fetch(
    `http://127.0.0.1:${DEV_PORT}/setup`,
    form({ apiKey: ` ${GOOD_KEY} `, model: "gemini-test-model" }),
  );
  const html = await res.text();
  assert.equal(res.status, 200);
  assert.match(html, /Key saved/);
  assert.ok(!html.includes(GOOD_KEY), "key is never echoed back into the page");

  assert.deepEqual((await event).data, { configured: true, source: "file", model: "gemini-test-model" });
  assert.equal(JSON.parse(readFileSync(devStore, "utf8")).apiKey, GOOD_KEY, "whitespace trimmed");
  assert.equal(statSync(devStore).mode & 0o777, 0o600);

  const reply = nextMessage(ws, (m) => m.id === "h1");
  ws.send(JSON.stringify(helloMsg("h1")));
  assert.equal((await reply).result.gemini.configured, true);
  ws.close();
});

test("proxy swaps the placeholder key for the real one and forwards path + body", async () => {
  seen.length = 0;
  const res = await fetch(
    `http://127.0.0.1:${DEV_PORT}/gemini/v1beta/models/gemini-x:generateContent?key=placeholder&alt=json`,
    { method: "POST", headers: { "content-type": "application/json" }, body: '{"hi":1}' },
  );
  assert.equal(res.status, 200);
  const json = (await res.json()) as any;
  assert.equal(json.echoed, '{"hi":1}');

  const upstream = seen.at(-1)!;
  assert.equal(upstream.apiKeyHeader, GOOD_KEY);
  assert.ok(!upstream.url!.includes("placeholder"), "client's own key param is dropped");
  assert.equal(upstream.url, "/v1beta/models/gemini-x:generateContent?alt=json");
});

test("proxy refuses anything outside /v1beta/", async () => {
  const res = await fetch(`http://127.0.0.1:${DEV_PORT}/gemini/v1/tunedModels`, { method: "POST", body: "{}" });
  assert.equal(res.status, 404);
});

test("setup refuses a cross-site form post", async () => {
  const res = await fetch(`http://127.0.0.1:${DEV_PORT}/setup`, {
    ...form({ apiKey: GOOD_KEY }),
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      origin: "http://evil.example",
    },
  });
  assert.equal(res.status, 403);
});

test("setup accepts the form post a real browser sends (same-origin Origin header)", async () => {
  const page = await fetch(`http://127.0.0.1:${DEV_PORT}/setup`);
  assert.equal(page.headers.get("referrer-policy"), "same-origin", "no-referrer would make browsers send Origin: null");
  const res = await fetch(`http://127.0.0.1:${DEV_PORT}/setup`, {
    ...form({ apiKey: "AIzaWrong" }),
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      origin: `http://127.0.0.1:${DEV_PORT}`,
    },
  });
  assert.equal(res.status, 400, "reaches the key check instead of being refused as cross-site");
  const nullOrigin = await fetch(`http://127.0.0.1:${DEV_PORT}/setup`, {
    ...form({ apiKey: GOOD_KEY }),
    headers: { "content-type": "application/x-www-form-urlencoded", origin: "null" },
  });
  assert.equal(nullOrigin.status, 403);
});

test("removing the saved key turns routing back off", async () => {
  const res = await fetch(`http://127.0.0.1:${DEV_PORT}/setup`, form({ action: "clear" }));
  assert.equal(res.status, 200);
  assert.equal(dev.gemini.status().configured, false);
  assert.equal(existsSync(devStore), false);
});

test("prod: setup needs the console code, and a wrong code is refused", async () => {
  const code = prod.gemini.http.peekSetupCode();
  assert.match(code ?? "", /^\d{6}$/);

  const wrong = await fetch(
    `http://127.0.0.1:${PROD_PORT}/setup`,
    form({ apiKey: GOOD_KEY, code: code === "000000" ? "111111" : "000000" }),
  );
  assert.equal(wrong.status, 403);
  assert.equal(prod.gemini.status().configured, false);

  const right = await fetch(`http://127.0.0.1:${PROD_PORT}/setup`, form({ apiKey: GOOD_KEY, code: code! }));
  assert.equal(right.status, 200);
  assert.equal(prod.gemini.status().configured, true);
  assert.notEqual(prod.gemini.http.peekSetupCode(), code, "code rotates after a successful save");
});

test("prod: setup code rotates after repeated wrong guesses", async () => {
  const before = prod.gemini.http.peekSetupCode();
  for (let i = 0; i < 5; i++) {
    await fetch(`http://127.0.0.1:${PROD_PORT}/setup`, form({ apiKey: GOOD_KEY, code: "abcdef" }));
  }
  assert.notEqual(prod.gemini.http.peekSetupCode(), before);
});

test("prod: proxy needs a paired device's token", async () => {
  const url = `http://127.0.0.1:${PROD_PORT}/gemini/v1beta/models/m:generateContent`;
  const anon = await fetch(url, { method: "POST", body: "{}" });
  assert.equal(anon.status, 401);

  // Pair a device the same way the headset does.
  const ws = new WebSocket(`ws://127.0.0.1:${PROD_PORT}`);
  await once(ws, "open");
  const rpc = (msg: any) => {
    const p = nextMessage(ws, (m) => m.id === msg.id);
    ws.send(JSON.stringify(msg));
    return p;
  };
  await rpc({ id: "b", type: "pair.begin", deviceId: "xr-glasses-1", deviceName: "Glasses" });
  const done = await rpc({
    id: "c",
    type: "pair.complete",
    deviceId: "xr-glasses-1",
    pairingCode: prod.pairingRegistry.peekPendingCode("xr-glasses-1")!,
  });
  const token = done.result.deviceToken as string;
  ws.close();

  const paired = await fetch(url, {
    method: "POST",
    body: "{}",
    headers: { "x-lenscode-device": "xr-glasses-1", "x-lenscode-token": token },
  });
  assert.equal(paired.status, 200);

  const forged = await fetch(url, {
    method: "POST",
    body: "{}",
    headers: { "x-lenscode-device": "xr-glasses-1", "x-lenscode-token": "nope" },
  });
  assert.equal(forged.status, 401);
});
