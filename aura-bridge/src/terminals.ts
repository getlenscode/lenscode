/**
 * Terminal subsystem (spec §18–21). Paseo owns every terminal (spec §4.1) —
 * this module only translates XR-shaped operations into Paseo terminal RPCs
 * and normalizes the results for the client.
 *
 * docs/paseo-migration-plan.md, Phase 1: replaces the Herdr pane/tab model.
 * Paseo has no per-terminal "focus" concept and no process-info RPC — those
 * two Herdr operations have no daemon equivalent (see the RPC mapping table
 * in the plan); `focus` is now a bridge-local acknowledgement only.
 */
import type { PaseoClient } from "./paseo.ts";

export interface TerminalInfo {
  terminalId: string;
  workspaceId: string | null;
  name: string;
  cwd: string | null;
  agentStatus: "idle" | "working" | "attention" | null;
}

interface PaseoTerminalInfo {
  id: string;
  name: string;
  /** Omitted from list_terminals_response entries (they're scoped by the query cwd already); present on create. */
  cwd?: string;
  workspaceId?: string;
  title?: string;
  activity?: { state: "idle" | "working" | "attention" } | null;
}

/**
 * Confirmed live against paseo 0.7.2: `name` is set once at creation (an
 * explicit request name, or an auto-numbered "Terminal N") and never changes
 * afterwards; `terminal.rename.request` instead sets `title`, which starts
 * out as the terminal's cwd (an absolute path) and only stops looking like a
 * path once the client explicitly renames it — the opposite of
 * WorkspaceDescriptorPayload, where `title` is the explicit override. So: an
 * absolute-path-shaped title is still the daemon's own default, not a rename
 * — fall back to `name` for those; a renamed title wins.
 */
function displayName(t: PaseoTerminalInfo): string {
  if (t.title && !t.title.startsWith("/")) return t.title;
  return t.name;
}

function toTerminalInfo(t: PaseoTerminalInfo): TerminalInfo {
  return {
    terminalId: t.id,
    workspaceId: t.workspaceId ?? null,
    name: displayName(t),
    cwd: t.cwd ?? null,
    agentStatus: t.activity?.state ?? null,
  };
}

export class Terminals {
  private paseo: PaseoClient;

  constructor(paseo: PaseoClient) {
    this.paseo = paseo;
  }

  async list(): Promise<TerminalInfo[]> {
    const res = await this.paseo.daemon.listTerminals();
    return (res.terminals ?? []).map((t) => toTerminalInfo(t as PaseoTerminalInfo));
  }

  /**
   * Creates a terminal (spec §19). `cwd` defaults to the bridge's project
   * root when the client omits it — asking for a terminal from XR must never
   * fail merely because no directory was specified.
   *
   * Unlike Herdr, Paseo's create_terminal_request requires a workspaceId
   * (confirmed live against paseo 0.7.2: "workspaceId is required" without
   * one) — there's no bare "just start a shell in this cwd" call. So the
   * bridge resolves one first: reuse an existing workspace already backed by
   * `cwd`, or create a directory-backed workspace for it (spec §19's model —
   * asking for a terminal from XR must never fail merely because no
   * workspace exists yet for that directory).
   */
  async create(opts: { workspaceId?: string; name?: string; cwd?: string }): Promise<TerminalInfo> {
    const cwd = opts.cwd ?? process.cwd();
    const workspaceId = opts.workspaceId ?? (await this.resolveOrCreateWorkspace(cwd));
    const res = await this.paseo.daemon.createTerminal(cwd, opts.name, undefined, {
      workspaceId,
    });
    if (!res.terminal) {
      throw new Error(res.error ?? "Paseo did not return a terminal.");
    }
    return toTerminalInfo(res.terminal as PaseoTerminalInfo);
  }

  private async resolveOrCreateWorkspace(cwd: string): Promise<string> {
    const { entries } = await this.paseo.daemon.fetchWorkspaces({ page: { limit: 200 } });
    const existing = entries.find(
      (e) => e.workspaceDirectory === cwd || e.projectRootPath === cwd,
    );
    if (existing) return existing.id;
    const created = await this.paseo.daemon.createWorkspace({
      source: { kind: "directory", path: cwd },
    });
    if (!created.workspace) {
      throw new Error(created.error ?? `Could not open a Paseo workspace at ${cwd}.`);
    }
    return created.workspace.id;
  }

  async close(terminalId: string): Promise<void> {
    await this.paseo.daemon.killTerminal(terminalId);
  }

  /** No daemon equivalent (Paseo has no server-side terminal focus) — bridge-local ack. */
  async focus(_terminalId: string): Promise<void> {
    return;
  }

  async rename(terminalId: string, name: string): Promise<void> {
    const res = await this.paseo.daemon.renameTerminal({ terminalId, title: name });
    if (!res.success) throw new Error(res.error ?? "Could not rename that terminal.");
  }

  /**
   * `source`/`format` from the old Herdr-shaped request are accepted for
   * protocol compatibility but Paseo's capture is always a plain-text line
   * range; ANSI passthrough and Herdr's "recent_unwrapped" distinction have
   * no equivalent here (see docs/paseo-migration-plan.md risks).
   */
  async read(
    terminalId: string,
    _source: "visible" | "recent" | "recent_unwrapped",
    format: "text" | "ansi",
    lines?: number,
  ): Promise<{ content: string; lines: number; truncated: boolean; revision: number }> {
    const res = await this.paseo.daemon.captureTerminal(terminalId, {
      ...(lines ? { start: -lines } : {}),
      stripAnsi: format === "text",
    });
    const content = res.lines.join("\n");
    return {
      content,
      lines: res.lines.length,
      truncated: res.lines.length < res.totalLines,
      revision: res.totalLines,
    };
  }

  sendText(terminalId: string, text: string): void {
    this.paseo.daemon.sendTerminalInput(terminalId, { type: "input", data: text });
  }

  /** Sends Ctrl+C (0x03) to the terminal's foreground process (spec §20). */
  interrupt(terminalId: string): void {
    this.paseo.daemon.sendTerminalInput(terminalId, { type: "input", data: "\x03" });
  }
}
