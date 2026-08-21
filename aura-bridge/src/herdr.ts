/**
 * Herdr IPC client: NDJSON over the Unix domain socket (docs/herdr-ipc.md).
 *
 * Observed connection model (herdr 0.8.2, protocol 20):
 *  - regular requests: one connection per request — Herdr closes after responding
 *  - events.subscribe: responds `subscription_started`, then holds the connection
 *    open streaming `{event, data}` frames
 */
import { createConnection, type Socket } from "node:net";
import { EventEmitter } from "node:events";
import { log } from "./log.ts";

export interface HerdrEvent {
  event: string;
  data: unknown;
}

export class HerdrError extends Error {
  body: unknown;

  constructor(body: unknown) {
    super(typeof body === "object" && body && "message" in (body as object)
      ? String((body as { message: unknown }).message)
      : "Herdr request failed");
    this.body = body;
  }
}

function readFrames(onFrame: (msg: Record<string, unknown>) => void) {
  let buffer = "";
  return (chunk: string) => {
    buffer += chunk;
    let idx: number;
    while ((idx = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line) continue;
      try {
        onFrame(JSON.parse(line));
      } catch {
        log("warn", "herdr sent unparseable frame", { line: line.slice(0, 200) });
      }
    }
  };
}

export class HerdrClient extends EventEmitter {
  private nextId = 1;
  private eventSocket: Socket | null = null;
  /** Herdr protocol number reported by ping; null until first successful ping. */
  protocol: number | null = null;
  private socketPath: string;

  constructor(socketPath: string) {
    super();
    this.socketPath = socketPath;
  }

  /** True once ping has succeeded and the event stream is up. */
  get connected(): boolean {
    return this.eventSocket !== null;
  }

  async connect(): Promise<void> {
    const pong = (await this.request("ping", {})) as { protocol?: number };
    this.protocol = pong.protocol ?? null;
    log("info", "herdr reachable", { protocol: this.protocol });
  }

  /** One-shot request on its own connection (Herdr closes it after the response). */
  request(method: string, params: Record<string, unknown>): Promise<unknown> {
    const id = String(this.nextId++);
    return new Promise((resolve, reject) => {
      const sock = createConnection(this.socketPath);
      sock.setEncoding("utf8");
      let settled = false;
      const settle = (fn: () => void) => {
        if (settled) return;
        settled = true;
        fn();
        sock.destroy();
      };
      sock.once("connect", () => {
        sock.write(JSON.stringify({ id, method, params }) + "\n");
      });
      sock.on("data", readFrames((msg) => {
        if (msg.id !== id) return;
        if ("error" in msg) settle(() => reject(new HerdrError(msg.error)));
        else settle(() => resolve(msg.result));
      }));
      sock.once("error", (e) => settle(() => reject(e)));
      sock.once("close", () =>
        settle(() => reject(new Error("herdr closed connection without responding"))),
      );
    });
  }

  /** Forward a native envelope verbatim, preserving the caller's request id. */
  async proxyRequest(envelope: {
    id: string;
    method: string;
    params: Record<string, unknown>;
  }): Promise<{ id: string; result?: unknown; error?: unknown }> {
    return this.request(envelope.method, envelope.params).then(
      (result) => ({ id: envelope.id, result }),
      (err: unknown) => ({
        id: envelope.id,
        error: err instanceof HerdrError ? err.body : { message: String(err) },
      }),
    );
  }

  /**
   * Global subscription types from the Herdr schema (docs/herdr-api-schema.json).
   * pane.output_matched / pane.agent_status_changed / pane.scroll_changed require a
   * pane_id and are subscribed per-pane later (terminal streaming, M4).
   */
  static readonly ALL_SUBSCRIPTIONS = [
    "workspace.created", "workspace.updated", "workspace.metadata_updated",
    "workspace.renamed", "workspace.moved", "workspace.reordered",
    "workspace.closed", "workspace.focused",
    "worktree.created", "worktree.opened", "worktree.removed",
    "tab.created", "tab.closed", "tab.focused", "tab.renamed", "tab.moved",
    "pane.created", "pane.closed", "pane.updated", "pane.focused",
    "pane.moved", "pane.exited", "pane.agent_detected",
    "layout.updated",
  ] as const;

  /** Opens the persistent event-stream connection. */
  async subscribeEvents(): Promise<void> {
    if (this.eventSocket) return;
    const id = String(this.nextId++);
    await new Promise<void>((resolve, reject) => {
      const sock = createConnection(this.socketPath);
      sock.setEncoding("utf8");
      let started = false;
      sock.once("connect", () => {
        sock.write(
          JSON.stringify({
            id,
            method: "events.subscribe",
            params: {
              subscriptions: HerdrClient.ALL_SUBSCRIPTIONS.map((type) => ({ type })),
            },
          }) + "\n",
        );
      });
      sock.on("data", readFrames((msg) => {
        if (!started && msg.id === id) {
          if ("error" in msg) {
            reject(new HerdrError(msg.error));
            sock.destroy();
            return;
          }
          started = true;
          this.eventSocket = sock;
          resolve();
          return;
        }
        if (typeof msg.event === "string") {
          this.emit("herdr-event", { event: msg.event, data: msg.data } as HerdrEvent);
        }
      }));
      sock.once("error", (e) => {
        if (!started) reject(e);
      });
      sock.once("close", () => {
        if (!started) {
          reject(new Error("herdr closed subscription connection"));
          return;
        }
        this.eventSocket = null;
        this.emit("disconnected");
        log("warn", "herdr event stream closed");
      });
    });
    log("info", "herdr event stream open");
  }

  close(): void {
    this.eventSocket?.destroy();
    this.eventSocket = null;
  }
}
