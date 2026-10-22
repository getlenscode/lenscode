/**
 * Lists the tmux sessions open on a machine, so the XR client can offer them as something to
 * attach to a prompt. tmux has no daemon API here — it is just the `tmux` command, run on the
 * machine itself (over the same key-based ssh the remote support already uses).
 */
import { spawn } from "node:child_process";

export interface TmuxSession {
  name: string;
  windows: number;
  attached: boolean;
}

// Fields separated by "|" (a session name can't contain one: tmux turns it into "_").
const FORMAT = "#{session_name}|#{session_windows}|#{session_attached}";

/** The shell command: tmux is often in /opt/homebrew/bin, which a non-login ssh shell lacks. */
export const TMUX_LIST_COMMAND =
  `PATH="$PATH:/opt/homebrew/bin:/usr/local/bin:/usr/bin" tmux list-sessions -F '${FORMAT}' 2>&1`;

/** Turns `tmux list-sessions -F` output into sessions; error lines ("no server running…") are skipped. */
export function parseTmuxSessions(output: string): TmuxSession[] {
  const sessions: TmuxSession[] = [];
  for (const line of output.split("\n")) {
    const parts = line.trim().split("|");
    if (parts.length !== 3 || parts[0] === "") continue;
    const windows = Number(parts[1]);
    if (!Number.isInteger(windows)) continue;
    sessions.push({ name: parts[0], windows, attached: Number(parts[2]) > 0 });
  }
  return sessions;
}

/** Runs [TMUX_LIST_COMMAND] on this machine. A missing tmux or no server just means no sessions. */
export function runLocalTmuxList(timeoutMs = 5000): Promise<string> {
  return new Promise((resolve) => {
    const proc = spawn("sh", ["-c", TMUX_LIST_COMMAND]);
    let out = "";
    proc.stdout.on("data", (d) => (out += d));
    const timer = setTimeout(() => proc.kill(), timeoutMs);
    proc.on("error", () => resolve(""));
    proc.on("close", () => {
      clearTimeout(timer);
      resolve(out);
    });
  });
}
