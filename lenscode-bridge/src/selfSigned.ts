/**
 * `lenscode-bridge --secure`: the bridge makes its own TLS certificate, so a user needs no
 * domain, no Let's Encrypt and no tunnel. Nothing public vouches for a certificate like this,
 * so the headset instead *pins* it: the first time it connects it shows this certificate's
 * fingerprint, the user compares it with the one printed here and confirms once, and from then
 * on only that exact certificate is accepted (like an SSH host key). The pin is to the
 * certificate, not the address, so the bridge changing IP does not break it.
 */
import { X509Certificate } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { hostname, homedir } from "node:os";
import { join } from "node:path";
import { generate } from "selfsigned";
import { lanAddresses } from "./geminiHttp.ts";
import { log } from "./log.ts";

const VALID_DAYS = 3650;
/** Replace the certificate this long before it expires, so a pin never silently outlives it. */
const RENEW_BEFORE_DAYS = 30;
const DAY_MS = 86_400_000;

export interface SelfSignedCert {
  certPath: string;
  keyPath: string;
  /** SHA-256 of the certificate, as Node prints it: uppercase hex pairs joined by ":". */
  fingerprint: string;
}

export function defaultSelfSignedDir(): string {
  return join(homedir(), ".config", "lenscode-bridge", "self-signed");
}

/**
 * Returns the bridge's self-signed certificate, creating it on first use and reusing it after
 * (a new certificate would invalidate every headset's pin). Replaced only when it is about to
 * expire or the files are unreadable.
 */
export async function ensureSelfSignedCert(
  dir = defaultSelfSignedDir(),
  options: { hosts?: string[]; now?: Date; renewBeforeDays?: number } = {},
): Promise<SelfSignedCert> {
  const certPath = join(dir, "cert.pem");
  const keyPath = join(dir, "key.pem");
  const now = options.now ?? new Date();
  const renewMs = (options.renewBeforeDays ?? RENEW_BEFORE_DAYS) * DAY_MS;

  const existing = loadCert(certPath, keyPath);
  if (existing && new Date(existing.validTo).getTime() - now.getTime() > renewMs) {
    return { certPath, keyPath, fingerprint: existing.fingerprint256 };
  }

  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const altNames: Array<{ type: 2; value: string } | { type: 7; ip: string }> = [
    { type: 2, value: "localhost" },
    { type: 7, ip: "127.0.0.1" },
    ...lanAddresses().map((ip) => ({ type: 7 as const, ip })),
    ...(options.hosts ?? []).map((h) =>
      /^\d+\.\d+\.\d+\.\d+$/.test(h) ? { type: 7 as const, ip: h } : { type: 2 as const, value: h },
    ),
  ];
  const pems = await generate([{ name: "commonName", value: `lenscode-bridge (${hostname()})` }], {
    keyType: "ec",
    curve: "P-256",
    algorithm: "sha256",
    notBeforeDate: new Date(now.getTime() - DAY_MS), // tolerate a headset clock a little behind ours
    notAfterDate: new Date(now.getTime() + VALID_DAYS * DAY_MS),
    extensions: [
      { name: "basicConstraints", cA: false },
      { name: "keyUsage", digitalSignature: true, keyEncipherment: true },
      { name: "extKeyUsage", serverAuth: true },
      { name: "subjectAltName", altNames },
    ],
  });
  writeFileSync(keyPath, pems.private, { mode: 0o600 });
  chmodSync(keyPath, 0o600);
  writeFileSync(certPath, pems.cert, { mode: 0o644 });
  const fingerprint = new X509Certificate(pems.cert).fingerprint256;
  log("info", "created a self-signed certificate", { certPath, fingerprint: shortFingerprint(fingerprint) });
  return { certPath, keyPath, fingerprint };
}

function loadCert(certPath: string, keyPath: string): X509Certificate | null {
  if (!existsSync(certPath) || !existsSync(keyPath)) return null;
  try {
    return new X509Certificate(readFileSync(certPath));
  } catch {
    return null;
  }
}

/**
 * The part of the fingerprint a person compares: the first 80 bits as five groups of four hex
 * characters. Long enough that nobody can grind a lookalike certificate, short enough to read.
 * The headset computes the same string from the same bytes.
 */
export function shortFingerprint(fingerprint256: string): string {
  const hex = fingerprint256.replace(/:/g, "").toUpperCase();
  return hex.slice(0, 20).match(/.{4}/g)!.join("-");
}
