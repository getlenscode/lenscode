/**
 * The commands a local tool (the MCP server) can send the bridge, and the checks they go through.
 * Pure: no I/O, so it is the part to read first and to test.
 */

/** Commands a local tool can send. The ones marked headset need a connected, paired headset. */
export const CONTROL_COMMANDS = [
  "status",
  "list_machines",
  "list_tmux",
  "open_url",
  "open_tmux",
  "open_project",
  "open_file",
  "show_view",
  "notify",
] as const;
export type ControlCommand = (typeof CONTROL_COMMANDS)[number];

/** Commands that are forwarded to the headset as a `control` event. */
export const HEADSET_COMMANDS: readonly ControlCommand[] = [
  "open_url",
  "open_tmux",
  "open_project",
  "open_file",
  "show_view",
  "notify",
];

/** `~` and a path relative to home become absolute under [home]; an absolute path is only tidied. */
export function expandHome(path: string, home: string): string {
  const base = home.replace(/\/+$/, "");
  let out: string;
  if (path === "~") out = base;
  else if (path.startsWith("~/")) out = `${base}/${path.slice(2)}`;
  else if (path.startsWith("/")) out = path;
  else out = `${base}/${path}`;
  return out.length > 1 ? out.replace(/\/+$/, "") : out;
}

/** [value] as one single-quoted shell word, for the commands run on a machine over ssh. */
export function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

export const VIEWS = ["home", "code", "terminal", "agents", "permissions", "diff", "remote", "tasks", "browser", "settings"] as const;

/**
 * A tmux session name safe to type into a shell (`tmux new -A -s <name>`): letters, digits, dot,
 * dash, underscore. Anything else is refused rather than quoted.
 */
export const TMUX_SESSION_NAME = /^[A-Za-z0-9._-]{1,64}$/;

export type ValidControl =
  | { ok: true; command: ControlCommand; args: Record<string, string> }
  | { ok: false; error: string };

function str(v: unknown): string | undefined {
  return typeof v === "string" && v.trim() !== "" ? v.trim() : undefined;
}

/** Checks a request body and keeps only the arguments each command uses. */
export function validateControl(body: unknown): ValidControl {
  if (typeof body !== "object" || body === null) return { ok: false, error: "Expected a JSON object." };
  const { command, args: rawArgs } = body as { command?: unknown; args?: unknown };
  if (typeof command !== "string" || !(CONTROL_COMMANDS as readonly string[]).includes(command)) {
    return { ok: false, error: `Unknown command. Use one of: ${CONTROL_COMMANDS.join(", ")}.` };
  }
  const a = (typeof rawArgs === "object" && rawArgs !== null ? rawArgs : {}) as Record<string, unknown>;
  const machineId = str(a.machineId);
  const args: Record<string, string> = {};
  if (machineId) {
    if (machineId.startsWith("-")) return { ok: false, error: "Invalid machine id." };
    args.machineId = machineId;
  }

  switch (command as ControlCommand) {
    case "status":
    case "list_machines":
      return { ok: true, command: command as ControlCommand, args: {} };
    case "list_tmux":
      return { ok: true, command: "list_tmux", args: { machineId: machineId ?? "local" } };
    case "open_url": {
      const url = str(a.url);
      if (!url) return { ok: false, error: "open_url needs a url." };
      // Only web addresses: no javascript:, file:, intent: or other schemes on the headset.
      const scheme = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(url)?.[1]?.toLowerCase();
      const hostPort = /^[\w.-]+:\d+(\/|$)/.test(url); // "localhost:3000" has no scheme
      if (scheme && !hostPort && scheme !== "http" && scheme !== "https") {
        return { ok: false, error: "Only http and https addresses can be opened." };
      }
      return { ok: true, command: "open_url", args: { url } };
    }
    case "open_tmux": {
      const session = str(a.session);
      if (!session || !TMUX_SESSION_NAME.test(session)) {
        return { ok: false, error: "open_tmux needs a session name of letters, digits, '.', '-' or '_'." };
      }
      return { ok: true, command: "open_tmux", args: { ...args, session } };
    }
    case "open_project": {
      const path = str(a.path);
      if (!machineId) return { ok: false, error: "open_project needs a machine (an SSH host alias)." };
      if (!path) return { ok: false, error: "open_project needs a folder path, e.g. ~/Documents/app." };
      return { ok: true, command: "open_project", args: { machineId, path } };
    }
    case "open_file": {
      const path = str(a.path);
      if (!path) return { ok: false, error: "open_file needs a path." };
      return { ok: true, command: "open_file", args: { ...args, path } };
    }
    case "show_view": {
      const view = str(a.view);
      if (!view || !(VIEWS as readonly string[]).includes(view)) {
        return { ok: false, error: `show_view needs one of: ${VIEWS.join(", ")}.` };
      }
      return { ok: true, command: "show_view", args: { view } };
    }
    case "notify": {
      const message = str(a.message);
      if (!message) return { ok: false, error: "notify needs a message." };
      return { ok: true, command: "notify", args: { message: message.slice(0, 300) } };
    }
  }
}
