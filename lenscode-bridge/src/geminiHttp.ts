/**
 * HTTP side of the Gemini key feature (the WebSocket server owns everything else):
 *
 *   GET/POST /setup     a small page, opened from a phone or PC browser, where the
 *                       user pastes the key once. The headset's Settings panel only
 *                       shows this URL — no typing in AR.
 *   ANY /gemini/v1beta/* a pass-through to generativelanguage.googleapis.com that
 *                       swaps in the stored key, so the headset never holds it.
 *
 * Trust follows the WebSocket rules (spec §55): dev mode trusts the LAN; prod mode
 * needs a paired device's token for the proxy and the console-printed setup code
 * for the page (the same "approve on the desktop" idea as pairing codes).
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { networkInterfaces } from "node:os";
import { randomInt, timingSafeEqual } from "node:crypto";
import { Readable } from "node:stream";
import { log } from "./log.ts";
import { checkGeminiKey, type GeminiKeyStore, type GeminiStatus } from "./geminiKey.ts";

export const GEMINI_UPSTREAM = "https://generativelanguage.googleapis.com";
const MAX_BODY_BYTES = 2 * 1024 * 1024;
const MAX_SETUP_BODY_BYTES = 8 * 1024;
const MAX_CODE_FAILURES = 5;
const PROXY_TIMEOUT_MS = 60_000;

export interface GeminiHttpOptions {
  store: GeminiKeyStore;
  mode: "dev" | "prod";
  /** Base URL requests are forwarded to; overridable so tests can stub Google. */
  upstream?: string;
  /** prod only: does this device id + token belong to a paired, unrevoked device? */
  authorizeDevice: (deviceId: string, token: string | undefined) => boolean;
  /** Called after the stored key changes, so connected headsets can refresh. */
  onChange: (status: GeminiStatus) => void;
  fetchImpl?: typeof fetch;
}

export class GeminiHttp {
  private opts: GeminiHttpOptions;
  private upstream: string;
  private fetchImpl: typeof fetch;
  private setupCode: string | null = null;
  private codeFailures = 0;

  constructor(opts: GeminiHttpOptions) {
    this.opts = opts;
    this.upstream = opts.upstream ?? GEMINI_UPSTREAM;
    this.fetchImpl = opts.fetchImpl ?? fetch;
    if (opts.mode === "prod") this.rotateCode();
  }

  /** Test hook: the current prod setup code (never exposed over the network). */
  peekSetupCode(): string | null {
    return this.setupCode;
  }

  /** Returns true if the request was one of ours. */
  handle(req: IncomingMessage, res: ServerResponse): boolean {
    const url = new URL(req.url ?? "/", "http://bridge.local");
    if (url.pathname === "/setup") {
      this.setup(req, res).catch((e) => this.fail(res, 500, `Setup failed: ${String(e)}`));
      return true;
    }
    if (url.pathname.startsWith("/gemini/")) {
      this.proxy(req, res, url).catch((e) => {
        log("warn", "gemini proxy failed", { error: String(e) });
        this.sendJson(res, 502, geminiError(502, "Could not reach Google from the bridge."));
      });
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // /setup
  // ---------------------------------------------------------------------------

  private async setup(req: IncomingMessage, res: ServerResponse): Promise<void> {
    if (req.method === "GET") {
      this.sendHtml(res, 200, this.renderPage());
      return;
    }
    if (req.method !== "POST") {
      res.writeHead(405, { allow: "GET, POST" }).end();
      return;
    }
    // A page on another site could auto-submit a form to this LAN address.
    const origin = req.headers.origin;
    if (origin && safeHost(origin) !== req.headers.host) {
      this.sendHtml(res, 403, this.renderPage({ error: "Cross-site submission refused." }));
      return;
    }
    const form = new URLSearchParams(await readBody(req, MAX_SETUP_BODY_BYTES));

    if (this.opts.mode === "prod" && !this.codeMatches(form.get("code") ?? "")) {
      this.codeFailures++;
      if (this.codeFailures >= MAX_CODE_FAILURES) this.rotateCode();
      this.sendHtml(
        res,
        403,
        this.renderPage({ error: "That setup code is not correct. Check the bridge console." }),
      );
      return;
    }

    if (form.get("action") === "clear") {
      this.opts.store.clear();
      this.afterChange();
      this.sendHtml(res, 200, this.renderPage({ ok: "The saved key was removed." }));
      return;
    }

    const apiKey = (form.get("apiKey") ?? "").trim();
    const model = (form.get("model") ?? "").trim();
    if (!apiKey) {
      this.sendHtml(res, 400, this.renderPage({ error: "Paste your Gemini API key first." }));
      return;
    }
    if (!/^[\w.-]*$/.test(model)) {
      this.sendHtml(res, 400, this.renderPage({ error: "That model name has odd characters in it." }));
      return;
    }
    const check = await checkGeminiKey(apiKey, this.upstream, this.fetchImpl);
    if (!check.ok) {
      this.sendHtml(res, 400, this.renderPage({ error: check.message }));
      return;
    }
    this.opts.store.set(apiKey, model || undefined);
    this.afterChange();
    this.sendHtml(
      res,
      200,
      this.renderPage({ ok: "Key saved. Voice routing is now active — you can close this page." }),
    );
  }

  private afterChange(): void {
    if (this.opts.mode === "prod") this.rotateCode();
    this.opts.onChange(this.opts.store.status());
  }

  private codeMatches(given: string): boolean {
    if (!this.setupCode) return false;
    const a = Buffer.from(this.setupCode);
    const b = Buffer.from(given.padEnd(a.length).slice(0, a.length));
    return given.length === a.length && timingSafeEqual(a, b);
  }

  private rotateCode(): void {
    this.setupCode = String(randomInt(0, 1_000_000)).padStart(6, "0");
    this.codeFailures = 0;
    log("info", "GEMINI SETUP CODE — enter this on the /setup page", { code: this.setupCode });
  }

  private renderPage(msg: { ok?: string; error?: string } = {}): string {
    const s = this.opts.store.status();
    const status = s.configured
      ? `Active — key from ${s.source === "file" ? "this page" : "the GEMINI_API_KEY environment variable"}` +
        (s.model ? `, model ${s.model}` : "")
      : "Not set — voice commands use the built-in matcher only";
    const prod = this.opts.mode === "prod";
    return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LensCode · Gemini key</title>
<style>
  :root { --bg:#fff; --fg:#14171f; --muted:#5b6475; --card:#f4f6fa; --line:#d6dbe6; --accent:#0a7c8c; --ok:#1a7f4b; --bad:#b3261e; }
  @media (prefers-color-scheme: dark) { :root { --bg:#0f1218; --fg:#e8ecf4; --muted:#9aa4b8; --card:#181d27; --line:#2b3345; --accent:#38bdd0; --ok:#4ade80; --bad:#f87171; } }
  body { margin:0; background:var(--bg); color:var(--fg); font:16px/1.5 system-ui, sans-serif; }
  main { max-width:30rem; margin:0 auto; padding:24px 16px 48px; }
  h1 { font-size:1.3rem; margin:0 0 4px; }
  .status { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:12px 14px; margin:16px 0; }
  .ok { color:var(--ok); } .bad { color:var(--bad); }
  label { display:block; font-weight:600; margin:16px 0 6px; }
  input { width:100%; box-sizing:border-box; padding:12px; font-size:16px; border-radius:8px; border:1px solid var(--line); background:var(--bg); color:var(--fg); }
  button { margin-top:20px; padding:12px 18px; font-size:16px; font-weight:600; border:0; border-radius:8px; background:var(--accent); color:#fff; }
  button.link { background:none; color:var(--muted); text-decoration:underline; font-weight:400; padding:0 0 0 8px; }
  small { color:var(--muted); display:block; margin-top:6px; }
</style></head>
<body><main>
<h1>Gemini key for LensCode</h1>
<div>Paste it here once instead of typing it in the headset. It stays on this computer.</div>
<div class="status">${esc(status)}</div>
${msg.ok ? `<p class="ok">${esc(msg.ok)}</p>` : ""}${msg.error ? `<p class="bad">${esc(msg.error)}</p>` : ""}
<form method="post" action="/setup" autocomplete="off">
  <label for="apiKey">API key</label>
  <input id="apiKey" name="apiKey" type="password" autocomplete="off" autocapitalize="none" spellcheck="false" placeholder="AIza…">
  <small>Get one at aistudio.google.com/apikey</small>
  <label for="model">Model (optional)</label>
  <input id="model" name="model" type="text" autocapitalize="none" spellcheck="false" placeholder="gemini-3.8-flash">
  <small>Leave blank to use the default, gemini-3.8-flash.</small>
  ${
    prod
      ? `<label for="code">Setup code</label>
  <input id="code" name="code" type="text" inputmode="numeric" autocomplete="off" placeholder="6 digits">
  <small>Printed in the bridge console.</small>`
      : ""
  }
  <button type="submit">Save key</button>
  ${s.source === "file" ? `<button class="link" type="submit" name="action" value="clear">Remove saved key</button>` : ""}
</form>
${prod ? "" : `<small>Dev mode: this page is plain HTTP, so the key crosses your local network unencrypted once. Use prod mode with TLS on shared networks.</small>`}
</main></body></html>`;
  }

  // ---------------------------------------------------------------------------
  // /gemini proxy
  // ---------------------------------------------------------------------------

  private async proxy(req: IncomingMessage, res: ServerResponse, url: URL): Promise<void> {
    if (this.opts.mode === "prod") {
      const deviceId = headerValue(req.headers["x-lenscode-device"]);
      const token = headerValue(req.headers["x-lenscode-token"]);
      if (!deviceId || !this.opts.authorizeDevice(deviceId, token)) {
        this.sendJson(res, 401, geminiError(401, "This device is not paired with lenscode-bridge."));
        return;
      }
    }
    const apiKey = this.opts.store.apiKey();
    if (!apiKey) {
      this.sendJson(
        res,
        503,
        geminiError(503, "No Gemini key is set on the bridge. Open its /setup page to add one."),
      );
      return;
    }
    const upstreamPath = url.pathname.slice("/gemini".length);
    if (!upstreamPath.startsWith("/v1beta/") || (req.method !== "POST" && req.method !== "GET")) {
      this.sendJson(res, 404, geminiError(404, "Unsupported Gemini endpoint."));
      return;
    }

    // The client's own `key` param is a placeholder (Koog always appends one); drop it
    // and authenticate upstream with the real key in a header so it never hits a URL.
    url.searchParams.delete("key");
    const target = `${this.upstream}${upstreamPath}${url.search}`;
    const headers: Record<string, string> = { "x-goog-api-key": apiKey };
    const contentType = headerValue(req.headers["content-type"]);
    if (contentType) headers["content-type"] = contentType;
    const accept = headerValue(req.headers.accept);
    if (accept) headers.accept = accept;

    const body = req.method === "POST" ? await readBody(req, MAX_BODY_BYTES) : undefined;
    const started = Date.now();
    const upstreamRes = await this.fetchImpl(target, {
      method: req.method,
      headers,
      body,
      signal: AbortSignal.timeout(PROXY_TIMEOUT_MS),
    });
    log("info", "gemini proxy", { status: upstreamRes.status, ms: Date.now() - started });

    // fetch() already decoded any gzip, so don't pass the encoding/length through.
    res.writeHead(upstreamRes.status, {
      "content-type": upstreamRes.headers.get("content-type") ?? "application/json",
    });
    if (!upstreamRes.body) {
      res.end();
      return;
    }
    Readable.fromWeb(upstreamRes.body as import("node:stream/web").ReadableStream).pipe(res);
  }

  // ---------------------------------------------------------------------------

  private sendHtml(res: ServerResponse, status: number, html: string): void {
    res.writeHead(status, {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      // Not "no-referrer": that makes browsers send `Origin: null` on our own form
      // post, which the cross-site check in setup() would then (rightly) refuse.
      "referrer-policy": "same-origin",
    });
    res.end(html);
  }

  private sendJson(res: ServerResponse, status: number, body: unknown): void {
    res.writeHead(status, { "content-type": "application/json" });
    res.end(JSON.stringify(body));
  }

  private fail(res: ServerResponse, status: number, message: string): void {
    if (res.headersSent) {
      res.end();
      return;
    }
    this.sendHtml(res, status, this.renderPage({ error: message }));
  }
}

/** Google's own error envelope, so Koog surfaces our failures like any API error. */
function geminiError(code: number, message: string) {
  return { error: { code, message, status: code === 401 ? "UNAUTHENTICATED" : "FAILED_PRECONDITION" } };
}

function headerValue(v: string | string[] | undefined): string | undefined {
  return Array.isArray(v) ? v[0] : v;
}

function safeHost(origin: string): string | null {
  try {
    return new URL(origin).host;
  } catch {
    return null;
  }
}

function esc(s: string): string {
  return s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

function readBody(req: IncomingMessage, maxBytes: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > maxBytes) {
        reject(new Error("request body too large"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

/** This machine's non-loopback IPv4 addresses — what a phone or headset would type. */
export function lanAddresses(): string[] {
  const out: string[] = [];
  for (const addrs of Object.values(networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family === "IPv4" && !a.internal) out.push(a.address);
    }
  }
  return out;
}

export function lanSetupUrls(port: number, tls: boolean): string[] {
  return lanAddresses().map((ip) => `${tls ? "https" : "http"}://${ip}:${port}/setup`);
}
