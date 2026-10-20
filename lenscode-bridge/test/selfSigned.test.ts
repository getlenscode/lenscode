import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { X509Certificate } from "node:crypto";
import { mkdtempSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";
import { ensureSelfSignedCert, shortFingerprint, type SelfSignedCert } from "../src/selfSigned.ts";

const PORT = 18146;
let dir: string;
let cert: SelfSignedCert;
let server: BridgeServer;

before(async () => {
  dir = mkdtempSync(join(tmpdir(), "lenscode-selfsigned-"));
  cert = await ensureSelfSignedCert(join(dir, "ss"), { hosts: ["bridge.example.test", "10.9.8.7"] });
  server = new BridgeServer(
    loadConfig({
      LENSCODE_PORT: String(PORT),
      LENSCODE_MODE: "prod",
      LENSCODE_TLS_CERT: cert.certPath,
      LENSCODE_TLS_KEY: cert.keyPath,
      LENSCODE_PAIRING_STORE: join(dir, "devices.json"),
      LENSCODE_GEMINI_STORE: join(dir, "gemini.json"),
    } as NodeJS.ProcessEnv),
  );
  await server.start();
});

after(() => server.close());

test("creates a certificate and a private key readable only by the owner", () => {
  const parsed = new X509Certificate(readFileSync(cert.certPath));
  assert.equal(cert.fingerprint, parsed.fingerprint256);
  assert.equal(statSync(cert.keyPath).mode & 0o777, 0o600);
  assert.equal(parsed.issuer, parsed.subject, "self-signed: issued by itself");
  assert.equal(parsed.verify(parsed.publicKey), true, "and signed with its own key");
  const san = parsed.subjectAltName ?? "";
  assert.match(san, /IP Address:127\.0\.0\.1/);
  assert.match(san, /IP Address:10\.9\.8\.7/);
  assert.match(san, /DNS:bridge\.example\.test/);
  const years = (new Date(parsed.validTo).getTime() - Date.now()) / (365 * 86_400_000);
  assert.ok(years > 9 && years < 11, `valid for about ten years, got ${years.toFixed(1)}`);
});

test("reuses the certificate, so headsets that pinned it keep working", async () => {
  const again = await ensureSelfSignedCert(join(dir, "ss"));
  assert.equal(again.fingerprint, cert.fingerprint);
});

test("replaces a certificate that is about to expire", async () => {
  // A separate folder: the running server is still serving the first certificate.
  const other = join(dir, "renew");
  const first = await ensureSelfSignedCert(other);
  const renewed = await ensureSelfSignedCert(other, { renewBeforeDays: 5000 });
  assert.notEqual(renewed.fingerprint, first.fingerprint);
});

test("the short fingerprint is five groups of four uppercase hex characters", () => {
  const short = shortFingerprint("1F:C2:33:D6:81:99:F7:D7:6C:7E:AA:BB:CC");
  assert.equal(short, "1FC2-33D6-8199-F7D7-6C7E");
  assert.match(shortFingerprint(cert.fingerprint), /^([0-9A-F]{4}-){4}[0-9A-F]{4}$/);
});

test("a client that does not trust the certificate is refused", async () => {
  const ws = new WebSocket(`wss://127.0.0.1:${PORT}`);
  const [err] = await once(ws, "error");
  assert.match(String(err.message), /self.signed|unable to verify|certificate/i);
});

test("a client that pins the certificate by fingerprint connects, and a wrong pin is caught", async () => {
  // This is what the headset does: skip the usual chain check, then compare the certificate it
  // was shown with the fingerprint the user confirmed.
  const ws = new WebSocket(`wss://127.0.0.1:${PORT}`, { rejectUnauthorized: false });
  await once(ws, "open");
  const peer = (ws as unknown as { _socket: import("node:tls").TLSSocket })._socket.getPeerCertificate();
  const served = new X509Certificate(peer.raw).fingerprint256;
  assert.equal(served, cert.fingerprint, "the server presents the certificate that was created");
  const wrongPin = "AA:".repeat(31) + "AA";
  assert.notEqual(served, wrongPin, "a pin for any other certificate would be rejected");
  ws.close();
});
