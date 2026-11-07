/**
 * LAN discovery: the bridge advertises itself over mDNS as `_lenscode._tcp` so the headset
 * can list it ("MacBook — tap to pair") instead of making someone dictate an IP address.
 * The advert is unauthenticated, so it carries only what is needed to find and label the
 * bridge; trust still comes from the pairing code and the certificate fingerprint prompt.
 */
import { hostname } from "node:os";
import { Bonjour } from "bonjour-service";
import { log } from "./log.ts";

export const SERVICE_TYPE = "lenscode";

export interface AdvertSpec {
  name: string;
  port: number;
  txt: Record<string, string>;
}

/** The mDNS record for this bridge. `secure=1` means wss://; `fp` is the short cert fingerprint. */
export function buildAdvert(opts: {
  host: string;
  port: number;
  secure: boolean;
  version: string;
  fingerprint?: string;
}): AdvertSpec {
  const txt: Record<string, string> = { v: opts.version, secure: opts.secure ? "1" : "0" };
  if (opts.fingerprint) txt.fp = opts.fingerprint;
  return { name: `LensCode bridge (${opts.host})`, port: opts.port, txt };
}

/** Starts advertising; returns a stop function. Failure is logged, never fatal: typing the IP still works. */
export function advertiseBridge(opts: {
  port: number;
  secure: boolean;
  version: string;
  fingerprint?: string;
}): () => void {
  try {
    const bonjour = new Bonjour();
    const spec = buildAdvert({ host: hostname().replace(/\.local$/, ""), ...opts });
    bonjour.publish({ name: spec.name, type: SERVICE_TYPE, protocol: "tcp", port: spec.port, txt: spec.txt });
    return () => bonjour.destroy();
  } catch (e) {
    log("warn", "could not advertise the bridge on the local network", { error: String(e) });
    return () => {};
  }
}
