import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";
import { TMUX_LIST_COMMAND, parseTmuxSessions, runLocalTmuxList } from "../src/tmux.ts";

// ── parsing ──────────────────────────────────────────────────────────────

test("parses one session per line with its windows and attached state", () => {
  const sessions = parseTmuxSessions("lc-ab12|1|1\nweb|3|0\n");

  assert.deepEqual(sessions, [
    { name: "lc-ab12", windows: 1, attached: true },
    { name: "web", windows: 3, attached: false },
  ]);
});

test("a count of attached clients above one still reads as attached", () => {
  assert.equal(parseTmuxSessions("lc-ab12|1|3")[0].attached, true);
});

test("no tmux server running gives no sessions, not an error", () => {
  assert.deepEqual(parseTmuxSessions("no server running on /private/tmp/tmux-501/default\n"), []);
  assert.deepEqual(parseTmuxSessions("error connecting to /private/tmp/tmux-501/default (No such file or directory)"), []);
  assert.deepEqual(parseTmuxSessions(""), []);
});

test("blank and malformed lines are skipped without dropping the good ones", () => {
  const sessions = parseTmuxSessions("\nbroken line\nweb|two|0\n|1|0\nok|2|0\n");

  assert.deepEqual(sessions, [{ name: "ok", windows: 2, attached: false }]);
});

test("the command asks tmux for exactly the fields the parser reads, with homebrew on PATH", () => {
  assert.match(TMUX_LIST_COMMAND, /tmux list-sessions -F '#\{session_name\}\|#\{session_windows\}\|#\{session_attached\}'/);
  assert.match(TMUX_LIST_COMMAND, /\/opt\/homebrew\/bin/);
});

test("listing on this machine always yields text, even without tmux or a server", async () => {
  assert.equal(typeof (await runLocalTmuxList()), "string");
});

// ── the tmux.list request ────────────────────────────────────────────────

const PORT = 18162;
let server: BridgeServer;
let seq = 0;

function rpc(ws: WebSocket, msg: Record<string, unknown>): Promise<any> {
  const id = `t${++seq}`;
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
    ws.send(JSON.stringify({ id, ...msg }));
  });
}

async function connect(): Promise<WebSocket> {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  await rpc(ws, {
    type: "hello",
    protocolVersion: 3,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  return ws;
}

before(async () => {
  server = new BridgeServer(loadConfig({ LENSCODE_PORT: String(PORT) } as NodeJS.ProcessEnv));
  await server.start();
});

after(() => server.close());

test("tmux.list on the bridge's own machine returns a sessions array", async () => {
  const ws = await connect();
  const res = await rpc(ws, { type: "tmux.list", machineId: "local" });

  assert.ok(Array.isArray(res.result.sessions));
  ws.close();
});

test("tmux.list refuses a machine that is not in the SSH registry", async () => {
  const ws = await connect();
  const res = await rpc(ws, { type: "tmux.list", machineId: "-oProxyCommand=evil" });

  assert.equal(res.error.code, "not_found");
  ws.close();
});
