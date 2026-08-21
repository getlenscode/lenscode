# Herdr IPC Surface (observed from herdr 0.8.2)

Source of truth: `herdr api schema --json` → committed at `docs/herdr-api-schema.json`.
Regenerate after Herdr updates; the schema carries `protocol` (currently **20**) and
`schema_version` (currently **1**) — the bridge must check `protocol` at connect time.

## Transport

- Unix domain socket: `~/.config/herdr/herdr.sock` (path reported by `herdr status`).
- JSON message envelopes:
  - Request: `{ "id": "<string>", "method": "<name>", "params": { … } }`
  - Success: `{ "id": "<string>", "result": { … } }`
  - Error:   `{ "id": "<string>", "error": { … } }`
  - Event:   `{ "event": "<kind>", "data": { … } }` (after `events.subscribe`)
- Server may not be running (`herdr status` → `server: not running`); the persistent
  session/server starts on `herdr` launch or `herdr server`. The bridge must surface
  "Herdr not running" as a clear error state, not a crash.

## Method groups relevant to Aura (125 methods total; key ones)

| Spec need | Herdr methods |
|---|---|
| Session snapshot (spec §7) | `session.snapshot` |
| Event stream (spec §7) | `events.subscribe`, `events.wait` — 26 event kinds (`pane_created`, `pane_output_changed`, `pane_agent_status_changed`, `workspace_*`, `tab_*`, `worktree_*`, `layout_updated`) |
| Workspaces | `workspace.create/list/get/focus/close/rename/move/report_metadata` |
| Tabs | `tab.create/list/get/focus/close/rename/move` |
| Panes / terminals (spec §18–20) | `pane.list/get/current/focus/close/split/resize/zoom/move/swap` |
| Terminal input (Ctrl+C = `send_keys`) | `pane.send_input`, `pane.send_keys`, `pane.send_text`, `pane.input.set` |
| Terminal output / scrollback | `pane.read` (format/lines/strip_ansi opts), `pane.wait_for_output`, `pane_output_changed` event |
| Process info (Stop confirmation card) | `pane.process_info` |
| Agents (spec §31–35) | `agent.start`, `agent.list/get`, `agent.prompt` (with wait-until-status), `agent.read`, `agent.send_keys`, `agent.focus`, `agent.wait`, `agent.rename`, `agent.explain` |
| Agent status events | `pane_agent_detected`, `pane_agent_status_changed` |
| Worktrees (spec §28–30) | `worktree.create/list/open/remove` + `worktree_created/opened/removed` events |
| Notifications | `notification.show` |
| Health | `ping` |

## Implications for aura-bridge

1. **Transparent proxy is viable** (spec §69–70): the XR `herdr.request` message can wrap
   the native envelope unchanged; responses/events forward as-is after permission checks.
2. **Generated client**: schema is JSON Schema 2020-12 with a `oneOf` discriminated union on
   `method` — generate TS types for the bridge and (selectively) C# DTOs for Unity.
3. **Ctrl+C**: `pane.send_keys` with the interrupt key chord (verify exact key encoding
   against `request.$defs` when implementing M4).
4. **Agent prompt/steer**: `agent.prompt { target, text, wait? }` covers send-code-to-agent
   and steering; `wait.until: [AgentStatus…]` enables approval/completion flows.
5. **Remote (Phase 3)**: `herdr --remote <ssh-target>` exists natively — remote Herdr
   attachment may be delegated to Herdr itself rather than custom SSH tunneling.
6. **Terminal output model**: `pane_output_changed` event + `pane.read` pull. The bridge
   should debounce/batch reads and diff-forward to XR to hit the 10k-line streaming target.

## Security notes

- The raw socket must never be network-exposed (spec §6). Only aura-bridge talks to it.
- `server.stop`, `server.live_handoff`, `plugin.*`, `integration.*` should be **blocked**
  at the bridge's method allowlist — the XR client has no business calling them.
