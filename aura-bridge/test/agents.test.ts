import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";

const PORT = 18141;
let server: BridgeServer;
let paseoUp = false;

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

let seq = 0;
const nextId = () => `a${++seq}`;

async function connect(): Promise<WebSocket> {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const hello = await rpc(ws, {
    id: nextId(),
    type: "hello",
    protocolVersion: 3,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  paseoUp = hello.result.backendConnected;
  return ws;
}

before(async () => {
  server = new BridgeServer(loadConfig({ AURA_PORT: String(PORT) } as NodeJS.ProcessEnv));
  await server.start();
});

after(() => server.close());

// These tests require a live local Paseo daemon (ws://127.0.0.1:6767/ws by
// default, or AURA_PASEO_URL); they self-skip otherwise. See
// docs/paseo-migration-plan.md Phase 0 for a manual smoke-test script.

test("agent op against an unreachable backend reports backend_unavailable", async () => {
  const ws = await connect();
  if (paseoUp) {
    ws.close();
    return; // a live daemon happens to be reachable — nothing to assert here
  }
  const res = await rpc(ws, { id: nextId(), type: "agent.list" });
  assert.equal(res.error.code, "backend_unavailable");
  ws.close();
});

test("provider.list reports installed providers", async (t) => {
  const ws = await connect();
  if (!paseoUp) {
    ws.close();
    return t.skip("paseo daemon not running");
  }
  const res = await rpc(ws, { id: nextId(), type: "provider.list" });
  assert.ok(Array.isArray(res.result.providers), "providers array present");
  assert.ok(res.result.providers.length > 0, "at least one provider reported");
  ws.close();
});

test("permission.list returns an array (likely empty — nothing pending)", async (t) => {
  const ws = await connect();
  if (!paseoUp) {
    ws.close();
    return t.skip("paseo daemon not running");
  }
  const res = await rpc(ws, { id: nextId(), type: "permission.list" });
  assert.ok(Array.isArray(res.result.permissions), "permissions array present");
  ws.close();
});

test("worktree.list against this repo's own checkout", async (t) => {
  const ws = await connect();
  if (!paseoUp) {
    ws.close();
    return t.skip("paseo daemon not running");
  }
  // "local" resolves to the bridge's project root when the workspaceId is
  // unknown (see resolveWorkspaceRoot) — the bridge's own cwd is a git repo.
  const res = await rpc(ws, { id: nextId(), type: "worktree.list", workspaceId: "local" });
  assert.ok(Array.isArray(res.result.worktrees), "worktrees array present");
  ws.close();
});

test("agent.create + agent.list + agent.send + agent.archive round-trip", async (t) => {
  const ws = await connect();
  if (!paseoUp) {
    ws.close();
    return t.skip("paseo daemon not running");
  }
  const providers = await rpc(ws, { id: nextId(), type: "provider.list" });
  const available = providers.result.providers.find((p: any) => p.available);
  if (!available) {
    ws.close();
    return t.skip("no available provider on this daemon");
  }

  const created = await rpc(ws, {
    id: nextId(),
    type: "agent.create",
    provider: available.provider,
    cwd: process.env.HOME,
    title: "aura-bridge-agent-test",
  });
  assert.ok(created.result?.agent?.agentId, `create failed: ${JSON.stringify(created)}`);
  const agentId = created.result.agent.agentId;
  assert.equal(created.result.agent.provider, available.provider);

  const list = await rpc(ws, { id: nextId(), type: "agent.list" });
  assert.ok(
    list.result.agents.some((a: any) => a.agentId === agentId),
    "created agent appears in agent.list",
  );

  const sent = await rpc(ws, { id: nextId(), type: "agent.send", agentId, text: "hello" });
  assert.equal(sent.result.sent, true);

  const archived = await rpc(ws, { id: nextId(), type: "agent.archive", agentId });
  assert.equal(archived.result.archived, agentId);
  ws.close();
});
