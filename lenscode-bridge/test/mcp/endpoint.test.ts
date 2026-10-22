import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtemp, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { BridgeServer } from "../../src/server.ts";
import { loadConfig } from "../../src/config.ts";
import {
  readControlFile,
  startControlServer,
  tokenMatches,
  writeControlFile,
} from "../../src/mcp/index.ts";

// ── the token and the listener ───────────────────────────────────────────

test("the token check needs the exact bearer token", () => {
  assert.equal(tokenMatches("Bearer abc", "abc"), true);
  assert.equal(tokenMatches("Bearer abd", "abc"), false);
  assert.equal(tokenMatches("abc", "abc"), false);
  assert.equal(tokenMatches(undefined, "abc"), false);
});

// ── the loopback listener ────────────────────────────────────────────────

async function post(port: number, token: string | null, body: unknown, path = "/control") {
  const res = await fetch(`http://127.0.0.1:${port}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
  return { status: res.status, body: await res.json() };
}

test("the listener checks the token and the body, then hands valid commands to the handler", async () => {
  const seen: unknown[] = [];
  const { server, port, token } = await startControlServer(async (command, args) => {
    seen.push({ command, args });
    return { status: 200, body: { done: command } };
  });
  try {
    assert.equal((await post(port, null, { command: "status" })).status, 401);
    assert.equal((await post(port, "wrong", { command: "status" })).status, 401);
    assert.equal((await post(port, token, "{not json")).status, 400);
    assert.equal((await post(port, token, { command: "nope" })).status, 400);
    assert.equal((await post(port, token, { command: "status" }, "/other")).status, 404);
    assert.deepEqual(await post(port, token, { command: "notify", args: { message: "hi" } }), {
      status: 200,
      body: { done: "notify" },
    });
    assert.deepEqual(seen, [{ command: "notify", args: { message: "hi" } }]);
  } finally {
    server.close();
  }
});

test("the listener is bound to loopback only", async () => {
  const { server } = await startControlServer(async () => ({ status: 200, body: {} }));
  try {
    const address = server.address();
    assert.equal(typeof address === "object" && address?.address, "127.0.0.1");
  } finally {
    server.close();
  }
});

test("the control file round-trips and is readable by its owner only", async () => {
  const path = join(await mkdtemp(join(tmpdir(), "lenscode-control-")), "control.json");
  await writeControlFile({ port: 4321, token: "t0k", pid: 99 }, path);

  assert.deepEqual(await readControlFile(path), { port: 4321, token: "t0k", pid: 99 });
  assert.equal((await stat(path)).mode & 0o777, 0o600);
  assert.equal(await readControlFile(join(tmpdir(), "does-not-exist.json")), null);
});

// ── through the bridge to a headset ──────────────────────────────────────

const PORT = 18163;
let bridge: BridgeServer;

before(async () => {
  bridge = new BridgeServer(loadConfig({ LENSCODE_PORT: String(PORT) } as NodeJS.ProcessEnv));
  await bridge.start();
});

after(() => bridge.close());

async function headset(): Promise<WebSocket> {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  ws.send(
    JSON.stringify({
      id: "h1",
      type: "hello",
      protocolVersion: 3,
      deviceId: "test-headset",
      clientInfo: { app: "test", version: "0", platform: "node" },
    }),
  );
  await new Promise<void>((resolve) => ws.on("message", (raw) => JSON.parse(raw.toString()).id === "h1" && resolve()));
  return ws;
}

test("status reports how many headsets are connected", async () => {
  const { port, token } = bridge.controlEndpoint!;
  const before = await post(port, token, { command: "status" });
  assert.equal(before.status, 200);
  assert.equal(before.body.headsetsConnected, 0);

  const ws = await headset();
  const during = await post(port, token, { command: "status" });
  assert.equal(during.body.headsetsConnected, 1);
  ws.close();
});

test("a headset command with no headset connected says so", async () => {
  const { port, token } = bridge.controlEndpoint!;
  const res = await post(port, token, { command: "open_url", args: { url: "https://x.dev" } });

  assert.equal(res.status, 409);
  assert.match(res.body.error, /No LensCode headset is connected/);
});

test("a headset command reaches the connected headset as a control event", async () => {
  const ws = await headset();
  const got = new Promise<any>((resolve) =>
    ws.on("message", (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.type === "event" && msg.topic === "control") resolve(msg);
    }),
  );
  const { port, token } = bridge.controlEndpoint!;
  const res = await post(port, token, { command: "open_tmux", args: { session: "lc-ab12", machineId: "box" } });

  assert.deepEqual(res, { status: 200, body: { sent: 1 } });
  const event = await got;
  assert.equal(event.event, "open_tmux");
  assert.deepEqual(event.data, { session: "lc-ab12", machineId: "box" });
  ws.close();
});

test("list_tmux refuses a machine that is not in the SSH registry", async () => {
  const { port, token } = bridge.controlEndpoint!;
  const res = await post(port, token, { command: "list_tmux", args: { machineId: "not-a-registered-host-xyz" } });

  assert.equal(res.status, 404);
});

test("open_project with no headset connected says so before touching any machine", async () => {
  const { port, token } = bridge.controlEndpoint!;
  const res = await post(port, token, { command: "open_project", args: { machineId: "not-a-registered-host-xyz", path: "~/x" } });

  assert.equal(res.status, 409);
});

test("open_project refuses a machine that is not in the SSH registry", async () => {
  const ws = await headset();
  const { port, token } = bridge.controlEndpoint!;
  const res = await post(port, token, { command: "open_project", args: { machineId: "not-a-registered-host-xyz", path: "~/x" } });

  assert.equal(res.status, 404);
  assert.match(res.body.error, /SSH machine registry/);
  ws.close();
});
