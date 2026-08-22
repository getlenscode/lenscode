/**
 * Remote machine registry (spec §36, Phase 3). Reads real host aliases out of
 * `~/.ssh/config` — but that file is the one place actual IPs, usernames, and
 * key paths live, and none of that may ever reach the XR client (spec §54,
 * §36: "no IPs/passwords in XR"). This module's only exported shape is
 * `{ id, label }`; every other SSH directive is parsed only to decide which
 * `Host` blocks are real single machines, then thrown away.
 */
import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

export interface MachineInfo {
  /** The bare Host alias from ssh config — the only thing the client ever sees. */
  id: string;
  label: string;
}

const IPV4_RE = /^(\d{1,3}\.){3}\d{1,3}$/;
/** Loose enough for a plain IPv6 literal without trying to fully validate it. */
const IPV6_RE = /^[0-9a-f:]+$/i;

function looksLikeIp(token: string): boolean {
  if (IPV4_RE.test(token)) return true;
  return token.includes(":") && IPV6_RE.test(token);
}

/** Parses `Host` block aliases only; every other directive (HostName, User,
 * IdentityFile, Port, ProxyJump…) is read by ssh itself, never by us. */
export function parseSshConfigHosts(text: string): string[] {
  const aliases: string[] = [];
  for (const rawLine of text.split("\n")) {
    // Strip inline comments before tokenizing — "Host foo  #comment" must not
    // parse "#comment" as a second alias.
    const line = rawLine.replace(/#.*/, "").trim();
    if (line.length === 0) continue;
    const match = line.match(/^Host\s+(.+)$/i);
    if (!match) continue;
    for (const token of match[1].split(/\s+/)) {
      // Wildcard/negated patterns (e.g. "Host *", "Host !bastion") configure
      // ssh's own defaults — they aren't a specific machine to register.
      if (token.includes("*") || token.includes("?") || token.startsWith("!")) continue;
      // A bare IP as the alias IS the sensitive info spec §36 forbids
      // sending to XR — there's no separate friendly name to show instead,
      // so the only safe option is leaving it out of the registry entirely.
      if (looksLikeIp(token)) continue;
      aliases.push(token);
    }
  }
  return aliases;
}

export async function listMachines(sshConfigPath: string = join(homedir(), ".ssh", "config")): Promise<MachineInfo[]> {
  let text: string;
  try {
    text = await readFile(sshConfigPath, "utf8");
  } catch {
    return []; // no ssh config at all is a normal, valid state — not an error
  }
  const aliases = parseSshConfigHosts(text);
  return aliases.map((alias) => ({ id: alias, label: alias }));
}
