import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";

const PORT = 18139;
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
const nextId = () => `t${++seq}`;

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

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

before(async () => {
  server = new BridgeServer(loadConfig({ LENSCODE_PORT: String(PORT) } as NodeJS.ProcessEnv));
  await server.start();
});

after(() => server.close());

// These tests require a live local Paseo daemon (ws://127.0.0.1:6767/ws by
// default, or LENSCODE_PASEO_URL); they self-skip otherwise. See
// docs/paseo-migration-plan.md Phase 0 for a manual smoke-test script.

test("terminal lifecycle: create, rename, list, read, close", async (t) => {
  const ws = await connect();
  if (!paseoUp) {
    ws.close();
    return t.skip("paseo daemon not running");
  }

  const created = await rpc(ws, {
    id: nextId(),
    type: "terminal.create",
    name: "lenscode-test",
    cwd: process.env.HOME,
  });
  assert.ok(created.result?.terminal?.terminalId, `create failed: ${JSON.stringify(created)}`);
  const terminalId = created.result.terminal.terminalId;
  await sleep(800); // let the shell start before typing into it

  const listed = await rpc(ws, { id: nextId(), type: "terminal.list" });
  const mine = listed.result.terminals.find((x: any) => x.terminalId === terminalId);
  assert.ok(mine, "new terminal appears in list");
  assert.equal(mine.name, "lenscode-test", "rename applied at creation");

  // Run a command and read its output back.
  await rpc(ws, {
    id: nextId(),
    type: "terminal.input",
    terminalId,
    text: "echo lenscode_marker_42\n",
  });
  await sleep(700);
  const read = await rpc(ws, {
    id: nextId(),
    type: "terminal.read",
    terminalId,
    source: "visible",
    format: "text",
  });
  assert.match(read.result.content, /lenscode_marker_42/, "command output readable");

  const closed = await rpc(ws, { id: nextId(), type: "terminal.close", terminalId });
  assert.equal(closed.result.closed, terminalId);
  ws.close();
});

test("Ctrl+C interrupts a running foreground process", async (t) => {
  const ws = await connect();
  if (!paseoUp) {
    ws.close();
    return t.skip("paseo daemon not running");
  }

  const created = await rpc(ws, {
    id: nextId(),
    type: "terminal.create",
    name: "lenscode-interrupt",
    cwd: process.env.HOME,
  });
  const terminalId = created.result.terminal.terminalId;
  await sleep(800); // let the shell start before typing into it

  // Long-running foreground process, then interrupt it from "XR".
  await rpc(ws, {
    id: nextId(),
    type: "terminal.input",
    terminalId,
    // The marker is split so the echoed command line does not itself contain
    // it — only actual execution can produce "NOT_INTERRUPTED".
    text: 'sleep 300 && echo NOT""_INTERRUPTED\n',
  });
  await sleep(800);

  const interrupted = await rpc(ws, {
    id: nextId(),
    type: "terminal.interrupt",
    terminalId,
    confirmed: true,
  });
  assert.equal(interrupted.result.interrupted, terminalId);

  // After SIGINT the shell prompt returns; a new command must execute.
  await sleep(500);
  await rpc(ws, {
    id: nextId(),
    type: "terminal.input",
    terminalId,
    text: "echo back_at_prompt\n",
  });
  await sleep(700);
  const read = await rpc(ws, {
    id: nextId(),
    type: "terminal.read",
    terminalId,
    source: "visible",
    format: "text",
  });
  assert.match(read.result.content, /back_at_prompt/, "shell responsive after Ctrl+C");
  assert.doesNotMatch(read.result.content, /NOT_INTERRUPTED/, "sleep did not complete");

  await rpc(ws, { id: nextId(), type: "terminal.close", terminalId });
  ws.close();
});

test("interrupt without confirmation is rejected by the protocol", async (t) => {
  const ws = await connect();
  const res = await rpc(ws, {
    id: nextId(),
    type: "terminal.interrupt",
    terminalId: "w1:t1",
    confirmed: false,
  });
  assert.equal(res.error.code, "invalid_request", "confirmed:true is mandatory");
  ws.close();
});

test("20 concurrent terminals keep independent state", async (t) => {
  const ws = await connect();
  if (!paseoUp) {
    ws.close();
    return t.skip("paseo daemon not running");
  }

  // Spec §62: 20 terminals, independent state.
  const terminalIds: string[] = [];
  for (let i = 0; i < 20; i++) {
    const created = await rpc(ws, {
      id: nextId(),
      type: "terminal.create",
      name: `lenscode-t${i}`,
      cwd: process.env.HOME,
    });
    assert.ok(created.result?.terminal?.terminalId, `terminal ${i} created`);
    terminalIds.push(created.result.terminal.terminalId);
  }
  await sleep(1000); // let all shells finish starting

  // Give each terminal a distinct marker, then verify no cross-talk.
  for (let i = 0; i < terminalIds.length; i++) {
    await rpc(ws, {
      id: nextId(),
      type: "terminal.input",
      terminalId: terminalIds[i],
      text: `echo marker_${i}\n`,
    });
  }
  await sleep(1500);

  for (let i = 0; i < terminalIds.length; i++) {
    const read = await rpc(ws, {
      id: nextId(),
      type: "terminal.read",
      terminalId: terminalIds[i],
      source: "visible",
      format: "text",
    });
    assert.match(read.result.content, new RegExp(`marker_${i}\\b`), `terminal ${i} has its own output`);
  }

  // Cached terminal switching under the 150 ms P95 target (spec §60). Focus
  // is now bridge-local (no daemon equivalent) so this measures round-trip
  // overhead only, not a Paseo RPC.
  const timings: number[] = [];
  for (const terminalId of terminalIds) {
    const started = performance.now();
    await rpc(ws, { id: nextId(), type: "terminal.focus", terminalId });
    timings.push(performance.now() - started);
  }
  timings.sort((a, b) => a - b);
  const p95 = timings[Math.floor(timings.length * 0.95) - 1] ?? timings.at(-1)!;
  assert.ok(p95 < 150, `focus switch P95 ${p95.toFixed(0)}ms (target <150ms)`);

  for (const terminalId of terminalIds) {
    await rpc(ws, { id: nextId(), type: "terminal.close", terminalId });
  }
  ws.close();
});
