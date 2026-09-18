import { createServer as createHttpsServer } from "node:https";
import { createServer as createHttpServer } from "node:http";
import { readFileSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import { hostname, homedir } from "node:os";
import { join } from "node:path";
import { WebSocketServer, WebSocket } from "ws";
import {
  PROTOCOL_VERSION,
  XrRequest,
  type StructuredError,
  type XrEvent,
} from "@aura/protocol";
import type { BridgeConfig } from "./config.ts";
import { PaseoClient } from "./paseo.ts";
import { PairingRegistry } from "./pairing.ts";
import { WorkspaceFiles } from "./files.ts";
import { Terminals } from "./terminals.ts";
import { Agents, listProviders } from "./agents.ts";
import { GitService, worktreeConflictPath } from "./git.ts";
import { listMachines } from "./machines.ts";
import { RemoteManager } from "./remote.ts";
import { RemoteFiles } from "./remoteFiles.ts";
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

/** User-facing failure text per terminal operation (spec §68). */
function terminalErrorMessage(type: string): string {
  switch (type) {
    case "terminal.interrupt":
      return "Could not stop the process in that terminal.";
    case "terminal.create":
      return "Could not create a new terminal.";
    case "terminal.read":
      return "Could not read that terminal's output.";
    default:
      return "That terminal operation failed.";
  }
}

export class BridgeServer {
  private wss: WebSocketServer;
  private paseo: PaseoClient;
  private clients = new Set<WebSocket>();
  private eventSeq = 0;
  private cfg: BridgeConfig;
  private httpServer: ReturnType<typeof createHttpServer>;
  private pairing: PairingRegistry;
  /** workspaceId → file service rooted at that workspace's directory. */
  private workspaces = new Map<string, WorkspaceFiles>();
  /** workspaceId → git service rooted at that same directory. */
  private gitServices = new Map<string, GitService>();
  /** "machineId:root" → remote file service rooted at that absolute path (spec §37). */
  private remoteFileServices = new Map<string, RemoteFiles>();
  private terminals: Terminals;
  /** Local-machine only for now (docs/paseo-migration-plan.md, Phase 2) — no remote-agent story yet. */
  private agents: Agents;
  private remote = new RemoteManager();
  /** machineId ("local" or a connected remote) → Terminals bound to that machine's PaseoClient. */
  private terminalsByMachine = new Map<string, Terminals>();
  /** Sockets that have passed hello with a valid device token (or dev mode). */
  private authed = new WeakSet<WebSocket>();

  constructor(cfg: BridgeConfig) {
    this.cfg = cfg;
    this.pairing = new PairingRegistry(cfg.pairingStorePath);
    this.paseo = new PaseoClient({ url: cfg.paseoUrl, password: cfg.paseoPassword });
    this.terminals = new Terminals(this.paseo);
    this.agents = new Agents(this.paseo);
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
    this.paseo.on("paseo-event", (ev) => this.broadcastPaseoEvent(ev));
    this.paseo.on("disconnected", () =>
      this.broadcastEvent("connection", "backend_disconnected", {}),
    );
  }

  async start(): Promise<void> {
    try {
      await this.paseo.connect();
    } catch (e) {
      // The Paseo daemon may start later; clients get backendConnected:false in hello.
      log("warn", "paseo daemon unavailable at startup", { error: String(e) });
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
        await this.ensurePaseo();
        this.send(ws, {
          id: req.id,
          result: {
            protocolVersion: PROTOCOL_VERSION,
            serverVersion: BRIDGE_VERSION,
            authenticated,
            backendProtocol: this.paseo.protocol,
            backendConnected: this.paseo.connected,
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
        await this.ensurePaseo();
        if (!this.paseo.connected) {
          this.send(ws, {
            id: req.id,
            error: err(
              "backend_unavailable",
              "Paseo is not running on this machine.",
              true,
            ),
          });
          return;
        }
        const [terminals, projects] = await Promise.all([
          this.paseo.daemon.listTerminals(),
          this.paseo.daemon.listProjects().catch(() => null),
        ]);
        this.send(ws, {
          id: req.id,
          result: {
            backend: { terminals: terminals.terminals, projects },
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
      case "dir.list": {
        try {
          const files = await this.resolveFiles(req.machineId, req.workspaceId);
          this.send(ws, { id: req.id, result: { entries: await files.listDir(req.path) } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("not_found", "That folder could not be read.", false, String(e)),
          });
        }
        return;
      }
      case "file.open": {
        try {
          const files = await this.resolveFiles(req.machineId, req.workspaceId);
          this.send(ws, { id: req.id, result: await files.openFile(req.path) });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("not_found", "That file could not be opened.", false, String(e)),
          });
        }
        return;
      }
      case "snip.upload": {
        try {
          const dir = join(homedir(), ".aura", "snips");
          await mkdir(dir, { recursive: true });
          const path = join(dir, `snip-${Date.now()}.png`);
          await writeFile(path, Buffer.from(req.pngBase64, "base64"));
          this.send(ws, { id: req.id, result: { path } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("internal", "Could not save that screenshot.", true, String(e)),
          });
        }
        return;
      }
      case "voice.search": {
        const files = await this.resolveFiles(req.machineId, req.workspaceId);
        const started = Date.now();
        const resolution = await files.resolveVoiceQuery(req.query);
        this.send(ws, {
          id: req.id,
          result: { ...resolution, tookMs: Date.now() - started },
        });
        return;
      }
      case "terminal.list":
      case "terminal.create":
      case "terminal.close":
      case "terminal.focus":
      case "terminal.rename":
      case "terminal.read":
      case "terminal.input":
      case "terminal.interrupt": {
        const machineId = req.machineId ?? "local";
        if (machineId === "local") {
          await this.ensurePaseo();
          if (!this.paseo.connected) {
            this.send(ws, {
              id: req.id,
              error: err("backend_unavailable", "Paseo is not running on this machine.", true),
            });
            return;
          }
        } else if (!this.remote.isConnected(machineId)) {
          this.send(ws, {
            id: req.id,
            error: err("backend_unavailable", `Not connected to ${machineId} — connect to it first.`, true),
          });
          return;
        }
        const terminals = this.terminalsFor(machineId);
        if (!terminals) {
          this.send(ws, {
            id: req.id,
            error: err("backend_unavailable", `Not connected to ${machineId}.`, true),
          });
          return;
        }
        try {
          this.send(ws, { id: req.id, result: await this.dispatchTerminal(req, terminals) });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", terminalErrorMessage(req.type), true, String(e)),
          });
        }
        return;
      }
      case "search.files": {
        const files = await this.resolveFiles(req.machineId, req.workspaceId);
        const started = Date.now();
        const hits = await files.search(req.query, req.limit);
        this.send(ws, {
          id: req.id,
          result: { hits, tookMs: Date.now() - started },
        });
        return;
      }
      case "git.status": {
        try {
          const git = await this.gitFor(req.workspaceId, req.machineId);
          this.send(ws, { id: req.id, result: await git.status() });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("internal", "Could not read Git status for that workspace.", true, String(e)),
          });
        }
        return;
      }
      case "git.branch.delete": {
        try {
          const git = await this.gitFor(req.workspaceId, req.machineId);
          await git.deleteBranch(req.branch, req.force ?? false);
          this.send(ws, { id: req.id, result: { deleted: req.branch } });
        } catch (e) {
          const message = String(e);
          const worktreePath = worktreeConflictPath(message);
          this.send(ws, {
            id: req.id,
            error: err(
              "internal",
              worktreePath
                ? `${req.branch} is checked out in a worktree — remove that worktree first.`
                : `Could not delete ${req.branch}.`,
              true,
              message,
            ),
          });
        }
        return;
      }
      case "git.diff": {
        try {
          const git = await this.gitFor(req.workspaceId, req.machineId);
          this.send(ws, { id: req.id, result: { path: req.path, diff: await git.diff(req.path) } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("internal", "Could not diff that file.", true, String(e)),
          });
        }
        return;
      }
      case "git.hunk.apply": {
        try {
          const git = await this.gitFor(req.workspaceId, req.machineId);
          await git.applyHunk(req.path, req.patch, req.action);
          this.send(ws, { id: req.id, result: { path: req.path, action: req.action } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err(
              "internal",
              req.action === "stage"
                ? `Could not stage that hunk in ${req.path}.`
                : `Could not discard that hunk in ${req.path}.`,
              true,
              String(e),
            ),
          });
        }
        return;
      }
      case "git.branches": {
        try {
          const git = await this.gitFor(req.workspaceId, req.machineId);
          this.send(ws, { id: req.id, result: await git.listBranches() });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("internal", "Could not list branches for that workspace.", true, String(e)),
          });
        }
        return;
      }
      case "git.branch.switch": {
        try {
          const git = await this.gitFor(req.workspaceId, req.machineId);
          const { files } = await git.status();
          const dirty = files.length > 0;

          // Spec §27: never switch over uncommitted changes without an
          // explicit resolution — a bare retry-without-onDirty always means
          // "I haven't decided yet", so it always re-reports dirty rather
          // than silently picking one.
          if (dirty && !req.onDirty) {
            this.send(ws, {
              id: req.id,
              error: err(
                "conflict",
                "This branch has uncommitted changes — choose how to handle them.",
                false,
                "dirty",
              ),
            });
            return;
          }

          if (dirty && req.onDirty === "abort") {
            this.send(ws, { id: req.id, result: { switched: false, aborted: true } });
            return;
          }

          // For a remote machine, workspaceId IS the absolute root already
          // (see resolveFiles/gitFor) — resolveWorkspaceRoot only knows how
          // to resolve the bridge's own local Paseo workspace ids.
          const machineId = req.machineId ?? "local";
          const isRemote = machineId !== "local";
          const cwd = isRemote ? req.workspaceId : await this.resolveWorkspaceRoot(req.workspaceId);

          if (dirty && req.onDirty === "worktree") {
            // Never touches the dirty tree at all: the target branch opens
            // in a brand-new worktree instead, so nothing here is at risk.
            // Paseo needs to be told which repo via cwd — it has no notion
            // of the bridge's own "local"/machine workspace ids.
            let client: PaseoClient;
            try {
              client = await this.paseoClientFor(machineId);
            } catch (e) {
              this.send(ws, { id: req.id, error: err("backend_unavailable", String(e), true) });
              return;
            }
            try {
              const created = await client.daemon.createPaseoWorktree({
                cwd,
                refName: req.target,
                action: "checkout",
              });
              if (created.error || !created.workspace) throw new Error(created.error ?? "unknown error");
              this.send(ws, {
                id: req.id,
                result: { switched: false, worktreeCreated: true, workspaceId: created.workspace.id },
              });
            } catch (e) {
              // A worktree for this branch already exists (spec §28's model —
              // branches created via this UI each get their own persistent
              // worktree) — open the existing one instead of failing to make
              // a duplicate.
              const existing = await this.findExistingWorktreeWorkspace(client, cwd, req.target);
              if (existing) {
                this.send(ws, { id: req.id, result: { switched: false, worktreeCreated: true, workspaceId: existing } });
              } else {
                this.send(ws, {
                  id: req.id,
                  error: err("internal", `Could not open a worktree for ${req.target}.`, true, String(e)),
                });
              }
            }
            return;
          }

          if (dirty && req.onDirty === "stash") {
            try {
              await git.stashAndCheckout(req.target);
              this.send(ws, { id: req.id, result: { switched: true, stashed: true, branch: req.target } });
            } catch (e) {
              await this.redirectToExistingWorktreeOrFail(ws, req.id, machineId, cwd, req.target, e);
            }
            return;
          }

          try {
            await git.checkout(req.target);
            this.send(ws, { id: req.id, result: { switched: true, branch: req.target } });
          } catch (e) {
            await this.redirectToExistingWorktreeOrFail(ws, req.id, machineId, cwd, req.target, e);
          }
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("internal", `Could not switch to ${req.target}.`, true, String(e)),
          });
        }
        return;
      }
      case "machine.list": {
        try {
          const machines = await listMachines();
          this.send(ws, {
            id: req.id,
            result: {
              machines: machines.map((m) => ({ ...m, connected: this.remote.isConnected(m.id) })),
            },
          });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("internal", "Could not read the SSH machine registry.", true, String(e)),
          });
        }
        return;
      }
      case "machine.connect": {
        // Only ever tunnel to an alias this bridge itself found in the SSH
        // registry — never an arbitrary client-supplied string, since that
        // string is handed straight to the `ssh` argv (spec §54).
        const known = await listMachines();
        if (!known.some((m) => m.id === req.machineId)) {
          this.send(ws, {
            id: req.id,
            error: err("not_found", `${req.machineId} isn't in the SSH machine registry.`, false),
          });
          return;
        }
        try {
          await this.remote.connect(req.machineId);
          this.send(ws, {
            id: req.id,
            result: {
              connected: true,
              machineId: req.machineId,
              homeDir: this.remote.homeDirFor(req.machineId),
            },
          });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_unavailable", `Could not connect to ${req.machineId}.`, true, String(e)),
          });
        }
        return;
      }
      case "machine.disconnect": {
        this.remote.disconnect(req.machineId);
        this.terminalsByMachine.delete(req.machineId);
        for (const key of [...this.remoteFileServices.keys()]) {
          if (key.startsWith(`${req.machineId}:`)) this.remoteFileServices.delete(key);
        }
        this.send(ws, { id: req.id, result: { connected: false, machineId: req.machineId } });
        return;
      }
      case "agent.list": {
        if (!(await this.requireBackend(ws, req.id))) return;
        try {
          this.send(ws, { id: req.id, result: { agents: await this.agents.list() } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not list agents.", true, String(e)),
          });
        }
        return;
      }
      case "agent.history": {
        if (!(await this.requireBackend(ws, req.id))) return;
        try {
          const result = await this.agents.history({
            search: req.search,
            limit: req.limit,
            cursor: req.cursor,
          });
          this.send(ws, { id: req.id, result });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not load agent history.", true, String(e)),
          });
        }
        return;
      }
      case "agent.create": {
        if (!(await this.requireBackend(ws, req.id))) return;
        try {
          // req.cwd is whatever the client had handy — the XR client in
          // particular has no real filesystem view of its own and just sends
          // ".", which means nothing to Paseo (it'd resolve against the
          // daemon process's own cwd, not the user's project — confirmed
          // live: it landed agents in a random scratch directory). Treat a
          // missing/relative cwd as "the local workspace root" the same way
          // resolveWorkspaceRoot does for files/git, rather than passing it
          // through unresolved.
          const cwd = req.cwd && req.cwd !== "." && req.cwd.startsWith("/")
            ? req.cwd
            : await this.resolveWorkspaceRoot(req.workspaceId ?? "local");
          const agent = await this.agents.create({
            provider: req.provider,
            model: req.model,
            cwd,
            workspaceId: req.workspaceId,
            title: req.title,
            prompt: req.prompt,
            modeId: req.modeId,
          });
          this.send(ws, { id: req.id, result: { agent } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not create that agent.", true, String(e)),
          });
        }
        return;
      }
      case "agent.timeline": {
        if (!(await this.requireBackend(ws, req.id))) return;
        try {
          const entries = await this.agents.timeline(req.agentId, req.limit);
          this.send(ws, { id: req.id, result: { entries } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not load that agent's activity.", true, String(e)),
          });
        }
        return;
      }
      case "agent.send": {
        if (!(await this.requireBackend(ws, req.id))) return;
        try {
          await this.agents.send(req.agentId, req.text);
          this.send(ws, { id: req.id, result: { sent: true } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not send that message.", true, String(e)),
          });
        }
        return;
      }
      case "agent.archive": {
        if (!(await this.requireBackend(ws, req.id))) return;
        try {
          await this.agents.archive(req.agentId);
          this.send(ws, { id: req.id, result: { archived: req.agentId } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not archive that agent.", true, String(e)),
          });
        }
        return;
      }
      case "permission.list": {
        if (!(await this.requireBackend(ws, req.id))) return;
        try {
          this.send(ws, { id: req.id, result: { permissions: await this.agents.pendingPermissions() } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not list permission requests.", true, String(e)),
          });
        }
        return;
      }
      case "permission.respond": {
        if (!(await this.requireBackend(ws, req.id))) return;
        try {
          await this.agents.respondToPermission(
            req.agentId,
            req.permissionRequestId,
            req.behavior,
            req.selectedActionId,
            req.message,
          );
          this.send(ws, { id: req.id, result: { responded: req.permissionRequestId } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not answer that permission request.", true, String(e)),
          });
        }
        return;
      }
      case "provider.list": {
        if (!(await this.requireBackend(ws, req.id))) return;
        try {
          this.send(ws, { id: req.id, result: { providers: await listProviders(this.paseo) } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not list providers.", true, String(e)),
          });
        }
        return;
      }
      case "worktree.list": {
        const machineId = req.machineId ?? "local";
        let client: PaseoClient;
        try {
          client = await this.paseoClientFor(machineId);
        } catch (e) {
          this.send(ws, { id: req.id, error: err("backend_unavailable", String(e), true) });
          return;
        }
        try {
          const isRemote = machineId !== "local";
          const cwd = isRemote ? req.workspaceId : await this.resolveWorkspaceRoot(req.workspaceId);
          this.send(ws, { id: req.id, result: { worktrees: await this.listWorktrees(client, cwd) } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not list worktrees for that workspace.", true, String(e)),
          });
        }
        return;
      }
      case "worktree.open": {
        const machineId = req.machineId ?? "local";
        let client: PaseoClient;
        try {
          client = await this.paseoClientFor(machineId);
        } catch (e) {
          this.send(ws, { id: req.id, error: err("backend_unavailable", String(e), true) });
          return;
        }
        try {
          const { entries } = await client.daemon.fetchWorkspaces({ page: { limit: 200 } });
          const existing = entries.find(
            (e) => e.workspaceDirectory === req.path || e.projectRootPath === req.path,
          );
          const workspaceId =
            existing?.id ??
            (
              await client.daemon.createWorkspace({
                source: { kind: "directory", path: req.path },
              })
            ).workspace?.id;
          if (!workspaceId) {
            this.send(ws, {
              id: req.id,
              error: err("backend_error", `Could not open a workspace at ${req.path}.`, true),
            });
            return;
          }
          this.send(ws, { id: req.id, result: { workspaceId } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", `Could not open ${req.path}.`, true, String(e)),
          });
        }
        return;
      }
      case "worktree.remove": {
        const machineId = req.machineId ?? "local";
        let client: PaseoClient;
        try {
          client = await this.paseoClientFor(machineId);
        } catch (e) {
          this.send(ws, { id: req.id, error: err("backend_unavailable", String(e), true) });
          return;
        }
        try {
          // archivePaseoWorktree's `workspaceId` field alone isn't enough — the
          // live daemon requires worktreePath/worktreeSlug/repoRoot+branchName
          // (confirmed live against paseo 0.7.2); resolve the path first.
          const { entries } = await client.daemon.fetchWorkspaces({ page: { limit: 200 } });
          const workspace = entries.find((e) => e.id === req.workspaceId);
          const worktreePath = workspace?.workspaceDirectory ?? workspace?.projectRootPath;
          if (!worktreePath) {
            throw new Error("That worktree's checkout path could not be resolved.");
          }
          const result = await client.daemon.archivePaseoWorktree({ worktreePath, scope: "worktree" });
          if (!result.success) throw new Error(result.error?.message ?? "unknown error");
          this.send(ws, { id: req.id, result: { removed: req.workspaceId } });
        } catch (e) {
          this.send(ws, {
            id: req.id,
            error: err("backend_error", "Could not remove that worktree.", true, String(e)),
          });
        }
        return;
      }
      default: {
        // Every current XrRequest variant is now handled above — this stays
        // as a forward-compatible fallback for whatever the protocol adds
        // next, hence the casts (req is legitimately `never` here today).
        this.send(ws, {
          id: (req as { id: string }).id,
          error: err(
            "invalid_request",
            `Not implemented yet: ${(req as { type: string }).type}.`,
            false,
          ),
        });
      }
    }
  }

  private async dispatchTerminal(req: XrRequest, terminals: Terminals): Promise<unknown> {
    switch (req.type) {
      case "terminal.list":
        return { terminals: await terminals.list() };
      case "terminal.create": {
        // Same fix as agent.create: an omitted cwd used to fall through to
        // terminals.create's own default of process.cwd() — the bridge
        // process's own directory, not the user's project (confirmed live:
        // a "+ New" terminal opened with prompt "bread@MacBookPro
        // aura-bridge %"). Only fill it in when the caller gave neither cwd
        // nor workspaceId — an explicit one (e.g. an agent's own cwd) is
        // never second-guessed.
        const hasRealCwd = req.cwd && req.cwd !== "." && req.cwd.startsWith("/");
        const cwd = hasRealCwd ? req.cwd : req.workspaceId ? undefined : await this.resolveWorkspaceRoot("local");
        return {
          terminal: await terminals.create({
            workspaceId: req.workspaceId,
            name: req.name,
            cwd,
          }),
        };
      }
      case "terminal.close":
        await terminals.close(req.terminalId);
        return { closed: req.terminalId };
      case "terminal.focus":
        await terminals.focus(req.terminalId);
        return { focused: req.terminalId };
      case "terminal.rename":
        await terminals.rename(req.terminalId, req.name);
        return { renamed: req.terminalId, name: req.name };
      case "terminal.read":
        return terminals.read(req.terminalId, req.source, req.format, req.lines);
      case "terminal.input":
        terminals.sendText(req.terminalId, req.text);
        return { sent: true };
      case "terminal.interrupt":
        // The protocol requires confirmed:true, so reaching here means the XR
        // confirmation card was shown (spec §20, §57).
        terminals.interrupt(req.terminalId);
        return { interrupted: req.terminalId };
      default:
        throw new Error(`unhandled terminal request ${(req as { type: string }).type}`);
    }
  }

  /** Terminals bound to the right machine's PaseoClient — "local" or a connected remote (spec §37). */
  private terminalsFor(machineId: string): Terminals | null {
    if (machineId === "local") return this.terminals;
    const cached = this.terminalsByMachine.get(machineId);
    if (cached) return cached;
    const client = this.remote.clientFor(machineId);
    if (!client) return null;
    const terminals = new Terminals(client);
    this.terminalsByMachine.set(machineId, terminals);
    return terminals;
  }

  /**
   * Resolves a workspaceId to a file service. Paseo workspace ids map to that
   * workspace's cwd (via its terminals); "local" (or unknown ids in dev) map
   * to the bridge's project root.
   */
  private async filesFor(workspaceId: string): Promise<WorkspaceFiles> {
    const cached = this.workspaces.get(workspaceId);
    if (cached) return cached;
    const files = new WorkspaceFiles(await this.resolveWorkspaceRoot(workspaceId));
    this.workspaces.set(workspaceId, files);
    return files;
  }

  /**
   * Routes dir.list/file.open to local or remote (spec §37). For a remote
   * machine, `workspaceId` isn't a Paseo workspace to resolve at all — it's
   * literally the absolute folder the user picked there (e.g. "/tmp/project2"),
   * used directly as both the cache key and the root.
   */
  private async resolveFiles(
    machineId: string | undefined,
    workspaceId: string,
  ): Promise<WorkspaceFiles | RemoteFiles> {
    if (!machineId || machineId === "local") return this.filesFor(workspaceId);
    const key = `${machineId}:${workspaceId}`;
    const cached = this.remoteFileServices.get(key);
    if (cached) return cached;
    if (!this.remote.isConnected(machineId)) {
      throw new Error(`Not connected to ${machineId} — connect to it first.`);
    }
    const files = new RemoteFiles(machineId, workspaceId);
    this.remoteFileServices.set(key, files);
    return files;
  }

  /**
   * Git service for the same workspace root "Code" browses (spec §23 — diff
   * must match what's on screen). Routes to a remote machine the same way
   * [resolveFiles] does: for a remote, `workspaceId` is the literal absolute
   * root, not a Paseo workspace id to resolve.
   */
  private async gitFor(workspaceId: string, machineId?: string): Promise<GitService> {
    if (machineId && machineId !== "local") {
      const key = `${machineId}:${workspaceId}`;
      const cached = this.gitServices.get(key);
      if (cached) return cached;
      if (!this.remote.isConnected(machineId)) {
        throw new Error(`Not connected to ${machineId} — connect to it first.`);
      }
      const git = new GitService(workspaceId, machineId);
      this.gitServices.set(key, git);
      return git;
    }
    const cached = this.gitServices.get(workspaceId);
    if (cached) return cached;
    const git = new GitService(await this.resolveWorkspaceRoot(workspaceId));
    this.gitServices.set(workspaceId, git);
    return git;
  }

  private async resolveWorkspaceRoot(workspaceId: string): Promise<string> {
    let root = this.cfg.projectRoot;
    if (workspaceId !== "local" && this.paseo.connected) {
      try {
        const { entries } = await this.paseo.daemon.fetchWorkspaces({ page: { limit: 200 } });
        const workspace = entries.find((e) => e.id === workspaceId);
        const dir = workspace?.workspaceDirectory ?? workspace?.projectRootPath;
        if (dir) root = dir;
      } catch {
        /* fall back to project root */
      }
    }
    return root;
  }

  /**
   * Looks up the live workspace id for a branch that already has a Paseo
   * worktree checked out. Paseo's worktree list gives a path, not a
   * workspace id directly, so cross-reference it against fetchWorkspaces.
   */
  private async findExistingWorktreeWorkspace(client: PaseoClient, cwd: string, branch: string): Promise<string | null> {
    try {
      const [{ worktrees }, { entries }] = await Promise.all([
        client.daemon.getPaseoWorktreeList({ cwd }),
        client.daemon.fetchWorkspaces({ page: { limit: 200 } }),
      ]);
      const worktree = worktrees.find((w) => w.branchName === branch);
      if (!worktree) return null;
      const workspace = entries.find(
        (e) => e.workspaceDirectory === worktree.worktreePath
          || e.projectRootPath === worktree.worktreePath,
      );
      return workspace?.id ?? null;
    } catch {
      return null;
    }
  }

  /** Full worktree list for a repo, cross-referenced against fetchWorkspaces for each one's live workspace id (spec §26, worktree.list). */
  private async listWorktrees(
    client: PaseoClient,
    cwd: string,
  ): Promise<Array<{ path: string; branch: string | null; openWorkspaceId: string | null }>> {
    const [{ worktrees }, { entries }] = await Promise.all([
      client.daemon.getPaseoWorktreeList({ cwd }),
      client.daemon.fetchWorkspaces({ page: { limit: 200 } }),
    ]);
    return worktrees.map((w) => {
      const workspace = entries.find(
        (e) => e.workspaceDirectory === w.worktreePath || e.projectRootPath === w.worktreePath,
      );
      return {
        path: w.worktreePath,
        branch: w.branchName ?? null,
        openWorkspaceId: workspace?.id ?? null,
      };
    });
  }

  /** Resolves "local" or a connected remote machine id to its PaseoClient, throwing a user-facing message otherwise. */
  private async paseoClientFor(machineId: string): Promise<PaseoClient> {
    if (machineId === "local") {
      await this.ensurePaseo();
      if (!this.paseo.connected) throw new Error("Paseo is not running on this machine.");
      return this.paseo;
    }
    const client = this.remote.clientFor(machineId);
    if (!client) throw new Error(`Not connected to ${machineId} — connect to it first.`);
    return client;
  }

  /**
   * `git checkout` refuses outright if the target branch is already checked
   * out in one of this repo's other worktrees — every branch created through
   * this UI's worktree flow is in exactly that state. Rather than surface
   * that as a dead-end error, redirect to the worktree that's already there.
   */
  private async redirectToExistingWorktreeOrFail(
    ws: WebSocket,
    id: string,
    machineId: string,
    cwd: string,
    target: string,
    error: unknown,
  ): Promise<void> {
    const message = String(error);
    if (worktreeConflictPath(message)) {
      const client = await this.paseoClientFor(machineId).catch(() => null);
      const existing = client ? await this.findExistingWorktreeWorkspace(client, cwd, target) : null;
      if (existing) {
        this.send(ws, {
          id,
          result: { switched: false, worktreeCreated: true, workspaceId: existing, alreadyOpenElsewhere: true },
        });
        return;
      }
    }
    this.send(ws, { id, error: err("internal", `Could not switch to ${target}.`, true, message) });
  }

  /**
   * Shared guard for the local-only `agent.*`/`permission.*`/`provider.*`
   * handlers (docs/paseo-migration-plan.md, Phase 2 — no remote-agent story
   * yet, unlike terminal.* which already routes per machineId). Sends the
   * structured error itself and returns false when the backend isn't up.
   */
  private async requireBackend(ws: WebSocket, id: string): Promise<boolean> {
    await this.ensurePaseo();
    if (!this.paseo.connected) {
      this.send(ws, {
        id,
        error: err("backend_unavailable", "Paseo is not running on this machine.", true),
      });
      return false;
    }
    return true;
  }

  private async ensurePaseo(): Promise<void> {
    if (this.paseo.connected) return;
    try {
      await this.paseo.connect();
      this.broadcastEvent("connection", "backend_connected", {});
    } catch {
      /* stays disconnected; callers check .connected */
    }
  }

  private broadcastPaseoEvent(ev: { type: string; [key: string]: unknown }): void {
    this.broadcastEvent("backend", ev.type, ev);
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
    this.paseo.close();
    this.remote.disconnectAll();
  }
}
