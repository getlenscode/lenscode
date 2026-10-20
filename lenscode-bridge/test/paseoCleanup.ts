// Tests that register a throwaway repo with the REAL local Paseo daemon (worktrees.test.ts)
// must take it back out: the daemon keeps every project and terminal it is told about, so
// without this each `npm test` adds a project and a terminal to the developer's Paseo
// (the Home screen's "workspaces"/"panes" counts climbed past 50 that way).
import { realpathSync, rmSync } from "node:fs";
import { DaemonClient } from "@getpaseo/client/internal/daemon-client";

/**
 * Kills terminals and removes projects rooted at exactly one of `dirs`, then deletes the
 * directories. Matching is by path only, so nothing the developer owns can be hit. Every
 * failure is swallowed: cleanup must never fail (or hang) a test run.
 */
export async function removeFixturesFromPaseo(dirs: string[], url = "ws://127.0.0.1:6767/ws"): Promise<void> {
  // macOS temp dirs are reachable as /var/... and /private/var/...; the daemon may use either.
  const roots = new Set(dirs.flatMap((d) => [d, safeRealpath(d)]));
  const client = new DaemonClient({
    url,
    clientId: `lenscode-bridge-test-cleanup-${Date.now()}`,
    clientType: "cli",
  });
  try {
    await client.connect();
    const terminals = await client.listTerminals();
    for (const t of terminals.terminals) {
      if (t.cwd !== undefined && inside(t.cwd, roots)) await client.killTerminal(t.id).catch(() => undefined);
    }
    const projects = await client.listProjects();
    for (const p of projects.projects) {
      if (p.projectRootPath !== undefined && roots.has(p.projectRootPath)) await client.removeProject(p.projectId).catch(() => undefined);
    }
  } catch {
    // Paseo not running or not reachable: nothing was registered, nothing to remove.
  } finally {
    await client.close().catch(() => undefined);
    for (const d of dirs) rmSync(d, { recursive: true, force: true });
  }
}

function inside(cwd: string, roots: Set<string>): boolean {
  for (const r of roots) if (cwd === r || cwd.startsWith(`${r}/`)) return true;
  return false;
}

function safeRealpath(p: string): string {
  try {
    return realpathSync(p);
  } catch {
    return p;
  }
}
