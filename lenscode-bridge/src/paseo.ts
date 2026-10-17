/**
 * Paseo daemon client (docs/paseo-migration-plan.md, Phase 1). Replaces
 * herdr.ts: lenscode-bridge is now a session on top of a Paseo daemon
 * (https://github.com/getpaseo/paseo), reached over its WebSocket protocol via
 * the official `@getpaseo/client` SDK rather than a hand-rolled IPC client.
 *
 * We depend on the SDK's low-level `DaemonClient` (its documented
 * `@getpaseo/client/internal/daemon-client` export) rather than the high-level
 * `createPaseoClient()` wrapper: the wrapper's `agents`/`workspaces` surface is
 * for Phase 2 (agents as first-class XR objects); Phase 1 only needs the
 * terminal + connection primitives DaemonClient already exposes directly.
 */
import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";
import { readFileSync } from "node:fs";
import { DaemonClient, type DaemonEvent } from "@getpaseo/client/internal/daemon-client";
import { log } from "./log.ts";

/** `major.minor` — paseo is pre-1.0, so minor bumps are the breaking boundary (0.7 → 0.8 → 0.10 all moved semantics). */
function majorMinor(version: string | null): string | null {
  return version?.match(/^(\d+)\.(\d+)/)?.[0] ?? null;
}

/** The @getpaseo/client pin this bridge was built against, read from our own package.json (the package doesn't export its manifest). Works from both src/ and dist/. */
function sdkPinnedVersion(): string | null {
  try {
    const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
    const range: unknown = pkg?.dependencies?.["@getpaseo/client"];
    return typeof range === "string" ? (range.match(/\d+\.\d+(\.\d+)?/)?.[0] ?? null) : null;
  } catch {
    return null;
  }
}

export interface PaseoConfig {
  url: string;
  password?: string;
  connectTimeoutMs?: number;
}

export class PaseoError extends Error {
  detail: unknown;

  constructor(message: string, detail?: unknown) {
    super(message);
    this.detail = detail;
  }
}

/**
 * Thin wrapper giving the rest of the bridge the same shape HerdrClient used
 * to provide (`connect`, `connected`, `protocol`, an event emitter for
 * backend/disconnected events) over the real Paseo SDK client.
 */
export class PaseoClient extends EventEmitter {
  private client: DaemonClient;
  private everConnected = false;
  /** Paseo daemon version string reported by daemon.get_status, null until first success. */
  protocol: string | null = null;

  constructor(cfg: PaseoConfig) {
    super();
    this.client = new DaemonClient({
      url: cfg.url,
      // lenscode-bridge is itself the "client" from the daemon's point of view —
      // one stable id per bridge process, not per XR device that connects to us.
      clientId: `lenscode-bridge-${randomUUID()}`,
      clientType: "mobile",
      password: cfg.password,
      // The bridge already retries on demand via ensurePaseo() before every
      // request (mirroring HerdrClient's model); the SDK's own background
      // reconnect loop would just be a second retry policy fighting the
      // first, and its timers can outlive a server.close() in tests.
      reconnect: { enabled: false },
      // The SDK's own default (15s) is tuned for a human waiting on a UI;
      // the bridge's ensurePaseo() is polled on every hello/request, so a
      // fast, loud failure beats a slow hang when no daemon is running yet
      // (mirrors HerdrClient's one-shot-connection-refused behavior).
      connectTimeoutMs: cfg.connectTimeoutMs ?? 3000,
    });
    this.client.subscribe((event) => this.onDaemonEvent(event));
    this.client.subscribeConnectionStatus((status) => {
      if (status.status === "disconnected" && this.everConnected) {
        this.emit("disconnected");
      }
    });
  }

  /** The underlying SDK client — terminals.ts calls its terminal RPCs directly. */
  get daemon(): DaemonClient {
    return this.client;
  }

  get connected(): boolean {
    return this.client.isConnected;
  }

  async connect(): Promise<void> {
    await this.client.connect();
    this.everConnected = true;
    try {
      const status = await this.client.getDaemonStatus();
      this.protocol = status.version ?? null;
    } catch (e) {
      // Connected but couldn't read status — leave protocol as-is (or null).
      log("warn", "paseo daemon status unavailable", { error: String(e) });
    }
    log("info", "paseo daemon reachable", { protocol: this.protocol });
    // Wire calls have held across daemon bumps so far, but semantics haven't
    // (0.10 mints branch-1 duplicates where 0.8 errored) — a version skew is
    // worth a loud line, not a silent shrug.
    const pinned = sdkPinnedVersion();
    if (majorMinor(this.protocol) !== majorMinor(pinned)) {
      log("warn", "paseo daemon/SDK version mismatch — expected drift bugs; bump @getpaseo/* pins or the daemon", {
        daemonVersion: this.protocol,
        sdkPinned: pinned,
      });
    }
  }

  private onDaemonEvent(event: DaemonEvent): void {
    if (event.type === "error") {
      log("warn", "paseo daemon event stream error", { message: event.message });
      return;
    }
    this.emit("paseo-event", event);
  }

  close(): void {
    void this.client.close();
  }
}
