/**
 * Herdr methods the XR client may reach through the herdr.request proxy.
 * Server/plugin/integration control is blocked (docs/herdr-ipc.md, spec §54).
 */
const ALLOWED_PREFIXES = [
  "workspace.",
  "tab.",
  "pane.",
  "agent.",
  "worktree.",
  "layout.",
  "notification.",
] as const;

const ALLOWED_EXACT = new Set([
  "ping",
  "session.snapshot",
  "events.subscribe",
  "new_tab",
  "new_workspace",
  "split",
]);

const BLOCKED_EXACT = new Set([
  // Destructive/system-level even within allowed prefixes.
  "pane.input.set",
]);

export function isHerdrMethodAllowed(method: string): boolean {
  if (BLOCKED_EXACT.has(method)) return false;
  if (ALLOWED_EXACT.has(method)) return true;
  return ALLOWED_PREFIXES.some((p) => method.startsWith(p));
}
