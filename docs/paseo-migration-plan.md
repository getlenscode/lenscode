# Plan: replace Herdr with Paseo

**Status:** Phases 0–3 all done: protocol + bridge validated live against a real Paseo daemon, and the
Kotlin client rewritten against the new protocol and builds clean end-to-end (2026-09-05).
**Backup taken before any change:** `../backups/agent-workspace-pre-paseo-20260904-223256.zip`
(all sources + full `.git`, 7.6 MB; regenerable caches excluded)

## Phase 2 status (2026-09-05) — validated live

`agent.*`, `permission.respond`, and `provider.list` added to `protocol/` (bumped to v3) and implemented in a
new `lenscode-bridge/src/agents.ts`, wired into `server.ts`. Local-machine only for now — no remote-agent story
(`agent.*`/`permission.*`/`provider.*` ignore `machineId`; `terminal.*` still routes per machine as before).
Agent/permission push events (agent_stream, agent_permission_request, …) needed no new wiring — they already
flow through the generic `backend` topic broadcast built in Phase 1.

Ran the full suite (29 tests, including 3 new `agents.test.ts` cases) against the same real
`@getpaseo/cli` 0.7.2 daemon used for Phase 1 validation. **29/29 pass**, including a full
`agent.create` → `agent.list` → `agent.send` → `agent.archive` round-trip against a real installed provider
(Claude, on this machine) and `provider.list` reporting the daemon's actual provider availability
(Claude/Codex/OpenCode/pi). Protocol bumped from v2 to v3; all test files' `protocolVersion` literals updated
to match.

Scope note: `listProviders()` fans out one `listProviderModels` call per *available* provider to populate a
model picker — the SDK gives that call up to a 90s timeout for a cold provider CLI, so treat `provider.list`
as a "populate a picker once" call, not a hot path.

## Phase 0 + Phase 1 status (2026-09-05) — validated live

Ran a real `@getpaseo/cli` 0.7.2 daemon locally (isolated `HOME`, scratch dir, not the user's real Paseo
install) and pointed the full bridge test suite at it via `LENSCODE_PASEO_URL`. **26/26 tests pass** (0 skipped,
0 failed) — terminal create/rename/list/read/close, Ctrl+C interrupt, 20-concurrent-terminals, snapshot, and
the full pairing flow all exercised against real daemon responses, not mocks.

Two real bugs only the live daemon surfaced (fixed in `terminals.ts`):
- **`create_terminal_request` requires a `workspaceId`** — unlike Herdr, Paseo has no "just start a shell in
  this cwd" call. `Terminals.create()` now resolves an existing workspace for the cwd via `fetchWorkspaces()`
  or creates a `{kind: "directory", path}` one via `createWorkspace()` first.
- **Display name field is inverted from what the schema comments imply**: `name` is set once at creation and
  never changes; `terminal.rename.request` sets `title` instead, which *starts out* as the cwd (an absolute
  path) and only stops looking like one once explicitly renamed — the opposite of how title/name work on
  `WorkspaceDescriptorPayload`. `toTerminalInfo()`'s `displayName()` now falls back to `name` when `title`
  still looks like a path.

Done, typechecked, and tested:
- `protocol/`: bumped to v2, `paneId` → `terminalId` throughout, `herdr_unavailable`/`herdr_error` →
  `backend_unavailable`/`backend_error`, `herdrConnected`/`herdrProtocol` → `backendConnected`/`backendProtocol`
  (now a version string, not a number), `herdr.request`/`HerdrProxyRequest` removed, `terminal.process_info`
  removed (no Paseo equivalent). 6/6 tests green.
- `lenscode-bridge/`: `herdr.ts` deleted, replaced by `paseo.ts` (wraps `@getpaseo/client`'s `DaemonClient` via its
  `internal/daemon-client` export — the high-level `createPaseoClient()` agent/workspace wrapper is Phase 2).
  `terminals.ts` rewritten against real SDK calls (`listTerminals`/`createTerminal`/`killTerminal`/
  `renameTerminal`/`captureTerminal`/`sendTerminalInput`), including the workspace-resolution and
  display-name fixes above. `server.ts` rewired: worktree flow now uses
  `createPaseoWorktree`/`getPaseoWorktreeList`/`fetchWorkspaces` instead of raw Herdr `worktree.*` calls
  (not yet exercised live — no worktree/branch-switch test in the suite). `remote.ts` forwards a TCP port
  (Paseo listens on 6767) instead of a Unix socket (not yet validated live — needs a second machine or a
  loopback SSH setup). `config.ts` reads `LENSCODE_PASEO_URL`/`LENSCODE_PASEO_PASSWORD` (defaults to
  `ws://127.0.0.1:6767/ws`), warns once on the old `LENSCODE_HERDR_SOCKET` key. `allowlist.ts` deleted (its only
  caller, the `herdr.request` passthrough, is gone).

## Phase 3 status (2026-09-05) — Kotlin client rewritten, builds clean

`herdrRequest()` deleted from `BridgeClient.kt` entirely — every call site now hits a typed `agent.*`,
`permission.*`, or `worktree.*` request instead of the removed Herdr proxy. This required extending the
protocol beyond Phase 2:
- Added `permission.list` (Phase 2 only had `permission.respond`; the client needs to list pending requests
  too) and `worktree.list`/`worktree.open`/`worktree.remove` (Herdr's worktree.* was only ever reachable
  through the removed proxy — these are its direct replacements, backed by
  `getPaseoWorktreeList`/`createWorkspace`/`archivePaseoWorktree`). Both validated live: a full
  create → list → open → remove round trip against a real Paseo-owned worktree, and `permission.list`
  returning an array. **32/32 bridge tests pass live** (2 new files: `worktrees.test.ts`, expanded
  `agents.test.ts`), plus **live-only bugs the daemon surfaced and fixed**:
  - `archivePaseoWorktree` rejects a bare `workspaceId` ("worktreePath, worktreeSlug, or repoRoot+branchName
    is required") — the handler now resolves the workspace's directory first via `fetchWorkspaces`.
  - `fetchWorkspaces()` without a page limit only returns its default first page; under this suite's own
    concurrent load (many parallel test files hitting one daemon) a just-created workspace could miss it.
    Every `fetchWorkspaces()` call in the bridge now passes `{ page: { limit: 200 } }`.
  - Both worktree creation and removal lag the daemon's own `getPaseoWorktreeList` by up to several seconds
    under concurrent load — a real Paseo characteristic, not a bridge bug; the new test polls rather than
    asserting instant consistency.

Kotlin data model changes (`BridgeClient.kt`): `PaneInfo.paneId`→`terminalId` (dropped `tabId`/`focused` —
no Paseo equivalent; added `agentStatus` from Paseo's real per-terminal activity state). `AgentInfo` fully
replaced — Paseo agents are first-class objects (provider/model/status/cost/pendingPermissionCount), not a
terminal pane Herdr flagged as agent-shaped (the old `AgentInfo.paneId` field is gone along with the model it
implied). New `PermissionInfo`/`PermissionAction` types. `UiState.herdrConnected`→`backendConnected`.

UI changes (`WorkspaceUi.kt`, `MainActivity.kt`):
- **New `PermissionsPanelContent` + dock entry** — the permission-approval queue is the single highest-value
  affordance this migration adds (no Herdr-era equivalent existed to migrate away from). Lists every pending
  request across every agent with Allow/Deny (or the request's own named actions), wired to
  `permission.respond`.
- `AgentDetailOverlay` redesigned: dropped the live terminal-snippet preview (agents aren't terminals anymore)
  for real agent metadata (cwd, model, cost, last error, pending-permission count). "Open Terminal"/"Interrupt"
  (which assumed agent==pane) replaced with "Open Workspace" (opens the agent's own workspaceId/cwd) and
  "Archive" (a real `agent.archive` call, gated behind a confirmation card per spec §57).
  `canPrompt` changed from Herdr's `status != "blocked"` to Paseo's `status != "closed"`.
  "Send" now calls `agent.send` instead of the removed Herdr `agent.prompt` proxy call.
- Terminal list status dot: was `pane.focused` (no longer exists), now `pane.agentStatus`
  (idle/working/attention) via a new `terminalActivityColor()` helper — genuinely more useful than a stale
  focus flag.
- `LensCodeColors.agentStatusColor()` updated from Herdr's status vocabulary (working/blocked/done/idle) to
  Paseo's real `AGENT_LIFECYCLE_STATUSES` (initializing/idle/running/error/closed).
- Worktree view: dropped `isLinked`/"main checkout" distinction — Paseo's worktree list is Paseo-owned linked
  worktrees only, it never includes the repo's main checkout, so every entry is now uniformly removable.

**Verified**: `./gradlew :app:compileDebugKotlin` and `:app:assembleDebug` both `BUILD SUCCESSFUL` — the client
compiles and packages against the new protocol. Not yet re-run on the emulator end-to-end (that needs the
AVD from the earlier Herdr-era verification pass, re-pointed at a live Paseo daemon instead of Herdr) — the
next natural checkpoint, not a known gap.

**Remaining, not done in this pass:**
- Emulator smoke test (dumpsys + logcat + network state, per the existing verification method — screenshots
  don't work on this AVD regardless of backend, see Risks).
- `git.branch.switch`'s `onDirty: "worktree"` path and the remote-machine (SSH-tunneled) `PaseoClient`
  connection are still implemented-but-unvalidated-live from Phase 1.
- `agent.create`/`provider.list` have working bridge handlers and Kotlin methods but no "create a new agent"
  UI flow was built — Herdr never had one either (agents were auto-detected in panes), so this is genuinely
  new scope, not a migration gap. Worth adding once the permission-queue affordance proves out.

## Goal

Make LensCode an **XR client for Paseo** — what `packages/app` (Expo) and the web UI are for
phones and browsers, but for smart glasses. Herdr is removed entirely.

## What Paseo actually is (verified against getpaseo/paseo @ 0.7.2)

- **Daemon** (`packages/server`), default `ws://127.0.0.1:6767/ws`. All state lives here.
- **Protocol** (`@getpaseo/protocol`) — zod schemas, ~7.2k lines in `messages.ts`,
  plus AOT-generated validators and **binary terminal frames**
  (opcodes `Output/Input/Resize/Snapshot/Restore`, one byte + slot + payload).
- **TS SDK** (`@getpaseo/client`) — `createPaseoClient({url, password})`, handles the
  handshake (`server_info`), `client_capabilities` negotiation, reconnection, the
  terminal stream router, and the **E2EE relay transport**.
- **Transports**: direct TCP, Tailscale, SSH tunnel, or the encrypted relay
  (`relay.paseo.sh:443`, QR/link pairing via `paseo daemon pair`).
- **Compat policy**: `docs/protocol-compatibility.md` + `CLIENT_CAPS` gates with dated
  `COMPAT(...)` removals. Old names stay accepted for a window; new RPCs are dotted
  (`workspace.create.request`), legacy ones flat (`create_terminal_request`).

The important upgrade over Herdr: Paseo has **agents as first-class objects** —
status, timeline, permission requests, usage/cost, subagents, modes, providers.
Herdr only had panes and PTYs; LensCode had to infer agent state from scraped terminal
output. That inference layer disappears.

## Architectural decision

**Keep `lenscode-bridge` and swap its backend: `HerdrClient` → `@getpaseo/client`.**
Do *not* make the Kotlin client speak Paseo's wire protocol directly.

Why:
- The XR client cannot reasonably reimplement 7.2k lines of zod schemas, AOT
  validators, binary frame demuxing, and dated capability gates in Kotlin. There is
  no Kotlin SDK in the repo (its 26 KB of Kotlin is Expo native glue).
- The bridge is Node/TS, so it consumes the official SDK unchanged and inherits
  reconnect, relay E2EE, and compat handling for free.
- Paseo's protocol churns on a published schedule. The bridge absorbs that churn;
  `protocol/` stays a narrow, stable, glasses-shaped surface that the Kotlin client
  and the generated C# DTOs keep targeting.
- LensCode's own concerns (device pairing, gaze/pinch affordances, panel layout
  persistence, voice search, walking mode) stay bridge-side where they already work.

Cost of the alternative (direct Kotlin → daemon): rewrite of `BridgeClient.kt`
(1030 lines) into a partial, hand-maintained protocol port that breaks on every
`COMPAT` expiry. Rejected.

The bridge's role changes from *"Herdr IPC proxy + everything Herdr lacks"* to
*"Paseo session adapter + XR-specific projection"*. It gets smaller, not bigger.

## Herdr coupling (what actually has to change)

| File | Lines | Herdr refs | Action |
|---|---|---|---|
| `lenscode-bridge/src/herdr.ts` | 184 | 10 | **Delete**, replace with `paseo.ts` |
| `lenscode-bridge/src/terminals.ts` | 156 | 17 | Rewrite against Paseo terminal RPCs |
| `lenscode-bridge/src/server.ts` | 821 | 42 | Rewire; drop `herdr.request` passthrough |
| `lenscode-bridge/src/allowlist.ts` | 33 | 2 | Repoint at Paseo RPC names |
| `lenscode-bridge/src/config.ts` | 43 | 3 | `herdrSocketPath` → Paseo URL/password/relay |
| `lenscode-bridge/src/remote.ts` | 137 | 2 | Machines → Paseo hosts |
| `files.ts` `git.ts` `pairing.ts` `machines.ts` `remoteFiles.ts` | 899 | 0 | Untouched in Phase 1; candidates for deletion in Phase 4 |

Client side: `BridgeClient.kt` and `WorkspaceUi.kt` mention "herdr" only as XR-protocol
field names (`herdrConnected`, `herdr_unavailable`) — pure renames, no logic change.

`docs/herdr-ipc.md` and `docs/herdr-api-schema.json` were removed once Herdr was no longer used (see git history).

## RPC mapping

| LensCode XR protocol | Herdr (old) | Paseo (new) |
|---|---|---|
| `terminal.list` | `pane.list` + `tab.list` | `list_terminals_request` |
| `terminal.create` | `tab.create` | `create_terminal_request` |
| `terminal.close` | `pane.close` | `kill_terminal_request` |
| `terminal.rename` | `tab.rename` | `terminal.rename.request` |
| `terminal.read` | `pane.read` | `capture_terminal_request` / snapshot frame |
| `terminal.input` | `pane.send_text` | binary `Input` frame (opcode `0x02`) |
| `terminal.interrupt` | `pane.send_keys ctrl+c` | `Input` frame with `0x03` |
| `terminal.focus` | `pane.focus` | *(no daemon equivalent — becomes client-local)* |
| `terminal.process_info` | `pane.process_info` | fold into `terminal_activity` / terminal state |
| `snapshot.request` | `session.snapshot` | `agent_list` + `list_terminals` + `project.list` |
| `events.subscribe` | `events.subscribe` | `subscribe_terminals` + agent stream subscriptions |
| `herdr.request` | passthrough | **removed** (no raw passthrough; see Risks) |
| *(new)* `agent.*` | — | `create_agent_request`, `send_agent_message`, `agent_stream`, `agent_status`, `agent.rewind`, `agent.detach` |
| *(new)* `permission.*` | — | `agent_permission_request` / `_response` / `_resolved` |
| *(new)* `provider.*` | — | `list_available_providers`, `list_provider_models`, `list_provider_modes` |
| `git.diff` (bespoke) | local git | `subscribe_checkout_diff_request` |
| `git.status` / `branches` | local git | `checkout.refresh`, `checkout.commits.list` |
| `dir.list` / `file.open` | local fs | `fs.entry.*`, `fs.file.subscribe`, `fs.file.update` |
| `search.files` | local | keep bridge-side (Paseo has no file-search RPC) |

## Phases

### Phase 0 — spike (0.5 day)
Run `paseo daemon` locally. Write `scratch/paseo-probe.ts` using `@getpaseo/client`:
connect, `agents.create`, `waitForFinish`, list terminals, subscribe to one terminal
and print decoded frames. Confirms the SDK works from a plain Node script and pins
the real shapes before any LensCode code changes. Record findings in `docs/paseo-ipc.md`
(the successor to `herdr-ipc.md`).

### Phase 1 — backend swap, protocol frozen (2–3 days)
Goal: **the Android client binary does not change and still works.**
1. `npm i @getpaseo/client @getpaseo/protocol` in `lenscode-bridge`.
2. New `lenscode-bridge/src/paseo.ts` exposing the same shape `server.ts` expects
   (`connect`, `request`, `subscribeEvents`, `connected`, `protocol`, event emitter).
3. Rewrite `terminals.ts` against the mapping table; `terminal.focus` becomes a
   bridge-local no-op that still returns success.
4. `config.ts`: `herdrSocketPath` → `paseo: {url, password?, relay?}` with env
   overrides. Keep reading the old key for one release, warn, and ignore it.
5. `allowlist.ts`: replace the Herdr method allowlist with a Paseo one.
6. Delete `herdr.ts` and the `herdr.request` case.
7. Port `lenscode-bridge/test/*.ts` (28 tests currently green against live Herdr 0.8.2)
   to run against a live `paseo daemon`. **Gate: 28/28 green again.**

Rename `herdrConnected` → `backendConnected` and `herdr_unavailable` →
`backend_unavailable` in `protocol/`, regenerate C# DTOs, and update the two Kotlin
call sites. Bump XR protocol to v2 — this is the only client-visible change in Phase 1.

### Phase 2 — agents become first-class in the XR protocol (3–4 days)
Add `agent.*`, `permission.*`, `provider.*` to `protocol/src/index.ts` and stream
`agent_stream` / `agent_status` through to the client. Delete whatever terminal-output
scraping currently fakes agent status. This is where the bridge stops being a Herdr
shim and starts being a Paseo adapter.

### Phase 3 — XR client surfaces them (5–7 days)
The mockup's agent-strip / primary-panel / dock already anticipates this:
- **Agent strip** → one spatial card per Paseo agent: status, provider/model badge,
  live cost, attention pulse on `agent_attention_required`.
- **Permission requests** → the highest-value glasses affordance. A spatial dialog
  that gaze-targets and resolves with a pinch. Approving a tool call from a heads-up
  card is the thing a phone does worse.
- **Primary panel** → agent timeline (`agent_stream`) with the existing
  `CodeHighlighter.kt`, replacing raw pane text.
- **Dock** → terminals via the binary stream, unchanged in feel.
- **Voice** → route `VoiceRecognizer` output to `send_agent_message` instead of
  `pane.send_text`.

### Phase 4 — retire the bespoke layers (2 days)
Once Paseo's `fs.*` and `checkout.*` are wired, delete or thin `files.ts` (303),
`git.ts` (233), `remoteFiles.ts` (234). `machines.ts` + `remote.ts` become a thin
wrapper over Paseo's multi-host support. Expect a net ~700-line deletion.

### Phase 5 — pairing and transport (1–2 days)
LensCode's own `pairing.ts` (device → bridge) stays. Add bridge → daemon transport
config: direct, Tailscale, or relay. Reuse the existing QR flow so the glasses pair
to the bridge and the bridge pairs to the daemon, rather than inventing a second
pairing UX inside a headset.

## Risks

- **No raw passthrough.** `herdr.request` let the client reach anything Herdr had.
  Dropping it means every capability must be modelled in the XR protocol. Deliberate:
  a passthrough against a 7.2k-line schema with dated compat gates would leak that
  churn straight into the Kotlin client. If a debug escape hatch is wanted, add
  `paseo.request` gated behind a dev-only config flag, off in release builds.
- **Binary terminal frames.** Herdr was NDJSON text end to end. Paseo's terminal
  stream is binary and slot-multiplexed. The bridge decodes it (SDK's
  `terminal-stream-router`) and re-emits text over the XR protocol — that keeps the
  Kotlin side simple but adds a transcode hop. Measure it before optimizing; if it
  hurts, forward frames verbatim and decode in Kotlin (~200 lines, self-contained).
- **Compat expiry.** Several `CLIENT_CAPS` gates expire through 2027. Pin
  `@getpaseo/protocol` exactly, advertise capabilities honestly, and add a CI check
  that fails when the pinned version drifts from the running daemon's `server_info`.
- **Emulator screenshots still lie.** `adb shell screencap` on AVD `aura_xr` returns
  a fixed environment mirror regardless of spatial content. Verification stays
  dumpsys + logcat + network state, exactly as before.

## Non-goals

- Rewriting the Kotlin client's spatial layout — Phase 3 rewired data/behavior only, no new panel geometry
  beyond the one new Permissions dock entry.
- Reviving `client-unity/`. It stays archived.
- Contributing an XR client upstream to getpaseo/paseo (revisit once the emulator smoke test passes).
- A "create new agent" UI flow (`agent.create`/`provider.list` are wired bridge-side and in `BridgeClient.kt`
  but have no UI yet — see Phase 3 status).
