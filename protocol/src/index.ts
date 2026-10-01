/**
 * LensCode XR protocol v3 — the contract between the Android XR client and lenscode-bridge.
 *
 * Design (spec §69):
 *  - every request carries a client-generated `id`; responses echo it
 *  - structured errors, never bare strings
 *  - server-push events are versioned envelopes
 *
 * v2 (docs/paseo-migration-plan.md, Phase 1): the bridge's backend switched from
 * Herdr to Paseo (https://github.com/getpaseo/paseo). There is no raw proxy
 * request anymore — Paseo's protocol churns on its own dated compat schedule
 * (packages/protocol/src/client-capabilities.ts upstream), and leaking that
 * straight into the Kotlin client would mean re-deriving those compat gates on
 * every daemon release. Every capability the XR client needs is modelled
 * explicitly below instead. `paneId` is renamed `terminalId` throughout to
 * match Paseo's own terminal ids (they are not Herdr panes).
 *
 * v3 (docs/paseo-migration-plan.md, Phase 2): agents become first-class. Herdr
 * had no agent model at all — LensCode inferred agent state by scraping terminal
 * output. Paseo tracks agents as their own objects (status, provider/model,
 * cost, pending permission requests), so `agent.*` and `permission.*` below
 * are new, not renamed. Agent/terminal push events (agent_stream,
 * agent_permission_request, terminals_changed, …) are not modelled as
 * distinct XR event types yet — they ride the existing generic `backend`
 * topic verbatim (bridge's `broadcastPaseoEvent`), same as every Paseo event
 * since Phase 1. Splitting them into typed topics is a client-ergonomics
 * improvement for Phase 3, not a protocol requirement.
 */
import { z } from "zod";

export const PROTOCOL_VERSION = 3;

// ---------------------------------------------------------------------------
// Envelopes
// ---------------------------------------------------------------------------

export const StructuredError = z.object({
  code: z.enum([
    "unauthorized",
    "pairing_required",
    "invalid_request",
    "not_found",
    "backend_unavailable",
    "backend_error",
    "conflict",
    "dangerous_op_unconfirmed",
    "internal",
  ]),
  /** Human-readable, user-facing reason (spec §68 — no ERR_SOCKET_13). */
  message: z.string(),
  /** Optional machine detail for the Details view. */
  detail: z.string().optional(),
  retryable: z.boolean(),
});
export type StructuredError = z.infer<typeof StructuredError>;

const requestBase = { id: z.string().min(1) };
/** "local" (default) or a connected remote machine id (spec §37). */
const machineScope = { machineId: z.string().optional() };

// ---------------------------------------------------------------------------
// Handshake
// ---------------------------------------------------------------------------

export const HelloRequest = z.object({
  ...requestBase,
  type: z.literal("hello"),
  protocolVersion: z.literal(PROTOCOL_VERSION),
  deviceId: z.string().min(1),
  /** Bearer secret issued at pairing (spec §55); absent → pairing flow (prod refuses). */
  deviceToken: z.string().optional(),
  clientInfo: z.object({
    app: z.string(),
    version: z.string(),
    platform: z.string(),
  }),
});

export const PairBeginRequest = z.object({
  ...requestBase,
  type: z.literal("pair.begin"),
  deviceId: z.string().min(1),
  deviceName: z.string().min(1),
});

export const PairCompleteRequest = z.object({
  ...requestBase,
  type: z.literal("pair.complete"),
  deviceId: z.string().min(1),
  /** Short-lived code displayed by the bridge CLI (spec §55). */
  pairingCode: z.string().min(6).max(10),
});

// ---------------------------------------------------------------------------
// Runtime state (spec §7, §58)
// ---------------------------------------------------------------------------

export const SnapshotRequest = z.object({
  ...requestBase,
  type: z.literal("snapshot.request"),
});

export const SubscribeRequest = z.object({
  ...requestBase,
  type: z.literal("events.subscribe"),
  topics: z.array(z.enum(["backend", "git", "files", "machines"])).min(1),
});

// ---------------------------------------------------------------------------
// Files & search (spec §12–14, §70)
// ---------------------------------------------------------------------------

export const FileOpenRequest = z.object({
  ...requestBase,
  type: z.literal("file.open"),
  machineId: z.string(),
  workspaceId: z.string(),
  path: z.string().min(1),
});

export const DirListRequest = z.object({
  ...requestBase,
  type: z.literal("dir.list"),
  machineId: z.string(),
  workspaceId: z.string(),
  path: z.string(),
});

export const SearchFilesRequest = z.object({
  ...requestBase,
  type: z.literal("search.files"),
  workspaceId: z.string(),
  query: z.string().min(1),
  limit: z.number().int().positive().max(100).default(20),
  /** Voice context for ranking + candidate flow (spec §48). */
  context: z
    .object({
      gazeTarget: z.string().optional(),
      activePanel: z.string().optional(),
      recentFiles: z.array(z.string()).optional(),
    })
    .optional(),
  ...machineScope,
});

/**
 * Voice file search (spec §13, P0). Distinct from search.files: this endpoint
 * always resolves to either a single confident path or a candidate list —
 * never a silent low-confidence guess.
 */
export const VoiceSearchRequest = z.object({
  ...requestBase,
  type: z.literal("voice.search"),
  workspaceId: z.string(),
  /** Raw transcribed speech; spoken punctuation is normalized server-side. */
  query: z.string().min(1),
  context: z
    .object({
      gazeTarget: z.string().optional(),
      activePanel: z.string().optional(),
    })
    .optional(),
  ...machineScope,
});

// ---------------------------------------------------------------------------
// Terminals (spec §18–21) — ergonomic wrappers over Paseo terminal operations
// ---------------------------------------------------------------------------

// Every terminal.* request below carries machineScope too.

export const TerminalListRequest = z.object({
  ...requestBase,
  type: z.literal("terminal.list"),
  ...machineScope,
});

export const TerminalCreateRequest = z.object({
  ...requestBase,
  type: z.literal("terminal.create"),
  /** Omit to create a terminal in the focused workspace. */
  workspaceId: z.string().optional(),
  name: z.string().optional(),
  cwd: z.string().optional(),
  ...machineScope,
});

export const TerminalCloseRequest = z.object({
  ...requestBase,
  type: z.literal("terminal.close"),
  terminalId: z.string().min(1),
  ...machineScope,
});

/**
 * Paseo has no server-side notion of terminal focus — the bridge tracks the
 * XR client's last-focused terminal locally and this is a no-op acknowledged
 * round-trip, kept so the client doesn't need a special case.
 */
export const TerminalFocusRequest = z.object({
  ...requestBase,
  type: z.literal("terminal.focus"),
  terminalId: z.string().min(1),
  ...machineScope,
});

export const TerminalRenameRequest = z.object({
  ...requestBase,
  type: z.literal("terminal.rename"),
  terminalId: z.string().min(1),
  name: z.string().min(1),
  ...machineScope,
});

export const TerminalReadRequest = z.object({
  ...requestBase,
  type: z.literal("terminal.read"),
  terminalId: z.string().min(1),
  source: z.enum(["visible", "recent", "recent_unwrapped"]).default("visible"),
  lines: z.number().int().positive().max(50_000).optional(),
  /** ANSI is kept for the XR renderer's color pass; strip for plain preview. */
  format: z.enum(["text", "ansi"]).default("ansi"),
  ...machineScope,
});

export const TerminalInputRequest = z.object({
  ...requestBase,
  type: z.literal("terminal.input"),
  terminalId: z.string().min(1),
  text: z.string(),
  ...machineScope,
});

/** Ctrl+C / SIGINT to the foreground process (spec §20). */
export const TerminalInterruptRequest = z.object({
  ...requestBase,
  type: z.literal("terminal.interrupt"),
  terminalId: z.string().min(1),
  /** Must be true — the XR confirmation card is mandatory before interrupting. */
  confirmed: z.literal(true),
  ...machineScope,
});

// ---------------------------------------------------------------------------
// Git (spec §23–27, §70)
// ---------------------------------------------------------------------------

export const GitBranchSwitchRequest = z.object({
  ...requestBase,
  type: z.literal("git.branch.switch"),
  workspaceId: z.string(),
  target: z.string().min(1),
  /** Safety resolution when dirty (spec §27); absent → bridge refuses if unsafe. */
  onDirty: z.enum(["stash", "worktree", "abort"]).optional(),
  ...machineScope,
});

/** Destructive — the client only ever sends this after its own confirmation card (spec §57). */
export const GitBranchDeleteRequest = z.object({
  ...requestBase,
  type: z.literal("git.branch.delete"),
  workspaceId: z.string(),
  branch: z.string().min(1),
  /** `-D` instead of `-d` — skips git's "not fully merged" check. */
  force: z.boolean().optional(),
  ...machineScope,
});

export const GitStatusRequest = z.object({
  ...requestBase,
  type: z.literal("git.status"),
  workspaceId: z.string(),
  ...machineScope,
});

/** Local branch names for the quick switcher (spec §26). */
export const GitBranchesRequest = z.object({
  ...requestBase,
  type: z.literal("git.branches"),
  workspaceId: z.string(),
  ...machineScope,
});

/** Unified diff for one file, relative to HEAD (untracked files diff against empty). */
export const GitDiffRequest = z.object({
  ...requestBase,
  type: z.literal("git.diff"),
  workspaceId: z.string(),
  path: z.string().min(1),
  ...machineScope,
});

/**
 * Per-hunk accept/reject (spec §31). `patch` is exactly one hunk's own text —
 * the file header lines plus one `@@ … @@` block — as the client re-derives
 * it from the `git.diff` text it already displayed; the bridge pipes it
 * straight to `git apply`, never re-parsing it.
 * "stage" adds only that hunk to the index; "discard" is destructive (spec
 * §57) — reverses that hunk out of the working tree — and the client only
 * ever sends it after its own confirmation card.
 */
export const GitHunkApplyRequest = z.object({
  ...requestBase,
  type: z.literal("git.hunk.apply"),
  workspaceId: z.string(),
  path: z.string().min(1),
  patch: z.string().min(1),
  action: z.enum(["stage", "discard"]),
  ...machineScope,
});

// ---------------------------------------------------------------------------
// Agents (docs/paseo-migration-plan.md, Phase 2) — first-class Paseo agents
// ---------------------------------------------------------------------------

export const AgentListRequest = z.object({
  ...requestBase,
  type: z.literal("agent.list"),
  ...machineScope,
});

/**
 * Archived/closed agents — `agent.list` only ever returns live ones, so
 * without this an archived agent disappears from the client entirely even
 * though Paseo still has its full history (`daemon.fetchAgentHistory`,
 * which is what Paseo Desktop's own History view is built on).
 */
export const AgentHistoryRequest = z.object({
  ...requestBase,
  type: z.literal("agent.history"),
  search: z.string().optional(),
  limit: z.number().int().positive().optional(),
  /** From a previous agent.history response's nextCursor. */
  cursor: z.string().optional(),
  ...machineScope,
});

export const AgentCreateRequest = z.object({
  ...requestBase,
  type: z.literal("agent.create"),
  /** e.g. "claude", "codex", "opencode", "pi" — see provider.list. */
  provider: z.string().min(1),
  model: z.string().optional(),
  cwd: z.string().min(1),
  workspaceId: z.string().optional(),
  title: z.string().optional(),
  /** Starts the agent working immediately; omit to create it idle. */
  prompt: z.string().optional(),
  /**
   * Paseo permission mode, e.g. "default" (ask every time — the daemon's own
   * default), "auto" (classifier-reviewed auto-approval), "acceptEdits",
   * "bypassPermissions". Omit for "default" — confirmed live (2026-09-07): a
   * voice-created agent left on "default" sits forever on its first tool
   * call's permission request since nothing in the voice flow ever answers
   * it, so ask_agent always passes one explicitly.
   */
  modeId: z.string().optional(),
  /**
   * Run the agent in a brand-new git worktree branched off `cwd`'s current
   * checkout instead of the checkout itself. The response's agent carries the
   * new workspaceId so the client can open it in Code.
   */
  worktree: z.boolean().optional(),
  ...machineScope,
});

/** Sends a follow-up prompt to an existing agent (fire-and-forget; watch agent_stream for the reply). */
export const AgentSendRequest = z.object({
  ...requestBase,
  type: z.literal("agent.send"),
  agentId: z.string().min(1),
  text: z.string().min(1),
  ...machineScope,
});

/**
 * The agent's own activity — every message and tool call, tail-first (docs/paseo-migration-plan.md
 * follow-up: previously an agent's Bash/tool output was only visible by querying
 * the Paseo daemon directly; no XR-client-facing view existed at all). A thin
 * projection of Paseo's fetchAgentTimeline, normalized to the handful of item
 * shapes the client actually renders.
 */
export const AgentTimelineRequest = z.object({
  ...requestBase,
  type: z.literal("agent.timeline"),
  agentId: z.string().min(1),
  /** Most recent N entries. Defaults bridge-side (agents.ts) if omitted. */
  limit: z.number().int().positive().optional(),
  ...machineScope,
});

/** Soft-deletes the agent and closes its runtime (spec §57 — destructive; client confirms first). */
export const AgentArchiveRequest = z.object({
  ...requestBase,
  type: z.literal("agent.archive"),
  agentId: z.string().min(1),
  ...machineScope,
});

// ---------------------------------------------------------------------------
// Permissions (docs/paseo-migration-plan.md, Phase 2) — the spatial "approve
// this tool call" card is the highest-value glasses affordance in this phase.
// ---------------------------------------------------------------------------

/** Every pending permission request across all local agents — the confirmation queue. */
export const PermissionListRequest = z.object({
  ...requestBase,
  type: z.literal("permission.list"),
  ...machineScope,
});

export const PermissionRespondRequest = z.object({
  ...requestBase,
  type: z.literal("permission.respond"),
  agentId: z.string().min(1),
  /** id of the AgentPermissionRequest being answered (from an agent_permission_request event). */
  permissionRequestId: z.string().min(1),
  behavior: z.enum(["allow", "deny"]),
  /** One of the request's own offered actions, when it had more than a bare allow/deny. */
  selectedActionId: z.string().optional(),
  /** Shown to the agent when denying (e.g. "not right now"). */
  message: z.string().optional(),
  ...machineScope,
});

// ---------------------------------------------------------------------------
// Providers (docs/paseo-migration-plan.md, Phase 2) — populates a provider/model picker
// ---------------------------------------------------------------------------

export const ProviderListRequest = z.object({
  ...requestBase,
  type: z.literal("provider.list"),
  ...machineScope,
});

// ---------------------------------------------------------------------------
// Worktrees (docs/paseo-migration-plan.md, Phase 3) — Herdr's worktree.* was
// reachable through the now-removed herdr.request proxy; these are its
// direct XR-protocol replacements, backed by Paseo's Paseo-owned-worktree
// RPCs (getPaseoWorktreeList / createPaseoWorktree / archivePaseoWorktree).
// ---------------------------------------------------------------------------

export const WorktreeListRequest = z.object({
  ...requestBase,
  type: z.literal("worktree.list"),
  workspaceId: z.string(),
  ...machineScope,
});

/** Opens (creating a workspace for it if needed) the checkout at an existing worktree path. */
export const WorktreeOpenRequest = z.object({
  ...requestBase,
  type: z.literal("worktree.open"),
  path: z.string().min(1),
  ...machineScope,
});

/** Destructive (spec §57) — only ever sent after the client's own confirmation card. */
export const WorktreeRemoveRequest = z.object({
  ...requestBase,
  type: z.literal("worktree.remove"),
  /** The worktree's own open workspace id (from worktree.list's openWorkspaceId). */
  workspaceId: z.string().min(1),
  ...machineScope,
});

// ---------------------------------------------------------------------------
// Remote machines (spec §36–37, Phase 3)
// ---------------------------------------------------------------------------

/** Lists configured SSH host aliases. Never returns IPs/users/key paths (spec §54, §36). */
export const MachineListRequest = z.object({
  ...requestBase,
  type: z.literal("machine.list"),
});

/** Opens an SSH tunnel to the machine's own Paseo daemon port (spec §37). Raw key material never leaves the bridge. */
export const MachineConnectRequest = z.object({
  ...requestBase,
  type: z.literal("machine.connect"),
  machineId: z.string().min(1),
});

export const MachineDisconnectRequest = z.object({
  ...requestBase,
  type: z.literal("machine.disconnect"),
  machineId: z.string().min(1),
});

// ---------------------------------------------------------------------------
// Snip tool — XR client crops a screenshot of its own workspace locally
// (SceneCore's spatial compositor gives no server-side capture surface to
// grab), then hands the flattened PNG to the bridge. agent.send has no image
// field, so this writes the file to disk on the bridge's own machine and
// hands the client back an absolute path to reference in a normal text
// prompt — Claude Code's Read tool already handles image files directly.
// ---------------------------------------------------------------------------

export const SnipUploadRequest = z.object({
  ...requestBase,
  type: z.literal("snip.upload"),
  /** Base64-encoded PNG bytes of the cropped screenshot. */
  pngBase64: z.string().min(1),
});

// ---------------------------------------------------------------------------
// Request union / responses / events
// ---------------------------------------------------------------------------

export const XrRequest = z.discriminatedUnion("type", [
  HelloRequest,
  PairBeginRequest,
  PairCompleteRequest,
  SnapshotRequest,
  SubscribeRequest,
  FileOpenRequest,
  DirListRequest,
  SearchFilesRequest,
  VoiceSearchRequest,
  TerminalListRequest,
  TerminalCreateRequest,
  TerminalCloseRequest,
  TerminalFocusRequest,
  TerminalRenameRequest,
  TerminalReadRequest,
  TerminalInputRequest,
  TerminalInterruptRequest,
  GitBranchSwitchRequest,
  GitBranchDeleteRequest,
  GitStatusRequest,
  GitDiffRequest,
  GitHunkApplyRequest,
  GitBranchesRequest,
  MachineListRequest,
  MachineConnectRequest,
  MachineDisconnectRequest,
  AgentListRequest,
  AgentHistoryRequest,
  AgentCreateRequest,
  AgentSendRequest,
  AgentTimelineRequest,
  AgentArchiveRequest,
  PermissionListRequest,
  PermissionRespondRequest,
  ProviderListRequest,
  WorktreeListRequest,
  WorktreeOpenRequest,
  WorktreeRemoveRequest,
  SnipUploadRequest,
]);
export type XrRequest = z.infer<typeof XrRequest>;

export const XrResponse = z.object({
  id: z.string(),
  /** Exactly one of result/error is present. */
  result: z.unknown().optional(),
  error: StructuredError.optional(),
});
export type XrResponse = z.infer<typeof XrResponse>;

export const XrEvent = z.object({
  type: z.literal("event"),
  topic: z.enum(["backend", "git", "files", "machines", "connection"]),
  event: z.string().min(1),
  data: z.unknown(),
  /** Monotonic per-topic sequence for gap detection after reconnect (spec §59). */
  seq: z.number().int().nonnegative(),
});
export type XrEvent = z.infer<typeof XrEvent>;

export const XrServerMessage = z.union([XrResponse, XrEvent]);
export type XrServerMessage = z.infer<typeof XrServerMessage>;

// ---------------------------------------------------------------------------
// Result payloads (M1–M2 subset; grows per milestone)
// ---------------------------------------------------------------------------

export const HelloResult = z.object({
  protocolVersion: z.literal(PROTOCOL_VERSION),
  serverVersion: z.string(),
  authenticated: z.boolean(),
  /** Paseo daemon version string (e.g. "0.7.2"), null if never connected. */
  backendProtocol: z.string().nullable(),
  backendConnected: z.boolean(),
});
export type HelloResult = z.infer<typeof HelloResult>;

/**
 * Speculative pre-Paseo agent state enum (M1–M2 spec draft) — unused by
 * anything current; only the archived client-unity ever referenced it. The
 * real backend status enum is `AgentStatus` below (Paseo's own
 * AGENT_LIFECYCLE_STATUSES). Kept for now so client-unity still parses.
 */
export const AgentState = z.enum([
  "working",
  "idle",
  "completed",
  "attention",
  "failed",
  "stopped",
]);

// ---------------------------------------------------------------------------
// Agent / permission result payloads (docs/paseo-migration-plan.md, Phase 2)
// ---------------------------------------------------------------------------

/** Mirrors Paseo's AGENT_LIFECYCLE_STATUSES verbatim (backend is authoritative). */
export const AgentStatus = z.enum(["initializing", "idle", "running", "error", "closed"]);
export type AgentStatus = z.infer<typeof AgentStatus>;

export const AgentSummary = z.object({
  agentId: z.string(),
  provider: z.string(),
  model: z.string().nullable(),
  title: z.string().nullable(),
  status: AgentStatus,
  cwd: z.string(),
  workspaceId: z.string().nullable(),
  requiresAttention: z.boolean(),
  attentionReason: z.enum(["finished", "error", "permission"]).nullable(),
  pendingPermissionCount: z.number().int().nonnegative(),
  lastError: z.string().nullable(),
  costUsd: z.number().nullable(),
  /** Which machine runs this agent — "local" or a connected remote's id. Omitted by single-machine backends. */
  machineId: z.string().optional(),
});
export type AgentSummary = z.infer<typeof AgentSummary>;

/** One archived/closed agent.history entry — read-only (no pending permissions/cost, nothing to act on). */
export const AgentHistorySummary = z.object({
  agentId: z.string(),
  provider: z.string(),
  model: z.string().nullable(),
  title: z.string().nullable(),
  status: AgentStatus,
  cwd: z.string(),
  createdAt: z.string(),
  archivedAt: z.string().nullable(),
});
export type AgentHistorySummary = z.infer<typeof AgentHistorySummary>;

/**
 * One entry in an agent's activity feed — normalized from Paseo's timeline
 * item union down to what the client actually renders. `kind` picks which
 * other fields are populated; unrecognized Paseo item types collapse to
 * "other" with a best-effort `text` rather than being dropped, so a new
 * provider event type shows up as *something* instead of silently vanishing.
 */
export const AgentTimelineEntry = z.object({
  kind: z.enum(["user_message", "assistant_message", "tool_call", "other"]),
  text: z.string().nullable(),
  /** Only for kind=tool_call, e.g. "Bash", "Read", "Edit". */
  toolName: z.string().nullable(),
  /** Only for kind=tool_call with a shell command (Paseo's "shell" detail type). */
  command: z.string().nullable(),
  /** Only for kind=tool_call: the tool's result text, once it has one. */
  output: z.string().nullable(),
  /** Only for kind=tool_call: "running" | "completed" | "error" | ... — Paseo's own status string. */
  status: z.string().nullable(),
  timestamp: z.string(),
});
export type AgentTimelineEntry = z.infer<typeof AgentTimelineEntry>;

/** One pending "approve this tool call" request — the spatial confirmation card's data. */
export const PermissionRequestSummary = z.object({
  permissionRequestId: z.string(),
  agentId: z.string(),
  kind: z.enum(["tool", "plan", "question", "mode", "other"]),
  title: z.string().nullable(),
  description: z.string().nullable(),
  actions: z.array(
    z.object({
      id: z.string(),
      label: z.string(),
      behavior: z.enum(["allow", "deny"]),
    }),
  ),
});
export type PermissionRequestSummary = z.infer<typeof PermissionRequestSummary>;

/** One provider/model combination the daemon can start an agent with. */
export const ProviderSummary = z.object({
  provider: z.string(),
  label: z.string(),
  available: z.boolean(),
  models: z.array(z.string()),
});
export type ProviderSummary = z.infer<typeof ProviderSummary>;

/** One Paseo-owned worktree (docs/paseo-migration-plan.md, Phase 3). */
export const WorktreeSummary = z.object({
  path: z.string(),
  branch: z.string().nullable(),
  /** Non-null when a workspace is currently open on this worktree (worktree.open resolves/creates one). */
  openWorkspaceId: z.string().nullable(),
});
export type WorktreeSummary = z.infer<typeof WorktreeSummary>;

export const SnapshotResult = z.object({
  /** Paseo daemon snapshot (terminals + projects today; agents in Phase 2), forwarded verbatim (backend is authoritative, spec §58). */
  backend: z.unknown().nullable(),
  machines: z.array(
    z.object({
      machineId: z.string(),
      alias: z.string(),
      kind: z.enum(["local", "remote"]),
      online: z.boolean(),
    }),
  ),
});
export type SnapshotResult = z.infer<typeof SnapshotResult>;
