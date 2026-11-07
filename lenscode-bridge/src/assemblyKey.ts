/**
 * The AssemblyAI API key for streaming speech-to-text lives on the bridge, like the OpenRouter key
 * (see openrouterKey.ts): the headset asks the bridge for a short-lived streaming token and opens the
 * AssemblyAI WebSocket with that, so the permanent key never reaches the glasses or a URL.
 *
 * Sources, highest precedence first: the file written by the /setup page, then ASSEMBLYAI_API_KEY.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, rmSync } from "node:fs";
import { dirname } from "node:path";
import { log } from "./log.ts";

export const ASSEMBLY_STREAMING_UPSTREAM = "https://streaming.assemblyai.com";
/**
 * Universal-2 is AssemblyAI's pre-recorded model; the streaming API serves Universal-Streaming
 * (English / multilingual) and the newer universal-3-*-pro models.
 */
export const DEFAULT_ASSEMBLY_MODEL = "universal-streaming-english";
/** Long enough to open the socket, short enough that a leaked token is useless. */
const TOKEN_REDEEM_SECONDS = 60;

export interface AssemblyStatus {
  configured: boolean;
  source: "file" | "env" | null;
  model: string;
}

interface StoredAssemblyKey {
  apiKey: string;
  model?: string;
}

export class AssemblyKeyStore {
  private stored: StoredAssemblyKey | null = null;
  private storePath: string;
  private env: { apiKey?: string; model?: string };

  constructor(storePath: string, env: { apiKey?: string; model?: string } = {}) {
    this.storePath = storePath;
    this.env = env;
    this.load();
  }

  /** The real key, for minting tokens only — never sent to a client or logged. */
  apiKey(): string | undefined {
    return this.stored?.apiKey ?? (this.env.apiKey || undefined);
  }

  model(): string {
    return this.stored?.model || this.env.model || DEFAULT_ASSEMBLY_MODEL;
  }

  status(): AssemblyStatus {
    const model = this.model();
    if (this.stored) return { configured: true, source: "file", model };
    if (this.env.apiKey) return { configured: true, source: "env", model };
    return { configured: false, source: null, model };
  }

  set(apiKey: string, model?: string): void {
    this.stored = { apiKey, ...(model ? { model } : {}) };
    mkdirSync(dirname(this.storePath), { recursive: true });
    writeFileSync(this.storePath, JSON.stringify(this.stored, null, 2), { mode: 0o600 });
    log("info", "assemblyai key saved", { model: model ?? null });
  }

  clear(): void {
    this.stored = null;
    rmSync(this.storePath, { force: true });
    log("info", "assemblyai key cleared");
  }

  private load(): void {
    if (!existsSync(this.storePath)) return;
    try {
      const data = JSON.parse(readFileSync(this.storePath, "utf8")) as Partial<StoredAssemblyKey>;
      if (typeof data.apiKey === "string" && data.apiKey.length > 0) {
        this.stored = {
          apiKey: data.apiKey,
          ...(typeof data.model === "string" && data.model ? { model: data.model } : {}),
        };
      }
    } catch (e) {
      log("warn", "could not load assemblyai key store", { error: String(e) });
    }
  }
}

export type TokenResult =
  | { ok: true; token: string }
  | { ok: false; unreachable: boolean; message: string };

/**
 * Asks AssemblyAI for a temporary streaming token. This doubles as the "is the key valid" check
 * when it is saved: it costs nothing and a bad key comes back as 401.
 */
export async function mintAssemblyToken(
  apiKey: string,
  upstream: string = ASSEMBLY_STREAMING_UPSTREAM,
  fetchImpl: typeof fetch = fetch,
): Promise<TokenResult> {
  let res: Response;
  try {
    res = await fetchImpl(`${upstream}/v3/token?expires_in_seconds=${TOKEN_REDEEM_SECONDS}`, {
      headers: { authorization: apiKey },
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    return {
      ok: false,
      unreachable: true,
      message: "Could not reach AssemblyAI. Is the bridge machine online?",
    };
  }
  if (res.status === 401 || res.status === 403) {
    return {
      ok: false,
      unreachable: false,
      message: "AssemblyAI rejected that key. Check it was copied completely.",
    };
  }
  if (!res.ok) {
    return { ok: false, unreachable: false, message: `AssemblyAI returned HTTP ${res.status}.` };
  }
  const body = (await res.json().catch(() => null)) as { token?: unknown } | null;
  if (typeof body?.token !== "string" || !body.token) {
    return { ok: false, unreachable: false, message: "AssemblyAI sent no token." };
  }
  return { ok: true, token: body.token };
}
