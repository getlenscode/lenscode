/**
 * The OpenRouter API key for voice command routing lives HERE, on the bridge's machine, not
 * in the headset: typing a long key on an XR soft keyboard is miserable, and a key baked into
 * an APK can't be rotated or shared safely. The glasses call OpenRouter through the bridge's
 * /openrouter proxy (see setupHttp.ts); the key only ever travels from the user's phone/PC
 * browser to this process.
 *
 * Sources, highest precedence first: the file written by the /setup page, then the
 * OPENROUTER_API_KEY env var. Persisted with mode 0600 like the pairing registry.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, rmSync } from "node:fs";
import { dirname } from "node:path";
import type { OpenRouterStatus } from "@lenscode/protocol";
import { log } from "./log.ts";

export type { OpenRouterStatus };

/** The model used when none is chosen on the setup page. */
export const DEFAULT_OPENROUTER_MODEL = "z-ai/glm-5.3-flash";

interface StoredOpenRouterKey {
  apiKey: string;
  model?: string;
}

export class OpenRouterKeyStore {
  private stored: StoredOpenRouterKey | null = null;
  private storePath: string;
  private env: { apiKey?: string; model?: string };

  constructor(storePath: string, env: { apiKey?: string; model?: string } = {}) {
    this.storePath = storePath;
    this.env = env;
    this.load();
  }

  /** The real key, for the proxy only — never sent to a client or logged. */
  apiKey(): string | undefined {
    return this.stored?.apiKey ?? (this.env.apiKey || undefined);
  }

  model(): string {
    return this.stored?.model || this.env.model || DEFAULT_OPENROUTER_MODEL;
  }

  status(): OpenRouterStatus {
    const model = this.model();
    if (this.stored) return { configured: true, source: "file", model };
    if (this.env.apiKey) return { configured: true, source: "env", model };
    return { configured: false, source: null, model };
  }

  set(apiKey: string, model?: string): void {
    this.stored = { apiKey, ...(model ? { model } : {}) };
    mkdirSync(dirname(this.storePath), { recursive: true });
    writeFileSync(this.storePath, JSON.stringify(this.stored, null, 2), { mode: 0o600 });
    log("info", "openrouter key saved", { model: model ?? null });
  }

  /** Removes the file-based key; an env-var key (if any) takes over again. */
  clear(): void {
    this.stored = null;
    rmSync(this.storePath, { force: true });
    log("info", "openrouter key cleared");
  }

  private load(): void {
    if (!existsSync(this.storePath)) return;
    try {
      const data = JSON.parse(readFileSync(this.storePath, "utf8")) as Partial<StoredOpenRouterKey>;
      if (typeof data.apiKey === "string" && data.apiKey.length > 0) {
        this.stored = {
          apiKey: data.apiKey,
          ...(typeof data.model === "string" && data.model ? { model: data.model } : {}),
        };
      }
    } catch (e) {
      log("warn", "could not load openrouter key store", { error: String(e) });
    }
  }
}

export type KeyCheck = { ok: true } | { ok: false; message: string };

/**
 * Cheap pre-save check: GET /auth/key costs nothing and answers 401 for a bad key. A network
 * failure is reported separately so "typo in the key" and "the PC is offline" read differently.
 */
export async function checkOpenRouterKey(
  apiKey: string,
  upstream: string,
  fetchImpl: typeof fetch = fetch,
): Promise<KeyCheck> {
  let res: Response;
  try {
    res = await fetchImpl(`${upstream}/auth/key`, {
      headers: { authorization: `Bearer ${apiKey}` },
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    return {
      ok: false,
      message: "Could not reach OpenRouter to check the key. Is the bridge machine online?",
    };
  }
  if (res.ok) return { ok: true };
  if (res.status === 401 || res.status === 403) {
    return { ok: false, message: "OpenRouter rejected that key. Check it was copied completely." };
  }
  return { ok: false, message: `OpenRouter returned HTTP ${res.status} while checking the key.` };
}
