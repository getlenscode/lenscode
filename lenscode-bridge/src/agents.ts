/**
 * Agent subsystem (docs/paseo-migration-plan.md, Phase 2). Herdr had no agent
 * model at all — LensCode inferred agent state by scraping terminal output. Paseo
 * tracks agents as first-class objects, so this module is new (not a
 * Herdr-derived rewrite like terminals.ts), translating XR-shaped operations
 * into `DaemonClient` agent RPCs and normalizing the results for the client.
 */
import type { PaseoClient } from "./paseo.ts";
import type {
  AgentHistorySummary,
  AgentSummary,
  AgentTimelineEntry,
  PermissionRequestSummary,
  ProviderSummary,
} from "@lenscode/protocol";

/**
 * The subset of Paseo's timeline item union the client actually renders
 * (docs/paseo-migration-plan.md follow-up — confirmed live against a real
 * agent run, see toTimelineEntry's doc). Paseo's own type is broader (plan
 * updates, mode changes, reasoning deltas, …); anything not matched here
 * still surfaces via the "other" fallback in toTimelineEntry rather than
 * being silently dropped.
 */
interface PaseoTimelineItem {
  type: string;
  text?: string;
  name?: string;
  detail?: { type?: string; command?: string; output?: string };
  status?: string;
}

interface PaseoTimelineEntryRaw {
  item: PaseoTimelineItem;
  timestamp: string;
}

interface PaseoAgentSnapshot {
  id: string;
  provider: string;
  model: string | null;
  title: string | null;
  status: "initializing" | "idle" | "running" | "error" | "closed";
  cwd: string;
  workspaceId?: string;
  requiresAttention?: boolean;
  attentionReason?: "finished" | "error" | "permission" | null;
  pendingPermissions: Array<{
    id: string;
    kind: "tool" | "plan" | "question" | "mode" | "other";
    title?: string;
    description?: string;
    actions?: Array<{ id: string; label: string; behavior: "allow" | "deny" }>;
  }>;
  lastError?: string;
  lastUsage?: { totalCostUsd?: number | null };
  createdAt?: string;
  archivedAt?: string | null;
}

/**
 * Confirmed live (2026-09-07) against a real Claude agent run: Paseo's
 * timeline items come back as `user_message`/`assistant_message` (plain
 * `text`) and `tool_call` (`name` + `detail.command`/`detail.output` for a
 * shell call, `status` tracking running/completed/error). Everything else
 * — plan updates, mode changes, reasoning deltas, provider-specific items —
 * collapses to "other" with whatever text is available rather than being
 * dropped, so a new item type is visible (if ugly) instead of invisible.
 */
function toTimelineEntry(e: PaseoTimelineEntryRaw): AgentTimelineEntry {
  const item = e.item;
  const base = { timestamp: e.timestamp, toolName: null, command: null, output: null, status: null };
  if (item.type === "user_message" || item.type === "assistant_message") {
    return { ...base, kind: item.type, text: item.text ?? null };
  }
  if (item.type === "tool_call") {
    return {
      ...base,
      kind: "tool_call",
      text: null,
      toolName: item.name ?? null,
      command: item.detail?.type === "shell" ? (item.detail.command ?? null) : null,
      output: item.detail?.output ?? null,
      status: item.status ?? null,
    };
  }
  return { ...base, kind: "other", text: item.text ?? item.type };
}

function toAgentSummary(a: PaseoAgentSnapshot): AgentSummary {
  return {
    agentId: a.id,
    provider: a.provider,
    model: a.model,
    title: a.title,
    status: a.status,
    cwd: a.cwd,
    workspaceId: a.workspaceId ?? null,
    requiresAttention: a.requiresAttention ?? false,
    attentionReason: a.attentionReason ?? null,
    pendingPermissionCount: a.pendingPermissions?.length ?? 0,
    lastError: a.lastError ?? null,
    costUsd: a.lastUsage?.totalCostUsd ?? null,
  };
}

function toAgentHistorySummary(a: PaseoAgentSnapshot): AgentHistorySummary {
  return {
    agentId: a.id,
    provider: a.provider,
    model: a.model,
    title: a.title,
    status: a.status,
    cwd: a.cwd,
    createdAt: a.createdAt ?? "",
    archivedAt: a.archivedAt ?? null,
  };
}

export class Agents {
  private paseo: PaseoClient;

  constructor(paseo: PaseoClient) {
    this.paseo = paseo;
  }

  async list(): Promise<AgentSummary[]> {
    const { entries } = await this.paseo.daemon.fetchAgents();
    return entries.map((e) => toAgentSummary(e.agent as PaseoAgentSnapshot));
  }

  /**
   * Archived/closed agents (docs/snip-and-agent-management-plan.md) — Paseo
   * Desktop's own History view is built on this same daemon RPC;
   * `includeArchived: true` is required or it returns the same live-only set
   * `list()` already does.
   */
  async history(opts: {
    search?: string;
    limit?: number;
    cursor?: string;
  }): Promise<{ entries: AgentHistorySummary[]; nextCursor: string | null; hasMore: boolean }> {
    const { entries, pageInfo } = await this.paseo.daemon.fetchAgentHistory({
      filter: { includeArchived: true },
      ...(opts.search ? { search: opts.search } : {}),
      page: { limit: opts.limit ?? 50, ...(opts.cursor ? { cursor: opts.cursor } : {}) },
    });
    return {
      entries: entries.map((e) => toAgentHistorySummary(e.agent as PaseoAgentSnapshot)),
      nextCursor: pageInfo.nextCursor,
      hasMore: pageInfo.hasMore,
    };
  }

  async create(opts: {
    provider: string;
    model?: string;
    cwd: string;
    workspaceId?: string;
    title?: string;
    prompt?: string;
    modeId?: string;
  }): Promise<AgentSummary> {
    const agent = await this.paseo.daemon.createAgent({
      config: {
        provider: opts.provider,
        cwd: opts.cwd,
        ...(opts.model ? { model: opts.model } : {}),
        ...(opts.title ? { title: opts.title } : {}),
        ...(opts.modeId ? { modeId: opts.modeId } : {}),
      },
      ...(opts.workspaceId ? { workspaceId: opts.workspaceId } : {}),
      ...(opts.prompt ? { initialPrompt: opts.prompt } : {}),
    });
    return toAgentSummary(agent as PaseoAgentSnapshot);
  }

  /** Tail of one agent's activity — every message and tool call (docs/paseo-migration-plan.md follow-up). */
  async timeline(agentId: string, limit = 100): Promise<AgentTimelineEntry[]> {
    const timeline = await this.paseo.daemon.fetchAgentTimeline(agentId, { direction: "tail", limit });
    return (timeline.entries as unknown as PaseoTimelineEntryRaw[]).map(toTimelineEntry);
  }

  async send(agentId: string, text: string): Promise<void> {
    await this.paseo.daemon.sendAgentMessage(agentId, text);
  }

  async archive(agentId: string): Promise<void> {
    await this.paseo.daemon.archiveAgent(agentId);
  }

  /** Answers one pending permission request (spec §57 — always after an explicit XR confirmation). */
  async respondToPermission(
    agentId: string,
    permissionRequestId: string,
    behavior: "allow" | "deny",
    selectedActionId?: string,
    message?: string,
  ): Promise<void> {
    await this.paseo.daemon.respondToPermission(
      agentId,
      permissionRequestId,
      behavior === "allow"
        ? { behavior: "allow", ...(selectedActionId ? { selectedActionId } : {}) }
        : { behavior: "deny", ...(selectedActionId ? { selectedActionId } : {}), ...(message ? { message } : {}) },
    );
  }

  /** Pending permission requests across every agent — the spatial confirmation queue. */
  async pendingPermissions(): Promise<PermissionRequestSummary[]> {
    const { entries } = await this.paseo.daemon.fetchAgents();
    const out: PermissionRequestSummary[] = [];
    for (const { agent } of entries as { agent: PaseoAgentSnapshot }[]) {
      for (const p of agent.pendingPermissions ?? []) {
        out.push({
          permissionRequestId: p.id,
          agentId: agent.id,
          kind: p.kind,
          title: p.title ?? null,
          description: p.description ?? null,
          actions: (p.actions ?? []).map((a) => ({ id: a.id, label: a.label, behavior: a.behavior })),
        });
      }
    }
    return out;
  }
}

/**
 * `listAvailableProviders` only reports install status (id + available), not
 * models — fetch each available provider's models with `listProviderModels`
 * to build the full picker list. That call can be slow on a cold provider
 * SDK (the SDK gives it a 90s timeout), so this is meant for populating a
 * picker once, not a hot path.
 */
export async function listProviders(paseo: PaseoClient): Promise<ProviderSummary[]> {
  const { providers } = await paseo.daemon.listAvailableProviders();
  return Promise.all(
    providers.map(async (p) => {
      let models: string[] = [];
      if (p.available) {
        try {
          const res = await paseo.daemon.listProviderModels(p.provider);
          models = (res.models ?? []).map((m) => m.id);
        } catch {
          /* provider available but model discovery failed — picker just shows no models */
        }
      }
      return { provider: p.provider, label: p.provider, available: p.available, models };
    }),
  );
}
