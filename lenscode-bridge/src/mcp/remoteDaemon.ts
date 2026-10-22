/**
 * Getting a remote machine's Paseo daemon running when a tool asks to open a project there.
 *
 * The SSH tunnel can come up while nothing answers on the other end because `paseo daemon` isn't
 * running. For `open_project` (and only that — a plain machine.connect still just reports it) the
 * bridge starts the daemon over the same key-based ssh and connects again.
 */

/** Seen in the error from RemoteManager.connect when the tunnel is up but Paseo doesn't answer. */
export function isPaseoUnreachable(e: unknown): boolean {
  return (e instanceof Error ? e.message : String(e)).includes("Paseo isn't reachable");
}

const NOT_INSTALLED = "NO_PASEO";

/**
 * Run on the machine over ssh: finds `paseo` (npm global bins and nvm are not on a non-interactive
 * ssh PATH) and starts its daemon. Prints NO_PASEO, and exits 0 so the output comes back, when
 * there is no `paseo` to start.
 */
export const PASEO_START_COMMAND =
  `bash -lc '` +
  `export PATH="$PATH:$HOME/.local/bin:$HOME/.npm-global/bin:$HOME/.volta/bin:/usr/local/bin:/opt/homebrew/bin"; ` +
  `[ -s "$HOME/.nvm/nvm.sh" ] && . "$HOME/.nvm/nvm.sh" >/dev/null 2>&1; ` +
  `command -v paseo >/dev/null 2>&1 || { echo ${NOT_INSTALLED}; exit 0; }; ` +
  `paseo daemon start 2>&1'`;

export type StartResult = { ok: true } | { ok: false; reason: string };

export async function startRemotePaseo(
  machineId: string,
  run: (machineId: string, command: string) => Promise<string>,
): Promise<StartResult> {
  let output: string;
  try {
    output = await run(machineId, PASEO_START_COMMAND);
  } catch (e) {
    return { ok: false, reason: `Couldn't start Paseo on ${machineId}: ${e instanceof Error ? e.message : String(e)}` };
  }
  if (output.includes(NOT_INSTALLED)) {
    return {
      ok: false,
      reason: `Paseo isn't installed on ${machineId}. Install it there (npm install -g @getpaseo/cli) and try again.`,
    };
  }
  return { ok: true };
}

export interface ConnectDeps {
  connect: (machineId: string) => Promise<void>;
  run: (machineId: string, command: string) => Promise<string>;
  sleep: (ms: number) => Promise<void>;
}

export type ConnectResult =
  | { ok: true; startedPaseo: boolean }
  | { ok: false; status: number; error: string };

/**
 * Connects [machineId]; when the tunnel is up but Paseo isn't answering, starts the daemon there and
 * connects again. Any other failure (ssh itself, the registry) is reported as it is.
 */
export async function connectStartingPaseo(machineId: string, deps: ConnectDeps): Promise<ConnectResult> {
  const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
  try {
    await deps.connect(machineId);
    return { ok: true, startedPaseo: false };
  } catch (first) {
    if (!isPaseoUnreachable(first)) {
      return { ok: false, status: 502, error: `Couldn't connect to ${machineId}: ${message(first)}` };
    }
  }

  const started = await startRemotePaseo(machineId, deps.run);
  if (!started.ok) return { ok: false, status: 502, error: started.reason };

  await deps.sleep(2000); // the daemon needs a moment to open its port
  try {
    await deps.connect(machineId);
    return { ok: true, startedPaseo: true };
  } catch (second) {
    return {
      ok: false,
      status: 502,
      error: `Started Paseo on ${machineId}, but it still isn't answering: ${message(second)}. Check ~/.paseo/daemon.log there.`,
    };
  }
}
