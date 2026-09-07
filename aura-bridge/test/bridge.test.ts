import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";

const PORT = 18137;
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

before(async () => {
  server = new BridgeServer(loadConfig({ AURA_PORT: String(PORT) } as NodeJS.ProcessEnv));
  await server.start();
});

after(() => server.close());

test("hello round-trip reports protocol and backend state", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const res = await rpc(ws, {
    id: "t1",
    type: "hello",
    protocolVersion: 3,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  assert.equal(res.result.protocolVersion, 3);
  assert.equal(typeof res.result.backendConnected, "boolean");
  ws.close();
});

test("malformed request gets structured error", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const res = await rpc(ws, { id: "t2", type: "nonsense" });
  assert.equal(res.error.code, "invalid_request");
  assert.equal(typeof res.error.message, "string");
  ws.close();
});

test("terminal op against an unreachable backend reports backend_unavailable", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const hello = await rpc(ws, {
    id: "t3a",
    type: "hello",
    protocolVersion: 3,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  if (hello.result.backendConnected) {
    ws.close();
    return; // a live daemon happens to be reachable — nothing to assert here
  }
  const res = await rpc(ws, { id: "t3", type: "terminal.list" });
  assert.equal(res.error.code, "backend_unavailable");
  ws.close();
});

// The following tests require a live local Paseo daemon (ws://127.0.0.1:6767/ws
// by default, or AURA_PASEO_URL); they self-skip otherwise. See
// docs/paseo-migration-plan.md Phase 0 for a manual smoke-test script.
test("snapshot mirrors a live Paseo daemon", async (t) => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const hello = await rpc(ws, {
    id: "t4a",
    type: "hello",
    protocolVersion: 3,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  if (!hello.result.backendConnected) {
    ws.close();
    return t.skip("paseo daemon not running");
  }
  const res = await rpc(ws, { id: "t4", type: "snapshot.request" });
  assert.ok(res.result.backend, "snapshot payload present");
  assert.equal(res.result.machines[0].kind, "local");
  ws.close();
});

test("terminal.create + terminal.list round-trip against a live Paseo daemon", async (t) => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const hello = await rpc(ws, {
    id: "t5a",
    type: "hello",
    protocolVersion: 3,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  if (!hello.result.backendConnected) {
    ws.close();
    return t.skip("paseo daemon not running");
  }
  const created = await rpc(ws, {
    id: "t5",
    type: "terminal.create",
    cwd: process.cwd(),
    name: "aura-bridge-test",
  });
  assert.ok(created.result.terminal.terminalId, "terminal created");
  const list = await rpc(ws, { id: "t5b", type: "terminal.list" });
  assert.ok(
    list.result.terminals.some((t: any) => t.terminalId === created.result.terminal.terminalId),
  );
  const closed = await rpc(ws, {
    id: "t5c",
    type: "terminal.close",
    terminalId: created.result.terminal.terminalId,
  });
  assert.equal(closed.result.closed, created.result.terminal.terminalId);
  ws.close();
});
