# Tasks board (Paseo Tasks plugin)

The dock's Tasks view and how the bridge talks to the plugin. Its voice commands are in [voice-routing.md](voice-routing.md).

## Install the plugin

- `paseo plugin add anh-chu/tasks` needs Paseo 0.11.2+. The plugin's README asks for Node 24+ on the daemon host (`node:sqlite`), but it ran on Node 22.20 here.
- **Paseo 0.12.0 install fails** with `Could not resolve type dependency "@getpaseo/client"` (the plugin's own issue #1: the dependency isn't declared). Workaround that doesn't touch Paseo: clone `anh-chu/paseo-plugin-tasks`, run `npm pkg set devDependencies.@getpaseo/client=0.12.0 && npm install --ignore-scripts`, then `paseo plugin add <that folder>`. Paseo runs the plugin from that folder, so keep it.
- Plugins are off daemon-wide by default ("Plugins are globally disabled"): set `pluginsEnabled: true` in `~/.paseo/config.json` (or Settings → Plugins in the Paseo app) and run `paseo reload`; no daemon restart is needed. `paseo plugin ls` should then show `running`.

## How the bridge talks to it

- The bridge never touches the plugin's SQLite store. `lenscode-bridge/src/tasks.ts` POSTs `argv` (always with `--json`) to the plugin's loopback `/cli` endpoint, using the token in `$PASEO_HOME/plugin-data/tasks/bridge.json`: the same call the plugin's `paseo-tasks` script makes. Remote machines run that machine's `paseo-tasks` over SSH.
- Requests: `tasks.list`, `tasks.create`, `tasks.update`. They are not workspace-scoped, and keys, statuses and priorities are validated before they reach the CLI. `tasks.list` joins projects, presets, tasks and each open task's attached agent sessions.
- If the plugin isn't running, `tasks.list` answers `available: false` with an install hint, which the view shows.
- Tested against the real plugin on Paseo 0.12.0 (list, create, update). `test/tasks.test.ts` uses a stand-in endpoint.

## The view (`ui/TasksPanel.kt`)

- One column per status (backlog, todo, in progress, in review, done; canceled only when it has tasks). It polls every 8 s while open, and loads once on connect so voice has a board to match against.
- The board has its own machine: "This computer" (the bridge's Paseo) unless you pick a connected remote. It is independent of the machine the Code view browses.
- Agent tags: a task's tags are the providers of its attached sessions (mapped through the Agents list) plus agents whose title starts with the task key (`taskAgentTags`). Chips filter by agent and, with several projects, by project.
- Tapping a card offers "Move to" and "Run with <preset>".

## Running an agent on a task

The plugin's own `delegate` and `attach` fail when called through its CLI ("Delegation requires an active Paseo daemon connection", its issue #2). So `BridgeClient.runTask` starts the agent through the normal `agent.create` path (preset provider, model and worktree setting), titles it `KEY: task title`, and moves the task to In Progress. The agent's prompt includes the task text, the preset's instructions and how to update the task with `paseo-tasks`. This path has not been tried on the headset.

Agents also get the tracker's instructions injected by the plugin, so they can create and update tasks themselves.

## Voice

`add_task`, `move_task` and `run_task` are in `voice/VoiceRouter.kt`. The router is given the board's tasks (`VoiceRouterContext.tasks`) and returns the key it means (`taskKey`) plus alternatives, so a misheard reference is corrected by the model. `voice/TaskRef.kt` (`resolveTaskChoice`, `resolveTask`) turns that into a card: the model's key first, then the spoken key, a bare number (including spelled-out numbers) or title words. Several matches give a "Which one?" toast.
