/**
 * The Gemini API key for voice routing lives HERE, on the bridge's machine, not
 * in the headset: typing a 39-character key on an XR soft keyboard is miserable,
 * and a key baked into an APK can't be rotated or shared safely. The glasses call
 * Gemini through the bridge's /gemini proxy (see geminiHttp.ts); the key only ever
 * travels from the user's phone/PC browser to this process.
 *
 * Sources, highest precedence first: the file written by the /setup page, then
 * the GEMINI_API_KEY env var. Persisted with mode 0600 like the pairing registry.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, rmSync } from "node:fs";
import { dirname } from "node:path";
import type { GeminiStatus } from "@lenscode/protocol";
import { log } from "./log.ts";

export type { GeminiStatus };

interface StoredGeminiKey {
  apiKey: string;
  model?: string;
}

export class GeminiKeyStore {
  private stored: StoredGeminiKey | null = null;
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

  status(): GeminiStatus {
    if (this.stored) {
      return { configured: true, source: "file", model: this.stored.model ?? null };
    }
    if (this.env.apiKey) {
      return { configured: true, source: "env", model: this.env.model || null };
    }
    return { configured: false, source: null, model: null };
  }

  set(apiKey: string, model?: string): void {
    this.stored = { apiKey, ...(model ? { model } : {}) };
    mkdirSync(dirname(this.storePath), { recursive: true });
    writeFileSync(this.storePath, JSON.stringify(this.stored, null, 2), { mode: 0o600 });
    log("info", "gemini key saved", { model: model ?? null });
  }

  /** Removes the file-based key; an env-var key (if any) takes over again. */
  clear(): void {
    this.stored = null;
    rmSync(this.storePath, { force: true });
    log("info", "gemini key cleared");
  }

  private load(): void {
    if (!existsSync(this.storePath)) return;
    try {
      const data = JSON.parse(readFileSync(this.storePath, "utf8")) as Partial<StoredGeminiKey>;
      if (typeof data.apiKey === "string" && data.apiKey.length > 0) {
        this.stored = {
          apiKey: data.apiKey,
          ...(typeof data.model === "string" && data.model ? { model: data.model } : {}),
        };
      }
    } catch (e) {
      log("warn", "could not load gemini key store", { error: String(e) });
    }
  }
}

export type KeyCheck = { ok: true } | { ok: false; message: string };

/**
 * Cheap pre-save check: listing models costs no generation quota and fails with
 * 400 API_KEY_INVALID on a bad key. Network failure is reported separately so
 * "typo in the key" and "the PC is offline" read differently.
 */
export async function checkGeminiKey(
  apiKey: string,
  upstream: string,
  fetchImpl: typeof fetch = fetch,
): Promise<KeyCheck> {
  let res: Response;
  try {
    res = await fetchImpl(`${upstream}/v1beta/models?pageSize=1`, {
      headers: { "x-goog-api-key": apiKey },
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    return {
      ok: false,
      message: "Could not reach Google to check the key. Is the bridge machine online?",
    };
  }
  if (res.ok) return { ok: true };
  if (res.status === 400 || res.status === 401 || res.status === 403) {
    return { ok: false, message: "Google rejected that key. Check it was copied completely." };
  }
  return { ok: false, message: `Google returned HTTP ${res.status} while checking the key.` };
}
