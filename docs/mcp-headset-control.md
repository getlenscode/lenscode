# Drive the glasses from Claude Code or Claude Desktop (MCP)

## Why this exists

You work in Claude Code or the Claude desktop app on your computer. The glasses are a second
screen: a dev-server preview, the terminal where a build is running, the remote project you want
to keep an eye on. Without this, getting something onto the glasses means putting the glasses on,
finding the right view and typing or dictating an address.

With the MCP server, you stay in Claude and say it: "open that preview on my glasses", "show me
the tmux session the build is running in", "open `~/Documents/app` on my dev box in Code". Claude
does it, and the glasses show it.

It is an **MCP server** rather than a skill because a skill only gives Claude instructions. Opening
something on the headset needs a real connection to it, and an MCP server provides that for Claude
Code, Claude Desktop, and any other MCP client in the same way.

## How it works

```
Claude Code / Claude Desktop
        │  MCP (stdio)
        ▼
lenscode-bridge mcp          a short-lived process the MCP client starts
        │  HTTP, 127.0.0.1 only, bearer token
        ▼
lenscode-bridge (running)    the bridge you already run for the app
        │  WebSocket (the app's normal connection)
        ▼
LensCode app on the glasses  does what the matching tap would
```

`lenscode-bridge mcp` starts no bridge of its own. It finds the running one through
`~/.config/lenscode-bridge/control.json` (written when the bridge starts, readable by you only) and
asks it to send a command to the paired glasses.

## Set it up

1. **Run the bridge** and pair the glasses, as usual:

   ```bash
   npx lenscode-bridge --secure
   ```

2. **Register the MCP server.**

   Claude Code:

   ```bash
   claude mcp add lenscode -- npx lenscode-bridge mcp
   ```

   Claude Desktop, in `~/Library/Application Support/Claude/claude_desktop_config.json`:

   ```json
   { "mcpServers": { "lenscode": { "command": "npx", "args": ["lenscode-bridge", "mcp"] } } }
   ```

   Working from a clone of this repo instead of the published package:

   ```bash
   claude mcp add lenscode -- node --experimental-strip-types /path/to/aura-workspace/lenscode-bridge/src/main.ts mcp
   ```

3. **Start a new Claude session** (or reconnect the server from `/mcp`). MCP servers are loaded
   when a session starts, so one added mid-session does not show up until then.

`npx lenscode-bridge mcp` needs bridge `0.1.0-alpha.4` or newer. Until then use the
clone form above.

## The tools

| Tool | What it does on the glasses |
|---|---|
| `lenscode_open_url` | Opens a web address in the app's own browser. http and https only. |
| `lenscode_open_project` | Connects a remote machine over SSH and opens a folder on it in Code. |
| `lenscode_open_tmux_session` | Shows a tmux session in the Terminal view (creates it if it does not exist). |
| `lenscode_open_file` | Opens a file in Code. |
| `lenscode_show_view` | Switches to a view: home, code, terminal, agents, permissions, diff, remote, browser, settings. |
| `lenscode_notify` | Shows a short message (a toast, up to 300 characters). |
| `lenscode_list_tmux_sessions` | Lists the tmux sessions on a machine, to pick one. Does not need the glasses. |
| `lenscode_list_machines` | Lists the SSH machines the bridge knows and whether each is connected. Does not need the glasses. |
| `lenscode_status` | Whether the bridge is running and how many glasses are connected. |

Every tool except the last three needs paired glasses connected to the bridge. Without them Claude
is told so, rather than the command silently going nowhere.

## Examples

Say these to Claude (the wording is yours; Claude picks the tool).

**Put a preview on the glasses**

> Start the dev server, then open it on my glasses.

Claude starts the server, then calls `lenscode_open_url` with this computer's address, for example
`http://192.168.1.157:3000`. See "localhost" below.

**Open a project on another machine**

> Open `~/Documents/wsr-test` on doraemon-arch in my glasses.

`lenscode_open_project` with `machine: "doraemon-arch"` and `path: "~/Documents/wsr-test"`. The bridge
connects to the machine over your passwordless SSH, so it shows as connected in the app's Remote
view, resolves `~` to that machine's home, checks the folder exists, and opens it in Code. If Paseo
is not running on that machine it starts `paseo daemon` there first; if Paseo is not installed it
tells you instead.

**Watch a long-running job**

> Run the build in a tmux session called `build`, then show it on my glasses.

Claude runs it in tmux, then `lenscode_open_tmux_session` with `session: "build"` attaches the
Terminal view to it.

> Which tmux sessions are open on doraemon-arch?

`lenscode_list_tmux_sessions` with `machine: "doraemon-arch"`.

**Open a file**

> Open `src/server.ts` in my glasses.

`lenscode_open_file`. A path under the folder Code already has open opens directly; an absolute path
elsewhere opens its folder in Code first. A relative path needs Code to be on that machine.

**Get your attention**

> When the tests finish, show me a message on the glasses.

`lenscode_notify` with the result, so you do not have to watch the terminal.

**Switch views**

> Show the diff on my glasses.

`lenscode_show_view` with `view: "diff"`.

## Things to know

- **"localhost" means the glasses.** A link opens in the app's own browser, which is on the glasses,
  so `localhost:3000` points at the glasses themselves. Give the computer's LAN address
  (`192.168.1.157:3000`), as you would for any other device on the network.
- **Machines are SSH aliases.** `machine` is a `Host` name from your `~/.ssh/config` (see
  `lenscode_list_machines`), with key-based login. Note the spelling: an alias with a hyphen is
  not the same as one with an underscore.
- **Paired glasses only.** In secure mode, only a headset that has paired (with the 6-digit code)
  receives commands.
- **Restart the bridge after updating it.** The bridge that is running is the one that serves these
  commands, so a bridge started before an update does not have the newer tools.

## Safety

- The control endpoint listens on `127.0.0.1` only, on a random port, and each request needs the
  token in `control.json` (mode 0600). Nothing on your network can reach it, and only processes
  running as you can read the token.
- Links must be http or https: no `javascript:`, `file:` or `intent:`.
- A tmux session name may only contain letters, digits, `.`, `-` and `_`, because it is typed into a
  shell on the machine. Both the bridge and the app check this.
- Folder paths are quoted before they reach `ssh`, and a machine must be one of your SSH aliases; it
  can never be an arbitrary string.

## Troubleshooting

| Claude says | Meaning and fix |
|---|---|
| "The LensCode bridge isn't running on this computer" | Start `npx lenscode-bridge --secure` (or `npm run dev -- --secure` from the repo). |
| "No LensCode headset is connected to the bridge" | Open the app on the glasses and pair it. Check with `lenscode_status`. |
| "<machine> isn't in the SSH machine registry" | Use an alias from `~/.ssh/config`; `lenscode_list_machines` shows them. |
| "Couldn't connect to <machine>" | Plain `ssh <machine>` must work without a password. |
| "Paseo isn't installed on <machine>" | `npm install -g @getpaseo/cli` there, then ask again. |
| "<path> isn't a folder on <machine>" | The folder does not exist; check the path. |
| The tools are not listed | Start a new Claude session after registering the server. |

## For developers

The code is in `lenscode-bridge/src/mcp/` (layout in its `README.md`; tests in
`lenscode-bridge/test/mcp/`). The app side is the `com.lenscode.app.control` package, which
receives the `control` events the bridge sends.
