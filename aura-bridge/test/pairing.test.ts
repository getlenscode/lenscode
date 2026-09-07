import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";

const PORT = 18138;
let server: BridgeServer;

function rpc(ws: WebSocket, msg: object): Promise<any> {
  const id = (msg as { id: string }).id;
  return new Promise((resolve, reject) => {
    const onMessage = (raw: WebSocket.RawData) => {
      const parsed = JSON.parse(raw.toString());
      if (parsed.id === id) {
        ws.off("message", onMessage);
        resolve(parsed);
      }
    };
    ws.on("message", onMessage);
    ws.once("error", reject);
    ws.send(JSON.stringify(msg));
  });
}

async function connect(): Promise<WebSocket> {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  return ws;
}

const hello = (deviceToken?: string) => ({
  id: "h1",
  type: "hello",
  protocolVersion: 3,
  deviceId: "xr-glasses-1",
  ...(deviceToken ? { deviceToken } : {}),
  clientInfo: { app: "test", version: "0", platform: "node" },
});

before(async () => {
  const store = join(mkdtempSync(join(tmpdir(), "aura-pair-")), "devices.json");
  server = new BridgeServer(
    loadConfig({
      AURA_PORT: String(PORT),
      AURA_MODE: "prod",
      AURA_INSECURE_PROD: "1",
      AURA_PAIRING_STORE: store,
    } as NodeJS.ProcessEnv),
  );
  await server.start();
});

after(() => server.close());

test("unpaired device: hello unauthenticated, requests rejected", async () => {
  const ws = await connect();
  const res = await rpc(ws, hello());
  assert.equal(res.result.authenticated, false);

  const snap = await rpc(ws, { id: "s1", type: "snapshot.request" });
  assert.equal(snap.error.code, "unauthorized");
  ws.close();
});

test("full pairing flow: begin, wrong code, right code, authorized", async () => {
  const ws = await connect();
  const begin = await rpc(ws, {
    id: "p1",
    type: "pair.begin",
    deviceId: "xr-glasses-1",
    deviceName: "Test Glasses",
  });
  assert.ok(begin.result.expiresInSeconds > 0);
  assert.equal(begin.result.code, undefined, "code never travels to the device");

  const bad = await rpc(ws, {
    id: "p2",
    type: "pair.complete",
    deviceId: "xr-glasses-1",
    pairingCode: "000000",
  });
  // 1-in-a-million collision with the random code is acceptable in this assert.
  const realCode = server.pairingRegistry.peekPendingCode("xr-glasses-1");
  if (realCode !== "000000") assert.equal(bad.error.code, "pairing_required");

  const good = await rpc(ws, {
    id: "p3",
    type: "pair.complete",
    deviceId: "xr-glasses-1",
    pairingCode: realCode ?? "000000",
  });
  const token = good.result.deviceToken;
  assert.ok(typeof token === "string" && token.length > 20);

  // New connection with the token: authenticated and allowed through.
  const ws2 = await connect();
  const res = await rpc(ws2, hello(token));
  assert.equal(res.result.authenticated, true);
  const sub = await rpc(ws2, { id: "s2", type: "events.subscribe", topics: ["backend"] });
  assert.ok(sub.result);

  // Revocation locks the device out again (spec §55).
  assert.equal(server.pairingRegistry.revoke("xr-glasses-1"), true);
  const ws3 = await connect();
  const res3 = await rpc(ws3, hello(token));
  assert.equal(res3.result.authenticated, false);

  ws.close();
  ws2.close();
  ws3.close();
});
