/**
 * `lenscode-bridge mcp` — a Model Context Protocol server (JSON-RPC 2.0 over stdio) that lets
 * Claude Code, Claude Desktop or any MCP client drive the LensCode headset: open a link in the
 * app's browser, show a tmux session, a file or a remote project, switch views, show a message.
 *
 * It does not talk to the headset itself. It forwards each tool call to the running bridge through
 * client.ts, and the bridge relays it to the paired headset. Nothing here writes to stdout except
 * protocol messages, because stdout is the MCP channel.
 */
import { createInterface } from "node:readline";
import { TOOLS } from "./tools.ts";
import { callBridge, type BridgeCall } from "./client.ts";

const DEFAULT_PROTOCOL_VERSION = "2025-06-18";

type JsonRpc = { jsonrpc?: string; id?: string | number | null; method?: string; params?: Record<string, unknown> };

/** Handles one JSON-RPC message; null for notifications, which get no reply. */
export async function handleMcpMessage(msg: JsonRpc, call: BridgeCall, version: string): Promise<object | null> {
  const hasId = msg.id !== undefined && msg.id !== null;
  const reply = (result: unknown) => ({ jsonrpc: "2.0", id: msg.id, result });
  const fail = (code: number, message: string) => ({ jsonrpc: "2.0", id: msg.id ?? null, error: { code, message } });

  switch (msg.method) {
    case "initialize":
      return reply({
        protocolVersion: typeof msg.params?.protocolVersion === "string" ? msg.params.protocolVersion : DEFAULT_PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: { name: "lenscode", version },
        instructions:
          "Controls the LensCode app on the user's Android XR glasses through their lenscode-bridge: open links " +
          "in its browser, show tmux sessions and files, switch views, show messages.",
      });
    case "ping":
      return reply({});
    case "tools/list":
      return reply({ tools: TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })) });
    case "tools/call": {
      const tool = TOOLS.find((t) => t.name === msg.params?.name);
      if (!tool) return fail(-32602, `Unknown tool: ${String(msg.params?.name)}`);
      const input = (typeof msg.params?.arguments === "object" && msg.params.arguments !== null
        ? msg.params.arguments
        : {}) as Record<string, unknown>;
      const { ok, body } = await call(tool.command, tool.args(input));
      const text = typeof body === "string" ? body : JSON.stringify(body, null, 2);
      return reply({ content: [{ type: "text", text }], isError: !ok });
    }
    default:
      return hasId ? fail(-32601, `Method not found: ${String(msg.method)}`) : null;
  }
}

/** Serves MCP over stdin/stdout until stdin closes. */
export async function runMcpServer(version: string, call: BridgeCall = callBridge): Promise<void> {
  const write = (obj: object) => process.stdout.write(JSON.stringify(obj) + "\n");
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of lines) {
    if (!line.trim()) continue;
    let msg: JsonRpc;
    try {
      msg = JSON.parse(line);
    } catch {
      write({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } });
      continue;
    }
    try {
      const out = await handleMcpMessage(msg, call, version);
      if (out) write(out);
    } catch (e) {
      if (msg.id !== undefined) write({ jsonrpc: "2.0", id: msg.id, error: { code: -32603, message: String(e) } });
    }
  }
}
