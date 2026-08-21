/**
 * Terminal subsystem (spec §18–21). Herdr owns every PTY, tab, and pane
 * (spec §4.1) — this module only translates XR-shaped operations into Herdr
 * pane/tab calls and normalizes the results for the client.
 */
import type { HerdrClient } from "./herdr.ts";

export interface TerminalInfo {
  paneId: string;
  tabId: string;
  workspaceId: string;
  name: string;
  cwd: string | null;
  focused: boolean;
  agentStatus: string | null;
}

interface HerdrPane {
  pane_id: string;
  tab_id: string;
  workspace_id: string;
  cwd?: string;
  foreground_cwd?: string;
  focused?: boolean;
  agent_status?: string;
}

interface HerdrTab {
  tab_id: string;
  label?: string;
}

export class Terminals {
  private herdr: HerdrClient;

  constructor(herdr: HerdrClient) {
    this.herdr = herdr;
  }

  async list(): Promise<TerminalInfo[]> {
    const [panesRes, tabsRes] = await Promise.all([
      this.herdr.request("pane.list", {}) as Promise<{ panes?: HerdrPane[] }>,
      this.herdr.request("tab.list", {}) as Promise<{ tabs?: HerdrTab[] }>,
    ]);
    const tabLabels = new Map(
      (tabsRes.tabs ?? []).map((t) => [t.tab_id, t.label ?? t.tab_id]),
    );
    return (panesRes.panes ?? []).map((p) => ({
      paneId: p.pane_id,
      tabId: p.tab_id,
      workspaceId: p.workspace_id,
      name: tabLabels.get(p.tab_id) ?? p.pane_id,
      cwd: p.foreground_cwd ?? p.cwd ?? null,
      focused: p.focused ?? false,
      agentStatus: p.agent_status ?? null,
    }));
  }

  /**
   * Creates a terminal as a new Herdr tab (spec §19 tab model). If Herdr has no
   * workspace yet, one is created first — asking for a terminal from XR must
   * never fail merely because the session is empty.
   */
  async create(opts: { workspaceId?: string; name?: string; cwd?: string }): Promise<TerminalInfo> {
    let workspaceId = opts.workspaceId;
    if (!workspaceId) {
      const list = (await this.herdr.request("workspace.list", {})) as {
        workspaces?: Array<{ workspace_id: string; focused?: boolean }>;
      };
      const existing = list.workspaces ?? [];
      if (existing.length === 0) {
        const ws = (await this.herdr.request("workspace.create", {
          focus: true,
          ...(opts.cwd ? { cwd: opts.cwd } : {}),
        })) as { workspace?: { workspace_id: string }; root_pane?: HerdrPane };
        workspaceId = ws.workspace?.workspace_id;
        // workspace.create already yields a tab + root pane; name and return it.
        const paneId = ws.root_pane?.pane_id;
        if (paneId) {
          if (opts.name) await this.rename(paneId, opts.name);
          const info = (await this.list()).find((t) => t.paneId === paneId);
          if (info) return info;
        }
      } else {
        workspaceId = (existing.find((w) => w.focused) ?? existing[0]).workspace_id;
      }
    }

    const created = (await this.herdr.request("tab.create", {
      ...(workspaceId ? { workspace_id: workspaceId } : {}),
      ...(opts.cwd ? { cwd: opts.cwd } : {}),
      ...(opts.name ? { label: opts.name } : {}),
      focus: true,
    })) as { tab?: HerdrTab; root_pane?: HerdrPane };
    const paneId = created.root_pane?.pane_id;
    if (!paneId) throw new Error("Herdr did not return a pane for the new tab.");
    const all = await this.list();
    const info = all.find((t) => t.paneId === paneId);
    if (!info) throw new Error("New terminal did not appear in the pane list.");
    return info;
  }

  async close(paneId: string): Promise<void> {
    await this.herdr.request("pane.close", { pane_id: paneId });
  }

  async focus(paneId: string): Promise<void> {
    await this.herdr.request("pane.focus", { pane_id: paneId });
  }

  async rename(paneId: string, name: string): Promise<void> {
    const panes = (await this.herdr.request("pane.list", {})) as { panes?: HerdrPane[] };
    const pane = panes.panes?.find((p) => p.pane_id === paneId);
    if (!pane) throw new Error("That terminal no longer exists.");
    await this.herdr.request("tab.rename", { tab_id: pane.tab_id, name });
  }

  async read(
    paneId: string,
    source: "visible" | "recent" | "recent_unwrapped",
    format: "text" | "ansi",
    lines?: number,
  ): Promise<{ content: string; lines: number; truncated: boolean; revision: number }> {
    const res = (await this.herdr.request("pane.read", {
      pane_id: paneId,
      source,
      format,
      strip_ansi: format === "text",
      ...(lines ? { lines } : {}),
    })) as { read?: { text?: string; truncated?: boolean; revision?: number } };
    const content = res.read?.text ?? "";
    return {
      content,
      lines: content ? content.split("\n").length : 0,
      truncated: res.read?.truncated ?? false,
      revision: res.read?.revision ?? 0,
    };
  }

  async sendText(paneId: string, text: string): Promise<void> {
    await this.herdr.request("pane.send_text", { pane_id: paneId, text });
  }

  /**
   * Sends Ctrl+C to the pane's foreground process. Herdr validates key names
   * before writing bytes; "ctrl+c" is its documented interrupt chord.
   */
  async interrupt(paneId: string): Promise<void> {
    await this.herdr.request("pane.send_keys", { pane_id: paneId, keys: ["ctrl+c"] });
  }

  /** Foreground process detail for the "Stop process?" confirmation card. */
  async processInfo(paneId: string): Promise<unknown> {
    return this.herdr.request("pane.process_info", { pane_id: paneId });
  }
}
