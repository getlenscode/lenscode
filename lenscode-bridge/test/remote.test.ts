import { test } from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { RemoteManager, type Connection } from "../src/remote.ts";

/** Stands in for a PaseoClient: a connected flag, close(), and the paseo-event emitter. */
class FakeClient extends EventEmitter {
  connected = true;
  closed = false;
  close() {
    this.closed = true;
  }
}

function fakeConnection() {
  const client = new FakeClient();
  const tunnel = { killed: false, kill() { this.killed = true; return true; } };
  const conn = { tunnel, client, localPort: 20001, homeDir: "/home/x" } as unknown as Connection;
  return { client, tunnel, conn };
}

test("a machine whose Paseo client is connected is listed and usable", () => {
  const mgr = new RemoteManager();
  const { client, conn } = fakeConnection();
  mgr.register("box", conn);

  assert.equal(mgr.isConnected("box"), true);
  assert.deepEqual(mgr.connectedIds(), ["box"]);
  assert.equal(mgr.clientFor("box"), client as never);
  assert.equal(mgr.homeDirFor("box"), "/home/x");
});

test("a dropped Paseo client counts as disconnected even though the tunnel entry is still there", () => {
  const mgr = new RemoteManager();
  const { client, conn } = fakeConnection();
  mgr.register("box", conn);
  client.connected = false; // the remote daemon restarted; the SSH tunnel stayed up

  assert.equal(mgr.isConnected("box"), false);
  assert.deepEqual(mgr.connectedIds(), []);
  assert.equal(mgr.clientFor("box"), null);
});

test("local and unknown machines are never connected remotes", () => {
  const mgr = new RemoteManager();
  assert.equal(mgr.isConnected("local"), false);
  assert.equal(mgr.clientFor("local"), null);
  assert.equal(mgr.isConnected("nope"), false);
  assert.equal(mgr.clientFor("nope"), null);
});

test("disconnect closes the client, kills the tunnel and forgets the machine", () => {
  const mgr = new RemoteManager();
  const { client, tunnel, conn } = fakeConnection();
  mgr.register("box", conn);

  mgr.disconnect("box");

  assert.equal(client.closed, true);
  assert.equal(tunnel.killed, true);
  assert.equal(mgr.isConnected("box"), false);
});

test("connect() tears down a dead connection before dialing again", async () => {
  const mgr = new RemoteManager();
  const { client, tunnel, conn } = fakeConnection();
  mgr.register("lenscode-test-nohost.invalid", conn);
  client.connected = false;

  // The new dial fails (the host does not resolve), but the stale connection must already be gone.
  await assert.rejects(mgr.connect("lenscode-test-nohost.invalid"));

  assert.equal(client.closed, true);
  assert.equal(tunnel.killed, true);
  assert.equal(mgr.isConnected("lenscode-test-nohost.invalid"), false);
});

test("connect() leaves a live connection alone", async () => {
  const mgr = new RemoteManager();
  const { client, tunnel, conn } = fakeConnection();
  mgr.register("box", conn);

  await mgr.connect("box"); // already alive: resolves without touching anything

  assert.equal(client.closed, false);
  assert.equal(tunnel.killed, false);
  assert.equal(mgr.isConnected("box"), true);
});

test("a remote machine's Paseo events are relayed with its machine id", () => {
  const mgr = new RemoteManager();
  const seen: Array<[string, string]> = [];
  mgr.onEvent = (machineId, event) => seen.push([machineId, event.type]);
  const a = fakeConnection();
  const b = fakeConnection();
  mgr.register("a", a.conn);
  mgr.register("b", b.conn);

  a.client.emit("paseo-event", { type: "agent_state" });
  b.client.emit("paseo-event", { type: "permission_requested" });

  assert.deepEqual(seen, [["a", "agent_state"], ["b", "permission_requested"]]);
});

test("remote events are dropped quietly when nothing is listening", () => {
  const mgr = new RemoteManager();
  const { client, conn } = fakeConnection();
  mgr.register("box", conn);

  assert.doesNotThrow(() => client.emit("paseo-event", { type: "agent_state" }));
});
