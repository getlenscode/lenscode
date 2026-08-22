/**
 * Remote Herdr connections (spec §37): tunnels the local bridge to a remote
 * machine's own Herdr socket over plain SSH port forwarding, then reuses the
 * exact same HerdrClient/Terminals/GitService/WorkspaceFiles code this bridge
 * already uses locally — a remote machine is just a HerdrClient pointed at a
 * different (forwarded) socket path. No new IPC protocol, no raw key
 * material ever leaves this process (spec §54): ssh reads `~/.ssh/config`
 * itself, this code never touches a private key or password.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { HerdrClient } from "./herdr.ts";
import { log } from "./log.ts";

interface Connection {
  tunnel: ChildProcess;
  client: HerdrClient;
  localSocketPath: string;
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

export class RemoteManager {
  private connections = new Map<string, Connection>();

  isConnected(machineId: string): boolean {
    return this.connections.has(machineId);
  }

  /** Null for "local" and for any machine that isn't currently connected. */
  clientFor(machineId: string): HerdrClient | null {
    if (machineId === "local") return null;
    return this.connections.get(machineId)?.client ?? null;
  }

  /** Resolved once at connect time — seeds the remote folder browser's starting path. */
  homeDirFor(machineId: string): string | null {
    return this.connections.get(machineId)?.homeDir ?? null;
  }

  async connect(machineId: string): Promise<void> {
    if (this.connections.has(machineId)) return;

    // sshd's own forwarding implementation doesn't shell-expand "~" in the
    // remote_socket argument, so the path must already be absolute.
    const home = (await runSsh(machineId, "echo $HOME")).trim();
    if (!home) throw new Error(`Could not resolve a home directory on ${machineId}.`);
    const remoteSocket = `${home}/.config/herdr/herdr.sock`;

    // Unix domain socket paths are capped around 104-108 bytes on both macOS
    // and Linux — a short hashed name under the OS temp dir stays well clear
    // of that regardless of how long the machine id is.
    const hash = createHash("sha1").update(machineId).digest("hex").slice(0, 12);
    const localSocketPath = join(tmpdir(), `aura-remote-${hash}.sock`);
    if (existsSync(localSocketPath)) unlinkSync(localSocketPath);

    const tunnel = spawn("ssh", [
      "-o", "BatchMode=yes",
      "-o", "ExitOnForwardFailure=yes",
      "-o", "ConnectTimeout=10",
      "-o", "ServerAliveInterval=15",
      "-o", "ServerAliveCountMax=3",
      "-L", `${localSocketPath}:${remoteSocket}`,
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

    // ssh creates the forwarded socket once its control channel is up —
    // poll for it rather than guessing a fixed delay.
    const deadline = Date.now() + 8000;
    while (!existsSync(localSocketPath)) {
      if (tunnelExited) throw new Error(`The SSH tunnel to ${machineId} closed before it came up.`);
      if (Date.now() > deadline) {
        tunnel.kill();
        throw new Error(`Timed out opening a tunnel to ${machineId}.`);
      }
      await new Promise((resolve) => setTimeout(resolve, 100));
    }

    const client = new HerdrClient(localSocketPath);
    try {
      await client.connect();
    } catch (e) {
      tunnel.kill();
      if (existsSync(localSocketPath)) unlinkSync(localSocketPath);
      throw new Error(`Tunnel to ${machineId} is up, but Herdr isn't reachable there: ${e}`);
    }

    this.connections.set(machineId, { tunnel, client, localSocketPath, homeDir: home });
  }

  disconnect(machineId: string): void {
    const conn = this.connections.get(machineId);
    if (!conn) return;
    conn.tunnel.kill();
    if (existsSync(conn.localSocketPath)) unlinkSync(conn.localSocketPath);
    this.connections.delete(machineId);
  }

  disconnectAll(): void {
    for (const machineId of [...this.connections.keys()]) this.disconnect(machineId);
  }
}
