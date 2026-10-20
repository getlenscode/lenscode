import { homedir } from "node:os";
import { join } from "node:path";

export interface BridgeConfig {
  /** WS listen port. */
  port: number;
  /** "dev" allows ws:// and auto-trusts devices; "prod" requires TLS + pairing. */
  mode: "dev" | "prod";
  /** Paths to TLS key/cert (required in prod). */
  tlsKeyPath?: string;
  tlsCertPath?: string;
  /** Paseo daemon WebSocket URL, e.g. ws://127.0.0.1:6767/ws (docs/paseo-migration-plan.md). */
  paseoUrl: string;
  /** Set when the Paseo daemon has a password configured. */
  paseoPassword?: string;
  /** Persistent store for paired devices. */
  pairingStorePath: string;
  /** Test-only: prod pairing enforcement without TLS (LENSCODE_INSECURE_PROD=1). */
  insecureProd?: boolean;
  /** Filesystem root for the "local" workspace's file service. */
  projectRoot: string;
  /** Where the /setup page persists the Gemini key (mode 0600). */
  geminiStorePath: string;
  /** Fallback key from GEMINI_API_KEY; a key saved via /setup wins over it. */
  geminiApiKey?: string;
  geminiModel?: string;
  /** Test-only: stub for generativelanguage.googleapis.com. */
  geminiUpstream?: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
  const mode = env.LENSCODE_MODE === "prod" ? "prod" : "dev";
  if (env.LENSCODE_HERDR_SOCKET) {
    // COMPAT: pre-Paseo config key. Warn once and ignore it rather than fail
    // startup, since it no longer maps to anything (docs/paseo-migration-plan.md).
    // eslint-disable-next-line no-console
    console.warn(
      "[lenscode-bridge] LENSCODE_HERDR_SOCKET is no longer used — lenscode-bridge now talks to a Paseo daemon. " +
        "Set LENSCODE_PASEO_URL instead (default ws://127.0.0.1:6767/ws).",
    );
  }
  const cfg: BridgeConfig = {
    port: Number(env.LENSCODE_PORT ?? 8137),
    mode,
    tlsKeyPath: env.LENSCODE_TLS_KEY,
    tlsCertPath: env.LENSCODE_TLS_CERT,
    paseoUrl: env.LENSCODE_PASEO_URL ?? "ws://127.0.0.1:6767/ws",
    paseoPassword: env.LENSCODE_PASEO_PASSWORD,
    pairingStorePath:
      env.LENSCODE_PAIRING_STORE ?? join(homedir(), ".config", "lenscode-bridge", "devices.json"),
    projectRoot: env.LENSCODE_PROJECT_ROOT ?? process.cwd(),
    geminiStorePath:
      env.LENSCODE_GEMINI_STORE ?? join(homedir(), ".config", "lenscode-bridge", "gemini.json"),
    geminiApiKey: env.GEMINI_API_KEY || undefined,
    geminiModel: env.LENSCODE_GEMINI_MODEL || undefined,
    geminiUpstream: env.LENSCODE_GEMINI_UPSTREAM || undefined,
  };
  if (cfg.mode === "prod" && (!cfg.tlsKeyPath || !cfg.tlsCertPath)) {
    // Spec §56: production must never silently downgrade to plaintext.
    // LENSCODE_INSECURE_PROD is an explicit, loudly-logged test-only escape hatch.
    if (env.LENSCODE_INSECURE_PROD !== "1") {
      throw new Error("prod mode requires LENSCODE_TLS_KEY and LENSCODE_TLS_CERT");
    }
    cfg.insecureProd = true;
  }
  return cfg;
}
