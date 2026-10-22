# MCP: control the headset from Claude

`lenscode-bridge mcp` lets Claude Code / Claude Desktop open links, tmux sessions, files and remote
projects on the glasses. What it is for, how to set it up, the tools, examples and troubleshooting:
[docs/mcp-headset-control.md](../../../docs/mcp-headset-control.md).

This folder is the implementation.

## Layout

| File | What it is |
|---|---|
| `commands.ts` | The commands a tool can send, and the checks they go through (pure; start here) |
| `endpoint.ts` | The bridge's loopback listener, its token, and `control.json` |
| `tools.ts` | The MCP tools Claude is offered and how each maps to a command |
| `client.ts` | How the MCP server reaches the running bridge |
| `server.ts` | The MCP server itself (JSON-RPC over stdio) |
| `remoteDaemon.ts` | Starting a remote machine's Paseo daemon when `open_project` finds it not running |
| `index.ts` | Re-exports; the rest of the bridge imports from here only |

Tests are in `test/mcp/`. The app side is `client-android/.../control/` (it receives the `control` events).

`lenscode_open_project` also takes a machine and a folder (`~` is that machine's home): it connects the
machine over passwordless SSH, so the app lists it as connected, and opens the folder in Code.
If the tunnel comes up but Paseo isn't running there, it starts `paseo daemon` on that machine and connects
again (`remoteDaemon.ts`); if `paseo` isn't installed there it says so instead. Only `open_project` does this.

