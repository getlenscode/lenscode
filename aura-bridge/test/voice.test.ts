import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";

const PORT = 18140;
let server: BridgeServer;
let root: string;

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
  root = mkdtempSync(join(tmpdir(), "aura-voice-"));
  mkdirSync(join(root, "src/services"), { recursive: true });
  writeFileSync(join(root, "src/services/auth.service.ts"), "export function login() {}\n");

  server = new BridgeServer(
    loadConfig({ AURA_PORT: String(PORT), AURA_PROJECT_ROOT: root } as NodeJS.ProcessEnv),
  );
  await server.start();
});

after(() => server.close());

test("voice.search over the protocol resolves a confident spoken query", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const res = await rpc(ws, {
    id: "v1",
    type: "voice.search",
    workspaceId: "local",
    query: "auth service",
  });
  assert.equal(res.result.confident, true);
  assert.equal(res.result.resolvedPath, "src/services/auth.service.ts");
  assert.equal(typeof res.result.tookMs, "number");
  ws.close();
});

test("voice.search on gibberish returns no candidates, not an error", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const res = await rpc(ws, {
    id: "v2",
    type: "voice.search",
    workspaceId: "local",
    query: "totallynonexistentquery987",
  });
  assert.equal(res.result.confident, false);
  assert.deepEqual(res.result.candidates, []);
  ws.close();
});
