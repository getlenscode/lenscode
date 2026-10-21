/**
 * Remote Paseo connections (spec §37): tunnels the local bridge to a remote
 * machine's own Paseo daemon over plain SSH TCP port forwarding, then reuses
 * the exact same PaseoClient/Terminals/GitService/WorkspaceFiles code this
 * bridge already uses locally — a remote machine is just a PaseoClient
 * pointed at a forwarded local port. No new IPC protocol, no raw key
 * material ever leaves this process (spec §54): ssh reads `~/.ssh/config`
 * itself, this code never touches a private key or password.
 *
 * Paseo's daemon listens on a TCP port (default 6767), so this forwards a
 * local TCP port to the remote daemon's port.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { createHash } from "node:crypto";
import { PaseoClient } from "./paseo.ts";
import { log } from "./log.ts";

const REMOTE_PASEO_PORT = 6767;

interface Connection {
  tunnel: ChildProcess;
  client: PaseoClient;
  localPort: number;
  homeDir: string;
}

function runSsh(machineId: string, command: string, timeoutMs = 8000): Promise<string> {
  return new Promise((resolve, reject) => {
    const proc = spawn("ssh", [
      "-o", "BatchMode=yes",
      "-o", `ConnectTimeout=${Math.ceil(timeoutMs / 1000)}`,
      machineId,
      command,
    ]);
    let out = "";
    let errOut = "";
    proc.stdout.on("data", (d) => (out += d));
    proc.stderr.on("data", (d) => (errOut += d));
    proc.on("error", reject);
    proc.on("close", (code) => {
      if (code === 0) resolve(out);
      else reject(new Error(errOut.trim() || `ssh to ${machineId} exited ${code}`));
    });
  });
}

/** Deterministic local port per machine id, offset into the ephemeral range. */
function localPortFor(machineId: string): number {
  const hash = createHash("sha1").update(machineId).digest();
  return 20000 + (hash.readUInt16BE(0) % 20000);
}

export class RemoteManager {
  private connections = new Map<string, Connection>();

  /**
   * Called with every Paseo event a connected remote machine pushes (agent state, a pending
   * permission, …). Without it only the local daemon's events reach the app, so a remote agent
   * asking for permission never lit the Permissions icon.
   */
  onEvent: ((machineId: string, event: { type: string; [key: string]: unknown }) => void) | null = null;

  // The SSH tunnel can stay up while the remote's Paseo daemon restarts; the client doesn't
  // reconnect on its own (see PaseoClient), so a connection whose client dropped is dead and must
  // be re-made rather than reported as connected and failing with "Transport not connected".
  private isAlive(machineId: string): boolean {
    return this.connections.get(machineId)?.client.connected === true;
  }

  connectedIds(): string[] {
    return [...this.connections.keys()].filter((id) => this.isAlive(id));
  }

  isConnected(machineId: string): boolean {
    return this.isAlive(machineId);
  }

  /** Null for "local" and for any machine that isn't currently connected. */
  clientFor(machineId: string): PaseoClient | null {
    if (machineId === "local" || !this.isAlive(machineId)) return null;
    return this.connections.get(machineId)?.client ?? null;
  }

  /** Resolved once at connect time — seeds the remote folder browser's starting path. */
  homeDirFor(machineId: string): string | null {
    return this.connections.get(machineId)?.homeDir ?? null;
  }

  async connect(machineId: string): Promise<void> {
    if (this.isAlive(machineId)) return;
    this.disconnect(machineId); // drop a stale tunnel + client before dialing again

    const home = (await runSsh(machineId, "echo $HOME")).trim();
    if (!home) throw new Error(`Could not resolve a home directory on ${machineId}.`);

    const localPort = localPortFor(machineId);

    const tunnel = spawn("ssh", [
      "-o", "BatchMode=yes",
      "-o", "ExitOnForwardFailure=yes",
      "-o", "ConnectTimeout=10",
      "-o", "ServerAliveInterval=15",
      "-o", "ServerAliveCountMax=3",
      "-L", `127.0.0.1:${localPort}:127.0.0.1:${REMOTE_PASEO_PORT}`,
      "-N",
      machineId,
    ]);

    let tunnelExited = false;
    tunnel.on("exit", (code) => {
      tunnelExited = true;
      if (this.connections.get(machineId)?.tunnel === tunnel) {
        log("warn", "remote tunnel exited", { machineId, code });
        this.connections.delete(machineId);
      }
    });
    tunnel.on("error", () => {
      tunnelExited = true;
    });

    // ssh's forwarding listener comes up once its control channel is
    // established — poll by attempting the Paseo connection rather than
    // guessing a fixed delay.
    const client = new PaseoClient({ url: `ws://127.0.0.1:${localPort}/ws` });
    const deadline = Date.now() + 8000;
    let lastError: unknown;
    while (Date.now() < deadline) {
      if (tunnelExited) throw new Error(`The SSH tunnel to ${machineId} closed before it came up.`);
      try {
        await client.connect();
        lastError = null;
        break;
      } catch (e) {
        lastError = e;
        await new Promise((resolve) => setTimeout(resolve, 200));
      }
    }
    if (lastError) {
      tunnel.kill();
      throw new Error(`Tunnel to ${machineId} is up, but Paseo isn't reachable there: ${lastError}`);
    }

    client.on("paseo-event", (event) => this.onEvent?.(machineId, event));
    this.connections.set(machineId, { tunnel, client, localPort, homeDir: home });
  }

  disconnect(machineId: string): void {
    const conn = this.connections.get(machineId);
    if (!conn) return;
    conn.client.close();
    conn.tunnel.kill();
    this.connections.delete(machineId);
  }

  disconnectAll(): void {
    for (const machineId of [...this.connections.keys()]) this.disconnect(machineId);
  }
}
