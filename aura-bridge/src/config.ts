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
  herdrSocketPath: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
  const mode = env.AURA_MODE === "prod" ? "prod" : "dev";
  const cfg: BridgeConfig = {
    port: Number(env.AURA_PORT ?? 8137),
    mode,
    tlsKeyPath: env.AURA_TLS_KEY,
    tlsCertPath: env.AURA_TLS_CERT,
    herdrSocketPath:
      env.AURA_HERDR_SOCKET ?? join(homedir(), ".config", "herdr", "herdr.sock"),
  };
  if (cfg.mode === "prod" && (!cfg.tlsKeyPath || !cfg.tlsCertPath)) {
    // Spec §56: production must never silently downgrade to plaintext.
    throw new Error("prod mode requires AURA_TLS_KEY and AURA_TLS_CERT");
  }
  return cfg;
}
