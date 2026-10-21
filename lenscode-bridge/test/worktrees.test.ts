import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { execFileSync } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { BridgeServer } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";
import { DaemonClient } from "@getpaseo/client/internal/daemon-client";
import { removeFixturesFromPaseo } from "./paseoCleanup.ts";

const PORT = 18142;
let server: BridgeServer;
let paseoUp = false;
let repo: string;

function rpc(ws: WebSocket, msg: object): Promise<any> {
  const id = (msg as { id: string }).id;
  return new Promise((resolve, reject) => {
    const onMessage = (raw: WebSocket.RawData) => {
      const parsed = JSON.parse(raw.toString());
      if (parsed.id === id) {
        ws.off("message", onMessage);
        resolve(parsed);
      }
    };
    ws.on("message", onMessage);
    ws.once("error", reject);
    ws.send(JSON.stringify(msg));
  });
}

let seq = 0;
const nextId = () => `wt${++seq}`;

async function connect(): Promise<WebSocket> {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await once(ws, "open");
  const hello = await rpc(ws, {
    id: nextId(),
    type: "hello",
    protocolVersion: 3,
    deviceId: "test-device",
    clientInfo: { app: "test", version: "0", platform: "node" },
  });
  paseoUp = hello.result.backendConnected;
  return ws;
}

before(async () => {
  repo = mkdtempSync(join(tmpdir(), "lenscode-worktree-fixture-"));
  const git = (...args: string[]) => execFileSync("git", args, { cwd: repo });
  git("init", "-q");
  git("config", "user.email", "test@test.com");
  git("config", "user.name", "test");
  execFileSync("sh", ["-c", "echo hi > README.md"], { cwd: repo });
  git("add", "-A");
  git("commit", "-q", "-m", "init");
  git("branch", "feature-x");
  git("branch", "wt-created");

  server = new BridgeServer(loadConfig({ LENSCODE_PORT: String(PORT) } as NodeJS.ProcessEnv));
  await server.start();
});

after(async () => {
  server.close();
  // Take the fixture repo back out of the developer's real Paseo daemon (see paseoCleanup.ts).
  await removeFixturesFromPaseo([repo]);
});

// Requires a live local Paseo daemon (ws://127.0.0.1:6767/ws by default, or
// LENSCODE_PASEO_URL); self-skips otherwise. See docs/paseo-migration-plan.md,
// Phase 3.
test("worktree.list, worktree.open, worktree.remove round-trip against a live Paseo daemon", async (t) => {
  const ws = await connect();
  if (!paseoUp) {
    ws.close();
    return t.skip("paseo daemon not running");
  }

  // Seed one Paseo-owned worktree on the fixture repo directly via the SDK
  // (a separate connection from the bridge's own), then find its resolvable
  // lenscode workspaceId the same way the client does (terminal.create).
  const seed = new DaemonClient({
    url: "ws://127.0.0.1:6767/ws",
    clientId: `lenscode-bridge-test-seed-${Date.now()}`,
    clientType: "cli",
  });
  await seed.connect();
  const created = await seed.createPaseoWorktree({ cwd: repo, refName: "feature-x", action: "checkout" });
  await seed.close();
  assert.ok(created.workspace, `worktree creation failed: ${JSON.stringify(created)}`);

  const terminal = await rpc(ws, { id: nextId(), type: "terminal.create", cwd: repo, name: "wt-test" });
  const workspaceId = terminal.result.terminal.workspaceId;

  // Same lag as noted below for removal — creation doesn't show up in
  // getPaseoWorktreeList instantly either.
  let listed: any;
  for (let attempt = 0; attempt < 10; attempt++) {
    listed = await rpc(ws, { id: nextId(), type: "worktree.list", workspaceId });
    if (listed.result.worktrees.length > 0) break;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  assert.equal(listed.result.worktrees.length, 1, "seeded worktree appears in worktree.list");
  const wt = listed.result.worktrees[0];
  assert.equal(wt.branch, "feature-x");
  assert.ok(wt.openWorkspaceId, "worktree.list resolved its live workspace id");

  const opened = await rpc(ws, { id: nextId(), type: "worktree.open", path: wt.path });
  assert.equal(opened.result.workspaceId, wt.openWorkspaceId, "worktree.open reuses the existing workspace");

  // worktree.create checks out an existing branch in a new Paseo worktree —
  // the same shape the composer's "+ New worktree" picker uses.
  const create = await rpc(ws, { id: nextId(), type: "worktree.create", workspaceId, branch: "wt-created" });
  assert.equal(create.result.created, true);
  assert.ok(create.result.workspaceId, "worktree.create returned the new workspace id");

  // Same creation lag as removal, and worse on paseo 0.10.x — observed ~16s
  // for the second worktree on a repo. Poll generously.
  let listedTwo = 1;
  for (let attempt = 0; attempt < 40 && listedTwo !== 2; attempt++) {
    const relisted = await rpc(ws, { id: nextId(), type: "worktree.list", workspaceId });
    listedTwo = relisted.result.worktrees.length;
    if (listedTwo === 2) break;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  assert.equal(listedTwo, 2, "created worktree appears in worktree.list");

  // Creating again for the same branch redirects to the existing worktree
  // instead of duplicating it. (After the poll above, so the daemon's lagging
  // worktree list already knows the branch — otherwise the redirect lookup
  // can miss it and this races.)
  const dupe = await rpc(ws, { id: nextId(), type: "worktree.create", workspaceId, branch: "wt-created" });
  assert.equal(dupe.result.created, false);
  assert.equal(dupe.result.workspaceId, create.result.workspaceId);

  // Path form: removal works without resolving through a workspace id at all.
  const removed = await rpc(ws, { id: nextId(), type: "worktree.remove", path: wt.path });
  assert.equal(removed.result.removed, wt.path);

  // archivePaseoWorktree does a real `git worktree remove` on disk, and
  // getPaseoWorktreeList lags that change — under this suite's own
  // concurrent load (other files hammering the same live daemon) that lag
  // can run several seconds, so poll generously rather than asserting
  // instant consistency.
  const listCount = async () =>
    (await rpc(ws, { id: nextId(), type: "worktree.list", workspaceId })).result.worktrees.length;
  let worktreesAfter = 2;
  for (let attempt = 0; attempt < 40 && worktreesAfter !== 1; attempt++) {
    worktreesAfter = await listCount();
    if (worktreesAfter === 1) break;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  assert.equal(worktreesAfter, 1, "removed worktree no longer appears");

  // The worktree.create'd one never had a workspace opened through the
  // client — removal by path still works.
  const createdRow = (await rpc(ws, { id: nextId(), type: "worktree.list", workspaceId })).result.worktrees[0];
  assert.equal(createdRow.branch, "wt-created");
  const removed2 = await rpc(ws, { id: nextId(), type: "worktree.remove", path: createdRow.path });
  assert.equal(removed2.result.removed, createdRow.path);
  for (let attempt = 0; attempt < 40 && worktreesAfter !== 0; attempt++) {
    worktreesAfter = await listCount();
    if (worktreesAfter === 0) break;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  assert.equal(worktreesAfter, 0, "created worktree removed cleanly");

  await rpc(ws, { id: nextId(), type: "terminal.close", terminalId: terminal.result.terminal.terminalId });
  ws.close();
});
