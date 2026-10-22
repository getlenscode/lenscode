import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { TOOLS, handleMcpMessage, type BridgeCall } from "../../src/mcp/index.ts";

function recorder(result: { ok: boolean; body: unknown } = { ok: true, body: { sent: 1 } }) {
  const calls: Array<{ command: string; args: Record<string, unknown> }> = [];
  const call: BridgeCall = async (command, args) => {
    calls.push({ command, args });
    return result;
  };
  return { calls, call };
}

test("initialize answers with the client's protocol version and a tools capability", async () => {
  const { call } = recorder();
  const res: any = await handleMcpMessage(
    { jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-03-26" } },
    call,
    "9.9.9",
  );

  assert.equal(res.id, 1);
  assert.equal(res.result.protocolVersion, "2025-03-26");
  assert.deepEqual(res.result.capabilities, { tools: {} });
  assert.deepEqual(res.result.serverInfo, { name: "lenscode", version: "9.9.9" });
});

test("tools/list offers every tool with a name, description and object schema", async () => {
  const { call } = recorder();
  const res: any = await handleMcpMessage({ jsonrpc: "2.0", id: 2, method: "tools/list" }, call, "0");
  const names = res.result.tools.map((t: { name: string }) => t.name);

  assert.deepEqual(names, TOOLS.map((t) => t.name));
  for (const name of ["lenscode_open_url", "lenscode_open_tmux_session", "lenscode_list_tmux_sessions", "lenscode_status"]) {
    assert.ok(names.includes(name), name);
  }
  for (const t of res.result.tools) {
    assert.ok(t.description.length > 10, t.name);
    assert.equal(t.inputSchema.type, "object", t.name);
  }
});

test("a tool call is forwarded to the bridge as its control command", async () => {
  const { calls, call } = recorder();
  const res: any = await handleMcpMessage(
    {
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: { name: "lenscode_open_tmux_session", arguments: { session: "web", machine: "maclaptop" } },
    },
    call,
    "0",
  );

  assert.deepEqual(calls, [{ command: "open_tmux", args: { session: "web", machineId: "maclaptop" } }]);
  assert.equal(res.result.isError, false);
  assert.deepEqual(JSON.parse(res.result.content[0].text), { sent: 1 });
});

test("open_url and notify map their arguments straight through", async () => {
  const { calls, call } = recorder();
  await handleMcpMessage({ id: 4, method: "tools/call", params: { name: "lenscode_open_url", arguments: { url: "https://x.dev" } } }, call, "0");
  await handleMcpMessage({ id: 5, method: "tools/call", params: { name: "lenscode_notify", arguments: { message: "done" } } }, call, "0");

  assert.deepEqual(calls, [
    { command: "open_url", args: { url: "https://x.dev" } },
    { command: "notify", args: { message: "done" } },
  ]);
});

test("a bridge error comes back as a tool error the model can read", async () => {
  const { call } = recorder({ ok: false, body: { error: "No LensCode headset is connected to the bridge." } });
  const res: any = await handleMcpMessage(
    { id: 6, method: "tools/call", params: { name: "lenscode_show_view", arguments: { view: "code" } } },
    call,
    "0",
  );

  assert.equal(res.result.isError, true);
  assert.match(res.result.content[0].text, /No LensCode headset/);
});

test("unknown tools and methods are JSON-RPC errors; notifications get no reply", async () => {
  const { call } = recorder();
  const unknownTool: any = await handleMcpMessage({ id: 7, method: "tools/call", params: { name: "nope" } }, call, "0");
  const unknownMethod: any = await handleMcpMessage({ id: 8, method: "resources/list" }, call, "0");

  assert.equal(unknownTool.error.code, -32602);
  assert.equal(unknownMethod.error.code, -32601);
  assert.equal(await handleMcpMessage({ method: "notifications/initialized" }, call, "0"), null);
  assert.deepEqual(await handleMcpMessage({ id: 9, method: "ping" }, call, "0"), { jsonrpc: "2.0", id: 9, result: {} });
});

test("`lenscode-bridge mcp` speaks MCP over stdio without starting a bridge", async () => {
  const main = fileURLToPath(new URL("../../src/main.ts", import.meta.url));
  const child = spawn(process.execPath, ["--experimental-strip-types", "--no-warnings", main, "mcp"], {
    stdio: ["pipe", "pipe", "pipe"],
  });
  let out = "";
  child.stdout.on("data", (d) => (out += d));
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }) + "\n");
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list" }) + "\n");
  child.stdin.end();
  await new Promise((resolve) => child.on("close", resolve));

  const lines = out.trim().split("\n").map((l) => JSON.parse(l));
  assert.equal(lines.length, 2, "only protocol messages on stdout");
  assert.equal(lines[0].result.serverInfo.name, "lenscode");
  assert.ok(lines[1].result.tools.length >= 8);
});
