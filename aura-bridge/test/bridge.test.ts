import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { isHerdrMethodAllowed } from "../src/allowlist.ts";
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

test("allowlist blocks server control and permits pane ops", () => {
  assert.equal(isHerdrMethodAllowed("server.stop"), false);
  assert.equal(isHerdrMethodAllowed("plugin.action.invoke"), false);
  assert.equal(isHerdrMethodAllowed("integration.install"), false);
  assert.equal(isHerdrMethodAllowed("pane.send_keys"), true);
  assert.equal(isHerdrMethodAllowed("agent.list"), true);
  assert.equal(isHerdrMethodAllowed("session.snapshot"), true);
});

test("hello round-trip reports protocol and herdr state", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const res = await rpc(ws, {
    id: "t1",
    type: "hello",
    protocolVersion: 1,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  assert.equal(res.result.protocolVersion, 1);
  assert.equal(typeof res.result.herdrConnected, "boolean");
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

test("herdr proxy rejects blocked method with unauthorized", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const res = await rpc(ws, {
    id: "t3",
    type: "herdr.request",
    request: { id: "1", method: "server.stop", params: {} },
  });
  assert.equal(res.error.code, "unauthorized");
  ws.close();
});

// The following tests require a live local Herdr server; they self-skip otherwise.
test("snapshot mirrors live Herdr session", async (t) => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const hello = await rpc(ws, {
    id: "t4a",
    type: "hello",
    protocolVersion: 1,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  if (!hello.result.herdrConnected) {
    ws.close();
    return t.skip("herdr not running");
  }
  const res = await rpc(ws, { id: "t4", type: "snapshot.request" });
  assert.ok(res.result.herdr, "snapshot payload present");
  assert.equal(res.result.machines[0].kind, "local");
  ws.close();
});

test("herdr proxy forwards agent.list verbatim", async (t) => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const hello = await rpc(ws, {
    id: "t5a",
    type: "hello",
    protocolVersion: 1,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  if (!hello.result.herdrConnected) {
    ws.close();
    return t.skip("herdr not running");
  }
  const res = await rpc(ws, {
    id: "t5",
    type: "herdr.request",
    request: { id: "77", method: "agent.list", params: {} },
  });
  assert.equal(res.result.response.id, "77");
  assert.ok("result" in res.result.response || "error" in res.result.response);
  ws.close();
});
