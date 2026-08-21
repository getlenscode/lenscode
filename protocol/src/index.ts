/**
 * Aura XR protocol v1 — the contract between the Unity XR client and aura-bridge.
 *
 * Design (spec §69):
 *  - every request carries a client-generated `id`; responses echo it
 *  - structured errors, never bare strings
 *  - server-push events are versioned envelopes
 *  - `herdr.request` transparently wraps a native Herdr envelope (docs/herdr-ipc.md)
 */
import { z } from "zod";

export const PROTOCOL_VERSION = 1;

// ---------------------------------------------------------------------------
// Envelopes
// ---------------------------------------------------------------------------

export const StructuredError = z.object({
  code: z.enum([
    "unauthorized",
    "pairing_required",
    "invalid_request",
    "not_found",
    "herdr_unavailable",
    "herdr_error",
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

// ---------------------------------------------------------------------------
// Handshake
// ---------------------------------------------------------------------------

export const HelloRequest = z.object({
  ...requestBase,
  type: z.literal("hello"),
  protocolVersion: z.literal(PROTOCOL_VERSION),
  deviceId: z.string().min(1),
  /** Device certificate fingerprint issued at pairing; absent → pairing flow. */
  deviceCertFingerprint: z.string().optional(),
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
  topics: z.array(z.enum(["herdr", "git", "files", "machines"])).min(1),
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
});

export const GitStatusRequest = z.object({
  ...requestBase,
  type: z.literal("git.status"),
  workspaceId: z.string(),
});

// ---------------------------------------------------------------------------
// Herdr transparent proxy (spec §70, docs/herdr-ipc.md)
// ---------------------------------------------------------------------------

export const HerdrProxyRequest = z.object({
  ...requestBase,
  type: z.literal("herdr.request"),
  /** Native Herdr envelope; bridge validates method against its allowlist. */
  request: z.object({
    id: z.string(),
    method: z.string().min(1),
    params: z.record(z.string(), z.unknown()),
  }),
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
  GitBranchSwitchRequest,
  GitStatusRequest,
  HerdrProxyRequest,
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
  topic: z.enum(["herdr", "git", "files", "machines", "connection"]),
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
  herdrProtocol: z.number().int().nullable(),
  herdrConnected: z.boolean(),
});
export type HelloResult = z.infer<typeof HelloResult>;

export const AgentState = z.enum([
  "working",
  "idle",
  "completed",
  "attention",
  "failed",
  "stopped",
]);

export const SnapshotResult = z.object({
  /** Herdr session snapshot, forwarded verbatim (backend is authoritative, spec §58). */
  herdr: z.unknown().nullable(),
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
