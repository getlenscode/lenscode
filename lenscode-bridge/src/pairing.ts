/**
 * Device pairing (spec §55): short-lived numeric code approved on the desktop,
 * per-device secret ("certificate") returned once, fingerprint stored, revocable.
 * Registry persists to disk so pairings survive bridge restarts.
 */
import { randomBytes, randomInt, createHash, timingSafeEqual } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname } from "node:path";
import { log } from "./log.ts";

const PAIRING_CODE_TTL_MS = 5 * 60 * 1000;

export interface PairedDevice {
  deviceId: string;
  deviceName: string;
  /** sha256 of the device secret; the secret itself is never stored. */
  certFingerprint: string;
  pairedAt: string;
  revoked: boolean;
}

interface PendingPairing {
  deviceId: string;
  deviceName: string;
  code: string;
  expiresAt: number;
}

export class PairingRegistry {
  private devices = new Map<string, PairedDevice>();
  private pending = new Map<string, PendingPairing>();
  private storePath: string;

  constructor(storePath: string) {
    this.storePath = storePath;
    this.load();
  }

  /**
   * Starts pairing. The code is shown on the DESKTOP (bridge log/CLI) and typed
   * or spoken in XR — it is never sent to the unauthenticated device.
   */
  begin(deviceId: string, deviceName: string): { expiresInSeconds: number } {
    const code = String(randomInt(0, 1_000_000)).padStart(6, "0");
    this.pending.set(deviceId, {
      deviceId,
      deviceName,
      code,
      expiresAt: Date.now() + PAIRING_CODE_TTL_MS,
    });
    log("info", "PAIRING CODE — approve this device by entering the code in XR", {
      deviceName,
      code,
    });
    return { expiresInSeconds: PAIRING_CODE_TTL_MS / 1000 };
  }

  /** Test/CLI hook: reads the pending code locally (never over the network). */
  peekPendingCode(deviceId: string): string | undefined {
    return this.pending.get(deviceId)?.code;
  }

  /** Completes pairing; returns the device secret exactly once. */
  complete(deviceId: string, code: string): { deviceSecret: string } | { error: string } {
    const p = this.pending.get(deviceId);
    if (!p) return { error: "No pairing in progress for this device." };
    if (Date.now() > p.expiresAt) {
      this.pending.delete(deviceId);
      return { error: "The pairing code expired. Start pairing again." };
    }
    const a = Buffer.from(p.code);
    const b = Buffer.from(code.padEnd(p.code.length).slice(0, p.code.length));
    if (!timingSafeEqual(a, b)) return { error: "That pairing code is not correct." };

    this.pending.delete(deviceId);
    const deviceSecret = randomBytes(32).toString("base64url");
    this.devices.set(deviceId, {
      deviceId,
      deviceName: p.deviceName,
      certFingerprint: fingerprint(deviceSecret),
      pairedAt: new Date().toISOString(),
      revoked: false,
    });
    this.save();
    log("info", "device paired", { deviceId, deviceName: p.deviceName });
    return { deviceSecret };
  }

  isAuthorized(deviceId: string, deviceSecret: string | undefined): boolean {
    if (!deviceSecret) return false;
    const d = this.devices.get(deviceId);
    if (!d || d.revoked) return false;
    const a = Buffer.from(d.certFingerprint);
    const b = Buffer.from(fingerprint(deviceSecret));
    return a.length === b.length && timingSafeEqual(a, b);
  }

  revoke(deviceId: string): boolean {
    const d = this.devices.get(deviceId);
    if (!d) return false;
    d.revoked = true;
    this.save();
    log("info", "device revoked", { deviceId });
    return true;
  }

  list(): PairedDevice[] {
    return [...this.devices.values()];
  }

  private load(): void {
    if (!existsSync(this.storePath)) return;
    try {
      const data = JSON.parse(readFileSync(this.storePath, "utf8")) as PairedDevice[];
      for (const d of data) this.devices.set(d.deviceId, d);
    } catch (e) {
      log("warn", "could not load pairing registry", { error: String(e) });
    }
  }

  private save(): void {
    mkdirSync(dirname(this.storePath), { recursive: true });
    writeFileSync(this.storePath, JSON.stringify(this.list(), null, 2), { mode: 0o600 });
  }
}

function fingerprint(secret: string): string {
  return createHash("sha256").update(secret).digest("hex");
}
