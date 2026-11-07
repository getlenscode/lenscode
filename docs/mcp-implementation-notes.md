# MCP: drive the headset from Claude Code / Claude Desktop

Developer notes for the MCP server. The user-facing guide (why, setup, tools, examples) is [mcp-headset-control.md](mcp-headset-control.md).

- User guide (why, setup, tools, examples): `docs/mcp-headset-control.md`. Everything is in `lenscode-bridge/src/mcp/` (see its `README.md`; tests in `test/mcp/`): `commands.ts` (the commands and their checks), `endpoint.ts` (loopback listener, token, `control.json`), `tools.ts`, `client.ts`, `server.ts` (stdio MCP, hand-written JSON-RPC, no SDK), `remoteDaemon.ts` (starts a remote Paseo for `open_project`), `index.ts`. The rest of the bridge imports only from `./mcp/index.ts`.
- `lenscode-bridge mcp` starts no bridge: it reads `~/.config/lenscode-bridge/control.json` (port + token, 0600, written by `main.ts` at startup) and POSTs to the running bridge's control endpoint. That endpoint is a separate HTTP listener on `127.0.0.1`, random port, bearer token. Never bind it to the LAN.
- Headset commands go to paired clients as `control` topic events. The app side is the `com.lenscode.app.control` package (`ControlCommands.kt`, tests in `test/.../control/`); `handleControlCommand` in `WorkspaceRoot.kt` acts on them.
- tmux session names are typed into a shell (`tmux new -A -s <name>`), so both sides only accept `[A-Za-z0-9._-]{1,64}`; URLs must be http(s); folder paths for `open_project` are single-quoted (`shellQuote`) before they reach ssh. Keep these checks.
- Dev from the repo: `claude mcp add lenscode -- node --experimental-strip-types <repo>/lenscode-bridge/src/main.ts mcp`. `npx lenscode-bridge mcp` needs bridge `0.1.0-alpha.4` or newer.
