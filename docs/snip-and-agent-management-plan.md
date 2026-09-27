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
  bridge writes it to `~/.lenscode/snips/` on the bridge's own machine, returns
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
  implementation. Grouped under a yellow (`LensCodeColors.Amber`) date-header bar
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

- **Voice: kill agent by name / kill all** — `archive_agent`'s grammar now
  carries a `target` (`KoogVoiceRouter.kt`'s `ArchiveAgent` is a `data class`
  now, not a `data object`; `VoiceCommandRouter.kt`'s deterministic fallback
  extracts it via `extractArchiveTarget`, checked *before* the
  `kill...terminal` pattern now — an agent whose own name contains
  "terminal" was otherwise misrouted to `KillTerminal` first, caught by a
  new unit test). `executeVoiceAction` resolves it: `"current"` (default)
  archives whichever card is open, `"all"` archives every non-closed agent
  behind one "archive all N" confirm card, and a name fuzzy-matches
  (case-insensitive substring) against active agents' labels — one match
  confirms directly, several surface the same picker pattern snip uses
  (`SnipAgentPickerOverlay` generalized into `AgentPickerOverlay` with a
  `title` param, reused for both), zero matches or a canceled picker toasts
  rather than silently doing nothing or guessing. `VoiceCommandRouterTest`
  covers `all`/named/current extraction. Verified: full unit test suite
  green, app builds and connects live with no crash.

- **Voice navigation gaps** — audited the full voice grammar against the
  real dock (11 destinations) and found `navigate` only knew 8 of them:
  Settings and Browser were completely unreachable by voice in both
  `KoogVoiceRouter.kt` and the deterministic `VoiceCommandRouter.kt`
  fallback. Fixed (Browser mirrors the dock's own behavior — it opens the
  URL launch dialog rather than a raw `activeView` set, since there's
  nothing to show with no URL loaded yet). Also added, since they came up
  in the same audit:
  - **`dismiss`** — closes/cancels whatever confirmation card or overlay is
    currently open ("cancel", "never mind", "close this", "go back").
    Previously nothing in the grammar could close an open overlay; voice
    went dead until you tapped. Resolves each open overlay's own
    `onCancel` where one exists (not just nulling local state) —
    `branchConflict`'s cancel actively aborts an in-flight branch switch
    server-side, so skipping it would leave that dangling.
  - **`open_agent`** — opens a specific agent's detail card by name (distinct
    from `navigate(agents)`, which only shows the list). Shares
    `resolveAgentByName`'s fuzzy-match + never-guess picker with
    `archive_agent`, extracted into one helper to avoid duplicating that
    logic a third time.
  - Deliberately **not** added: voice terminal-selection-by-name (`kill_terminal`
    still only ever targets whichever pane is currently selected, same
    class of gap `archive_agent` used to have) — separate scope, flagged
    here as a follow-up idea rather than built now.
  - Verified: full unit test suite green, app builds/installs/connects live
    with no crash.
- **Home view HUD** — the home/primary panel was mostly empty space around a
  small centered status block. Added a Stark-HUD-style header (reference:
  a classic Rainmeter "Iron Man" desktop skin) built on the app's own
  existing cyan-accent smoked-glass palette (`LensCodeColors`) rather than a
  mismatched reskin: glowing ring gauges for time (with a seconds-hand
  sweep), battery, and RAM usage, plus a current-weather card. System-wide
  CPU usage was deliberately dropped per the user's own call — not
  reliably readable on modern non-rooted Android, and faking a number was
  off the table.
  - `net/WeatherClient.kt` — Open-Meteo (no API key needed), current
    temperature/humidity/wind/WMO weather code → emoji+label.
  - Location: `ACCESS_COARSE_LOCATION` requested once at launch;
    `LocationManager.getCurrentLocation` (API 30+) rather than
    `getLastKnownLocation`, which stayed null indefinitely on this LensCode
    unit (never taken a fix). Retries a few times with a short gap before
    giving up, since a single cold request often comes back null too —
    confirmed live. Denial and "still resolving" both show an honest
    placeholder in the weather card rather than fake data.
  - `ui/HomeHud.kt` — `HudRing` (glow approximated with a wide faint stroke
    behind a sharp one, not a real blur — untested/risky whether
    `Modifier.blur`'s RenderEffect path behaves correctly hosted inside a
    Subspace panel's 2D surface, so avoided rather than risk it), RAM
    polled via `ActivityManager.MemoryInfo` (no permission needed).
  - Verified: builds clean, installs, no crash; location permission prompt
    and grant flow confirmed live; weather fetch reliability on this
    specific device still being confirmed as of this writing (device seems
    slow/inconsistent to resolve a location fix indoors — see the retry
    loop above).

## Still missing

### 1. Known gaps / not yet verified
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
  archiving — the history view (now in Done, above) is what makes it
  possible to actually see and clean these up rather than losing track of
  them.
- **Weather fetch reliability on this specific Aura unit** — location
  services are enabled and reachable, but `getCurrentLocation` has come
  back null on more than one cold attempt in testing (likely this
  particular device being slow/inconsistent at resolving GPS/network
  location indoors, not a code issue) — the retry loop mitigates but
  doesn't fully solve this. Worth another look if the weather card sits on
  "Finding your weather…" for a long time in practice.

### 2. Follow-up ideas raised but not built
- **Voice: select/kill a terminal pane by name** — same class of gap
  `archive_agent` used to have (only ever targets "whichever pane is
  currently selected"), noticed while auditing voice navigation but kept
  out of scope for that change. Would reuse `resolveAgentByName`'s pattern
  against `terminals: List<PaneInfo>` instead of agents.
