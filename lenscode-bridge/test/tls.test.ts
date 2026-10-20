// Prod mode over real TLS — the configuration a store build needs, since release
// builds only dial wss://. Certificates come from scripts/make-dev-tls.sh (the same
// script used for on-device testing), so a break there breaks this test too.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { execFileSync, spawnSync } from "node:child_process";
import { createServer, type Server } from "node:http";
import { request as httpsRequest } from "node:https";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import type { AddressInfo } from "node:net";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";

const PORT = 18145;
const haveOpenssl = spawnSync("openssl", ["version"]).status === 0;
const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "scripts", "make-dev-tls.sh");

let server: BridgeServer;
let google: Server;
let ca: Buffer;

function rpc(ws: WebSocket, msg: { id: string }): Promise<any> {
  return new Promise((resolve) => {
    const onMessage = (raw: WebSocket.RawData) => {
      const parsed = JSON.parse(raw.toString());
      if (parsed.id === msg.id) {
        ws.off("message", onMessage);
        resolve(parsed);
      }
    };
    ws.on("message", onMessage);
    ws.send(JSON.stringify(msg));
  });
}

const hello = (id: string, deviceToken?: string) => ({
  id,
  type: "hello",
  protocolVersion: 3,
  deviceId: "xr-glasses-tls",
  ...(deviceToken ? { deviceToken } : {}),
  clientInfo: { app: "test", version: "0", platform: "node" },
});

async function wss(opts: { ca?: Buffer }): Promise<WebSocket> {
  const ws = new WebSocket(`wss://127.0.0.1:${PORT}`, opts.ca ? { ca: opts.ca } : {});
  await once(ws, "open");
  return ws;
}

/** One HTTPS request that trusts our test CA; resolves with status and body. */
function https(path: string, init: { method?: string; body?: string; headers?: Record<string, string> } = {}) {
  return new Promise<{ status: number; body: string }>((resolve, reject) => {
    const req = httpsRequest(
      { host: "127.0.0.1", port: PORT, path, method: init.method ?? "GET", ca, headers: init.headers },
      (res) => {
        let body = "";
        res.on("data", (c) => (body += c));
        res.on("end", () => resolve({ status: res.statusCode ?? 0, body }));
      },
    );
    req.on("error", reject);
    req.end(init.body);
  });
}

before(async () => {
  if (!haveOpenssl) return;
  const dir = mkdtempSync(join(tmpdir(), "lenscode-tls-"));
  execFileSync(SCRIPT, ["--out", dir, "127.0.0.1"], { stdio: "pipe" });
  ca = readFileSync(join(dir, "ca.pem"));

  google = createServer((req, res) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: true, path: req.url }));
  });
  google.listen(0, "127.0.0.1");
  await once(google, "listening");

  server = new BridgeServer(
    loadConfig({
      LENSCODE_PORT: String(PORT),
      LENSCODE_MODE: "prod",
      LENSCODE_TLS_KEY: join(dir, "server.key"),
      LENSCODE_TLS_CERT: join(dir, "server.pem"),
      LENSCODE_PAIRING_STORE: join(dir, "devices.json"),
      LENSCODE_GEMINI_STORE: join(dir, "gemini.json"),
      GEMINI_API_KEY: "AIzaEnvKeyForTls",
      LENSCODE_GEMINI_UPSTREAM: `http://127.0.0.1:${(google.address() as AddressInfo).port}`,
    } as NodeJS.ProcessEnv),
  );
  await server.start();
});

after(() => {
  server?.close();
  google?.close();
});

const opts = { skip: !haveOpenssl && "openssl not installed" };

test("a client that does not trust the CA is refused", opts, async () => {
  // What a store build does with a private/self-signed certificate: it must fail,
  // not silently connect — so users need a certificate from a public CA.
  const ws = new WebSocket(`wss://127.0.0.1:${PORT}`);
  const [err] = await once(ws, "error");
  assert.match(String(err.message), /self.signed|unable to verify|certificate/i);
});

test("wss: unpaired device is locked out, pairing works, token authenticates", opts, async () => {
  const ws = await wss({ ca });
  const first = await rpc(ws, hello("h1"));
  assert.equal(first.result.authenticated, false);
  assert.equal((await rpc(ws, { id: "s1", type: "snapshot.request" } as any)).error.code, "unauthorized");

  await rpc(ws, { id: "p1", type: "pair.begin", deviceId: "xr-glasses-tls", deviceName: "TLS glasses" } as any);
  const code = server.pairingRegistry.peekPendingCode("xr-glasses-tls")!;
  const done = await rpc(ws, { id: "p2", type: "pair.complete", deviceId: "xr-glasses-tls", pairingCode: code } as any);
  const token = done.result.deviceToken as string;
  assert.ok(token.length > 20);
  ws.close();

  const again = await wss({ ca });
  const authed = await rpc(again, hello("h2", token));
  assert.equal(authed.result.authenticated, true);
  assert.equal(authed.result.gemini.configured, true, "env key is reported to paired devices");
  again.close();
});

test("https: the setup page needs the console code, the proxy needs a paired token", opts, async () => {
  const page = await https("/setup");
  assert.equal(page.status, 200);
  assert.match(page.body, /Setup code/);

  const form = (fields: Record<string, string>) => ({
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(fields).toString(),
  });
  const refused = await https("/setup", form({ apiKey: "AIzaWhatever", code: "not-the-code" }));
  assert.equal(refused.status, 403);

  const proxyPath = "/gemini/v1beta/models/m:generateContent";
  assert.equal((await https(proxyPath, { method: "POST", body: "{}" })).status, 401);

  // Pair a second device to get a real token for the proxy call.
  const ws = await wss({ ca });
  await rpc(ws, { id: "p3", type: "pair.begin", deviceId: "proxy-device", deviceName: "Proxy" } as any);
  const done = await rpc(ws, {
    id: "p4",
    type: "pair.complete",
    deviceId: "proxy-device",
    pairingCode: server.pairingRegistry.peekPendingCode("proxy-device")!,
  } as any);
  ws.close();
  const ok = await https(proxyPath, {
    method: "POST",
    body: "{}",
    headers: { "x-lenscode-device": "proxy-device", "x-lenscode-token": done.result.deviceToken },
  });
  assert.equal(ok.status, 200);
  assert.match(ok.body, /generateContent/);
});
