/**
 * `--tailscale`: find this machine's Tailscale address so the app can be pointed at an address that
 * keeps working off the home network. Tailscale hands out IPv4 addresses from the carrier-grade NAT
 * range 100.64.0.0/10, so an interface scan finds it without needing the `tailscale` CLI on PATH.
 */
import { execFile } from "node:child_process";
import { networkInterfaces, type NetworkInterfaceInfo } from "node:os";

/** True for 100.64.0.0 – 100.127.255.255. */
export function isTailscaleIp(ip: string): boolean {
  const [a, b] = ip.split(".").map(Number);
  return a === 100 && b !== undefined && b >= 64 && b <= 127;
}

export function tailscaleAddresses(
  interfaces: Record<string, NetworkInterfaceInfo[] | undefined> = networkInterfaces(),
): string[] {
  const out: string[] = [];
  for (const addrs of Object.values(interfaces)) {
    for (const a of addrs ?? []) {
      if (a.family === "IPv4" && !a.internal && isTailscaleIp(a.address)) out.push(a.address);
    }
  }
  return out;
}

/** The MagicDNS name from `tailscale status --json` ("my-mac.tailnet.ts.net"), if the CLI is available. */
export function parseMagicDnsName(statusJson: string): string | undefined {
  try {
    const name = (JSON.parse(statusJson) as { Self?: { DNSName?: string } }).Self?.DNSName;
    return name ? name.replace(/\.$/, "") : undefined;
  } catch {
    return undefined;
  }
}

const CLI_CANDIDATES = ["tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale"];

export async function tailscaleDnsName(): Promise<string | undefined> {
  for (const bin of CLI_CANDIDATES) {
    const out = await new Promise<string | undefined>((resolve) => {
      execFile(bin, ["status", "--json"], { timeout: 3000 }, (err, stdout) => resolve(err ? undefined : stdout));
    });
    const name = out ? parseMagicDnsName(out) : undefined;
    if (name) return name;
  }
  return undefined;
}
