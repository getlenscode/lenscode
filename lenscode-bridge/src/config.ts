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

/**
 * Plain ws:// and secure wss:// get different default ports (like 80 and 443), so the two can run
 * side by side and a user who types only the bridge's IP into the app reaches the right one: the
 * app likewise assumes 8443 for a secure address and 8137 for a plain one.
 */
export function defaultPort(mode: "dev" | "prod"): number {
  return mode === "prod" ? 8443 : 8137;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
  const mode = env.LENSCODE_MODE === "prod" ? "prod" : "dev";
  const cfg: BridgeConfig = {
    port: Number(env.LENSCODE_PORT ?? defaultPort(mode)),
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
