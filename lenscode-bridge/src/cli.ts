/**
 * Command-line flags for `lenscode-bridge` (and `npm run dev -- --port 9000`).
 * Flags are translated into the LENSCODE_* environment variables that loadConfig()
 * already understands, so there is one config path and env-only setups (the
 * setup script, tests, systemd units) keep working unchanged.
 */

export const USAGE = `lenscode-bridge — connects the LensCode headset app to your Paseo agents.

Usage: lenscode-bridge [options]

  --port <n>          port to listen on (default 8137; 8443 in secure mode)
  --secure            secure mode with a certificate the bridge makes itself: wss://, and a
                      headset pairs with a code and confirms the certificate fingerprint once.
                      No domain or certificate authority needed.
  --tls-cert <file>   TLS certificate (PEM, full chain) from a public authority. With --tls-key
  --tls-key <file>    this also turns on secure mode.
  --paseo-url <url>   Paseo daemon WebSocket (default ws://127.0.0.1:6767/ws)
  --project-root <d>  folder the "local" workspace's files are served from (default: cwd)
  -v, --version       print the version
  -h, --help          show this help

Without TLS flags the bridge runs in dev mode: plain ws://, open to your local network.
That is fine for a debug build of the app; a store build only connects over wss://
(use --secure, or your own certificate).

Environment: LENSCODE_PORT, LENSCODE_MODE, LENSCODE_TLS_CERT, LENSCODE_TLS_KEY,
LENSCODE_PASEO_URL, LENSCODE_PASEO_PASSWORD, GEMINI_API_KEY, LENSCODE_SECURE=1.
`;

const VALUE_FLAGS: Record<string, string> = {
  "--port": "LENSCODE_PORT",
  "--tls-cert": "LENSCODE_TLS_CERT",
  "--tls-key": "LENSCODE_TLS_KEY",
  "--paseo-url": "LENSCODE_PASEO_URL",
  "--project-root": "LENSCODE_PROJECT_ROOT",
};

export type CliResult =
  | { action: "run" }
  | { action: "exit"; code: number; message: string };

/**
 * Applies `argv` (without node and the script name) to `env`. Returns what main should
 * do: run the bridge, or print a message and exit (help, version, or a usage error).
 */
export function applyCliArgs(argv: string[], env: NodeJS.ProcessEnv, version: string): CliResult {
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--secure") {
      env.LENSCODE_SECURE = "1";
      continue;
    }
    if (arg === "-h" || arg === "--help") return { action: "exit", code: 0, message: USAGE };
    if (arg === "-v" || arg === "--version") return { action: "exit", code: 0, message: `${version}\n` };

    // Accept both "--port 9000" and "--port=9000".
    const [flag, inline] = arg.includes("=") ? [arg.slice(0, arg.indexOf("=")), arg.slice(arg.indexOf("=") + 1)] : [arg, undefined];
    const envName = VALUE_FLAGS[flag];
    if (!envName) {
      return { action: "exit", code: 2, message: `Unknown option: ${arg}\n\n${USAGE}` };
    }
    const value = inline ?? argv[++i];
    if (value === undefined || value === "") {
      return { action: "exit", code: 2, message: `${flag} needs a value.\n` };
    }
    env[envName] = value;
  }

  if (env.LENSCODE_PORT !== undefined && !/^\d{1,5}$/.test(env.LENSCODE_PORT)) {
    return { action: "exit", code: 2, message: `--port must be a number, got "${env.LENSCODE_PORT}".\n` };
  }
  const hasCert = Boolean(env.LENSCODE_TLS_CERT);
  const hasKey = Boolean(env.LENSCODE_TLS_KEY);
  if (env.LENSCODE_SECURE === "1" && (hasCert || hasKey)) {
    return { action: "exit", code: 2, message: "--secure makes its own certificate; don't combine it with --tls-cert/--tls-key.\n" };
  }
  if (hasCert !== hasKey) {
    return { action: "exit", code: 2, message: "--tls-cert and --tls-key must be given together.\n" };
  }
  // Giving a certificate means the user wants the secure setup; don't make them also know about a mode.
  if ((hasCert && hasKey) || env.LENSCODE_SECURE === "1") {
    if (env.LENSCODE_MODE === undefined) env.LENSCODE_MODE = "prod";
  }
  return { action: "run" };
}
