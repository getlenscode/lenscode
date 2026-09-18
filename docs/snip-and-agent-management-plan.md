# Snip tool + agent lifecycle management — plan

Follow-up to the Paseo migration (docs/paseo-migration-plan.md). Covers the
XR screenshot-crop-and-attach ("snip") tool and the agent management gaps it
surfaced: no session history view, no voice way to kill agents beyond the
one currently open.

## Done

- **Snip capture** (`ui/SnipCapture.kt`) — `SnipCaptureController` +
  `SnipRegion` wrap each visible panel's Compose content in a `GraphicsLayer`
  so it can be rendered to a real bitmap on demand, sidestepping the known
  `adb screencap`-returns-a-fixed-mirror limitation (see herdr-ipc-gotchas
  memory) since this captures Compose pixels directly, not the OS surface.
  Regions (`agents` / `primary` / `dock`) stack top-to-bottom in that order
  in the composite, matching their actual on-screen arrangement — Subspace
  exposes no true 3D world position in 2D screen space to composite more
  precisely than that.
  - Fixed live: `GraphicsLayer.toImageBitmap()` returns a GPU-backed
    `HARDWARE` bitmap; drawing that on the software `Canvas` used to stack
    regions crashed with `IllegalArgumentException: Software rendering
    doesn't support hardware bitmaps`. Now copied to `ARGB_8888` first.
- **Crop overlay** (`ui/SnipCropOverlay.kt`) — two independent drag handles
  (top-left, bottom-right), each with its own `pointerInput` gesture scope,
  so a two-hand pinch (one hand per handle) or a single hand on either
  handle both work, the same mechanism the existing `movable`/`resizable`
  panel modifiers already rely on for pinch input.
- **Upload + attach path** — `protocol`'s `snip.upload` (base64 PNG in,
  bridge writes it to `~/.aura/snips/` on the bridge's own machine, returns
  the absolute path out) since `agent.send` has no image field and Claude
  Code's own Read tool already handles image files directly.
- **Multi-agent targeting** — never guesses which agent a snip belongs to
  (same "never silently guess" discipline as voice search, spec §13):
  - 0 active agents → creates one, screenshot path as its opening task.
  - 1 active agent → auto-attaches, opens its card with the path prefilled
    in the prompt box (unsent — same review-before-send rule every other
    voice-filled prompt in this app follows).
  - 2+ active agents → `ui/SnipAgentPickerOverlay.kt`, a scrollable
    (`LazyColumn`, capped height) picker card; tapping the dimmed background
    instead of an agent discards the screenshot with a toast rather than
    guessing.
- **Dock button** — scissor icon (`DockIcon.kt`'s `"snip"` case) alongside
  the existing line-icon set.
- **Session/agent history view** — `agent.history` (protocol + bridge,
  wraps Paseo's `fetchAgentHistory({filter: {includeArchived: true}, ...})`,
  the same daemon RPC Paseo Desktop's own History view is built on) plus a
  **History** chip button in `AgentsPanelContent` (bordered, not a plain text
  link — a first attempt as a plain colored text link went unnoticed).
  Archived/closed agents render read-only by reusing `AgentDetailOverlay`
  (`AgentHistoryEntry.toAgentInfo()` forces `status = "closed"`, which the
  overlay already treats as no-prompt-box) rather than a second card
  implementation. Grouped under a yellow (`AuraColors.Amber`) date-header bar
  per creation day ("Today" / "Yesterday" / full date), with pagination via
  a "Load more" row once the backend's `hasMore` is true. Verified live:
  direct WebSocket smoke test of `agent.history` returned real archived +
  live entries with working cursor pagination; `paseo agent logs` confirmed
  timeline reads still work post-archive.
- **Root-caused and fixed** (unrelated to snip code, but discovered by it):
  Paseo was resolving a stale `@anthropic-ai/claude-code@1.0.128` npm
  package under `~/node_modules` ahead of the current CLI on PATH, so every
  agent started in `acceptEdits`/`bypassPermissions` mode crashed with
  `unknown option '--allow-dangerously-skip-permissions'`. Removed the
  package from `~/package.json`/`~/node_modules` and regenerated
  `~/package-lock.json`; Paseo now resolves the current `claude` binary.

## Still missing

### 1. Voice: kill agent by name / kill all
Today `archive_agent` (`VoiceCommandRouter.kt`, `KoogVoiceRouter.kt`,
`executeVoiceAction`'s `ArchiveAgent` case) only ever archives whichever
agent's card is currently open (`agentDetail?.agent`) — no way to target by
name or archive several at once.

Steps:
1. **Grammar**: extend the `archive_agent` action with an optional
   `target: string` field — `"current"` (default, keeps today's behavior),
   an agent name/label to fuzzy-match, or `"all"`. Update:
   - `KoogVoiceRouter.kt`'s `VoiceAction.ArchiveAgent` (currently a
     `data object`, needs to become a `data class` carrying `target`), its
     JSON schema/system prompt (so "kill the terminal test agent" or "stop
     all agents" extracts the right value), and the `"archive_agent" ->`
     branch that builds it.
   - `VoiceCommandRouter.kt`'s deterministic fallback: extend the existing
     `\b(archive|close|stop)\b.*\bagent\b` regex to also capture `all` vs a
     trailing name (e.g. `archive agent B`, `kill all agents`).
2. **Resolution**: in `executeVoiceAction`'s `ArchiveAgent` case, resolve
   `target` against the live `agents` list:
   - `"current"` → today's behavior (falls back to no-op if no card open).
   - a name → fuzzy match against `agent.label` (reuse whatever matching
     approach `voice.search`/`bridge.voiceSearch` already uses server-side
     for consistency, or a simple case-insensitive substring match
     client-side since the agent list is already local).
   - `"all"` → every agent with `status != "closed"`.
3. **Confirmation**: keep the existing `DeleteConfirmState` confirm-card
   discipline (spec §57) for every case — for `"all"`, one card listing how
   many agents will be archived rather than one card per agent.
4. Ambiguous name matches (e.g. two agents both contain "test"): same "never
   silently guess" rule as snip's multi-agent picker — surface a picker
   instead of guessing which one, reusing `SnipAgentPickerOverlay`'s pattern
   (rename/generalize it to a plain `AgentPickerOverlay` if reused outside
   snip).

### 2. Known gaps / not yet verified
- **Real two-hand pinch on hardware** — verified the crop overlay doesn't
  crash and single-hand drag works in principle (pointer input, same
  mechanism as existing movable/resizable panels), but genuine two-hand
  simultaneous pinch on both handles at once hasn't been confirmed in-headset
  (adb can't drive two simultaneous pinches, and this device's spatial
  content doesn't show up in `adb screencap` — same limitation noted in
  herdr-ipc-gotchas memory).
- **Composite screenshot quality** — the flattened top-to-bottom montage is
  a deliberate approximation (see herdr-ipc-gotchas: SceneCore exposes no
  screen-space panel position), not a real spatial capture. Worth a look
  once real headset testing is underway — may want a "primary panel only"
  fast path if the full composite feels noisy in practice.
- **Cost/session hygiene** — snip's zero-active-agent fallback
  (`startVoiceAgent`) spins up a brand-new Claude Code agent same as voice's
  `ask_agent`. No limit today on how many of these can pile up beyond manual
  archiving — the history view (§1) is what makes it possible to actually
  see and clean these up rather than losing track of them.
