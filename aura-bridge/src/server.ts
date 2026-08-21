import { createServer as createHttpsServer } from "node:https";
import { createServer as createHttpServer } from "node:http";
import { readFileSync } from "node:fs";
import { hostname } from "node:os";
import { WebSocketServer, WebSocket } from "ws";
import {
  PROTOCOL_VERSION,
  XrRequest,
  type StructuredError,
  type XrEvent,
} from "@aura/protocol";
import type { BridgeConfig } from "./config.ts";
import { HerdrClient } from "./herdr.ts";
import { PairingRegistry } from "./pairing.ts";
import { isHerdrMethodAllowed } from "./allowlist.ts";
import { log } from "./log.ts";

const BRIDGE_VERSION = "0.1.0";

function err(
  code: StructuredError["code"],
  message: string,
  retryable = false,
  detail?: string,
): StructuredError {
  return { code, message, retryable, ...(detail ? { detail } : {}) };
}

export class BridgeServer {
  private wss: WebSocketServer;
  private herdr: HerdrClient;
  private clients = new Set<WebSocket>();
  private eventSeq = 0;
  private cfg: BridgeConfig;
  private httpServer: ReturnType<typeof createHttpServer>;
  private pairing: PairingRegistry;
  /** Sockets that have passed hello with a valid device token (or dev mode). */
  private authed = new WeakSet<WebSocket>();

  constructor(cfg: BridgeConfig) {
    this.cfg = cfg;
    this.pairing = new PairingRegistry(cfg.pairingStorePath);
    this.herdr = new HerdrClient(cfg.herdrSocketPath);
    if (cfg.insecureProd) {
      log("warn", "INSECURE: prod pairing enforcement without TLS — tests only");
    }
    const httpServer =
      cfg.mode === "prod" && !cfg.insecureProd
        ? createHttpsServer({
            key: readFileSync(cfg.tlsKeyPath!),
            cert: readFileSync(cfg.tlsCertPath!),
          })
        : createHttpServer();
    this.httpServer = httpServer;
    this.wss = new WebSocketServer({ server: httpServer });
    this.wss.on("connection", (ws) => this.onConnection(ws));
    httpServer.listen(cfg.port, () => {
      log("info", "bridge listening", {
        port: cfg.port,
        mode: cfg.mode,
        tls: cfg.mode === "prod",
      });
    });
    this.herdr.on("herdr-event", (ev) => this.broadcastHerdrEvent(ev));
    this.herdr.on("disconnected", () =>
      this.broadcastEvent("connection", "herdr_disconnected", {}),
    );
  }

  async start(): Promise<void> {
    try {
      await this.herdr.connect();
      await this.herdr.subscribeEvents();
    } catch (e) {
      // Herdr may start later; clients get herdrConnected:false in hello.
      log("warn", "herdr unavailable at startup", { error: String(e) });
    }
  }

  private onConnection(ws: WebSocket): void {
    this.clients.add(ws);
    ws.on("close", () => this.clients.delete(ws));
    ws.on("message", async (raw) => {
      let parsed: XrRequest;
      let requestId = "unknown";
      try {
        const json = JSON.parse(raw.toString());
        if (typeof json?.id === "string") requestId = json.id;
        parsed = XrRequest.parse(json);
      } catch (e) {
        this.send(ws, {
          id: requestId,
          error: err(
            "invalid_request",
            "The request could not be understood.",
            false,
            String(e),
          ),
        });
        return;
      }
      try {
        await this.dispatch(ws, parsed);
      } catch (e) {
        log("error", "dispatch failed", { type: parsed.type, error: String(e) });
        this.send(ws, {
          id: parsed.id,
          error: err("internal", "Something went wrong inside aura-bridge.", true, String(e)),
        });
      }
    });
  }

  private async dispatch(ws: WebSocket, req: XrRequest): Promise<void> {
    // Pairing handshake messages are the only ones allowed pre-auth (spec §55).
    const preAuth = req.type === "hello" || req.type === "pair.begin" || req.type === "pair.complete";
    if (!preAuth && this.cfg.mode === "prod" && !this.authed.has(ws)) {
      this.send(ws, {
        id: req.id,
        error: err("unauthorized", "This device is not paired with aura-bridge."),
      });
      return;
    }

    switch (req.type) {
      case "hello": {
        const authenticated =
          this.cfg.mode === "dev" ||
          this.pairing.isAuthorized(req.deviceId, req.deviceToken);
        if (authenticated) this.authed.add(ws);
        await this.ensureHerdr();
        this.send(ws, {
          id: req.id,
          result: {
            protocolVersion: PROTOCOL_VERSION,
            serverVersion: BRIDGE_VERSION,
            authenticated,
            herdrProtocol: this.herdr.protocol,
            herdrConnected: this.herdr.connected,
          },
        });
        return;
      }
      case "pair.begin": {
        this.send(ws, { id: req.id, result: this.pairing.begin(req.deviceId, req.deviceName) });
        return;
      }
      case "pair.complete": {
        const outcome = this.pairing.complete(req.deviceId, req.pairingCode);
        if ("error" in outcome) {
          this.send(ws, {
            id: req.id,
            error: err("pairing_required", outcome.error, true),
          });
          return;
        }
        this.authed.add(ws);
        this.send(ws, { id: req.id, result: { deviceToken: outcome.deviceSecret } });
        return;
      }
      case "snapshot.request": {
        await this.ensureHerdr();
        if (!this.herdr.connected) {
          this.send(ws, {
            id: req.id,
            error: err(
              "herdr_unavailable",
              "Herdr is not running on this machine.",
              true,
            ),
          });
          return;
        }
        const snapshot = await this.herdr.request("session.snapshot", {});
        this.send(ws, {
          id: req.id,
          result: {
            herdr: snapshot,
            machines: [
              {
                machineId: "local",
                alias: hostname(),
                kind: "local",
                online: true,
              },
            ],
          },
        });
        return;
      }
      case "events.subscribe": {
        // M1: all connected clients receive all events; per-topic filtering later.
        this.send(ws, { id: req.id, result: { subscribed: req.topics } });
        return;
      }
      case "herdr.request": {
        if (!isHerdrMethodAllowed(req.request.method)) {
          this.send(ws, {
            id: req.id,
            error: err(
              "unauthorized",
              `The method ${req.request.method} is not available from XR.`,
            ),
          });
          return;
        }
        await this.ensureHerdr();
        if (!this.herdr.connected) {
          this.send(ws, {
            id: req.id,
            error: err("herdr_unavailable", "Herdr is not running on this machine.", true),
          });
          return;
        }
        const response = await this.herdr.proxyRequest(req.request);
        this.send(ws, { id: req.id, result: { response } });
        return;
      }
      default: {
        this.send(ws, {
          id: req.id,
          error: err(
            "invalid_request",
            `Not implemented yet: ${req.type}.`,
            false,
          ),
        });
      }
    }
  }

  private async ensureHerdr(): Promise<void> {
    if (this.herdr.connected) return;
    try {
      await this.herdr.connect();
      await this.herdr.subscribeEvents();
      this.broadcastEvent("connection", "herdr_connected", {});
    } catch {
      /* stays disconnected; callers check .connected */
    }
  }

  private broadcastHerdrEvent(ev: { event: string; data: unknown }): void {
    this.broadcastEvent("herdr", ev.event, ev.data);
  }

  private broadcastEvent(topic: XrEvent["topic"], event: string, data: unknown): void {
    const frame: XrEvent = { type: "event", topic, event, data, seq: this.eventSeq++ };
    const payload = JSON.stringify(frame);
    for (const ws of this.clients) {
      if (ws.readyState === WebSocket.OPEN) ws.send(payload);
    }
  }

  private send(ws: WebSocket, msg: unknown): void {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
  }

  /** Test/CLI access to the pairing registry (codes are read locally only). */
  get pairingRegistry(): PairingRegistry {
    return this.pairing;
  }

  close(): void {
    for (const ws of this.clients) ws.terminate();
    this.wss.close();
    this.httpServer.close();
    this.herdr.close();
  }
}
