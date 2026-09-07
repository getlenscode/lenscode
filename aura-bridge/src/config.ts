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
  /** Test-only: prod pairing enforcement without TLS (AURA_INSECURE_PROD=1). */
  insecureProd?: boolean;
  /** Filesystem root for the "local" workspace's file service. */
  projectRoot: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
  const mode = env.AURA_MODE === "prod" ? "prod" : "dev";
  if (env.AURA_HERDR_SOCKET) {
    // COMPAT: pre-Paseo config key. Warn once and ignore it rather than fail
    // startup, since it no longer maps to anything (docs/paseo-migration-plan.md).
    // eslint-disable-next-line no-console
    console.warn(
      "[aura-bridge] AURA_HERDR_SOCKET is no longer used — aura-bridge now talks to a Paseo daemon. " +
        "Set AURA_PASEO_URL instead (default ws://127.0.0.1:6767/ws).",
    );
  }
  const cfg: BridgeConfig = {
    port: Number(env.AURA_PORT ?? 8137),
    mode,
    tlsKeyPath: env.AURA_TLS_KEY,
    tlsCertPath: env.AURA_TLS_CERT,
    paseoUrl: env.AURA_PASEO_URL ?? "ws://127.0.0.1:6767/ws",
    paseoPassword: env.AURA_PASEO_PASSWORD,
    pairingStorePath:
      env.AURA_PAIRING_STORE ?? join(homedir(), ".config", "aura-bridge", "devices.json"),
    projectRoot: env.AURA_PROJECT_ROOT ?? process.cwd(),
  };
  if (cfg.mode === "prod" && (!cfg.tlsKeyPath || !cfg.tlsCertPath)) {
    // Spec §56: production must never silently downgrade to plaintext.
    // AURA_INSECURE_PROD is an explicit, loudly-logged test-only escape hatch.
    if (env.AURA_INSECURE_PROD !== "1") {
      throw new Error("prod mode requires AURA_TLS_KEY and AURA_TLS_CERT");
    }
    cfg.insecureProd = true;
  }
  return cfg;
}
