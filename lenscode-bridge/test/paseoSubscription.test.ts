import { test } from "node:test";
import assert from "node:assert/strict";
import { PaseoClient } from "../src/paseo.ts";

/** A DaemonClient stand-in that records agent subscriptions instead of talking to a daemon. */
function fakeDaemon(opts: { subscriptionFails?: boolean } = {}) {
  const calls = { connect: 0, observeAgents: 0, released: 0, closed: 0 };
  const daemon = {
    isConnected: true,
    async connect() { calls.connect++; },
    async getDaemonStatus() { return { version: "0.10.2" }; },
    observeAgents() {
      calls.observeAgents++;
      return {
        ready: opts.subscriptionFails ? Promise.reject(new Error("no subscription")) : Promise.resolve({}),
        async release() { calls.released++; },
      };
    },
    async close() { calls.closed++; },
  };
  return { daemon, calls };
}

function clientWith(daemon: unknown): PaseoClient {
  const paseo = new PaseoClient({ url: "ws://127.0.0.1:1/ws" });
  (paseo as unknown as { client: unknown }).client = daemon;
  return paseo;
}

test("connecting subscribes to agent updates so the daemon pushes permission and state changes", async () => {
  const { daemon, calls } = fakeDaemon();
  const paseo = clientWith(daemon);

  await paseo.connect();

  assert.equal(calls.observeAgents, 1);
});

test("connecting again does not stack a second subscription", async () => {
  const { daemon, calls } = fakeDaemon();
  const paseo = clientWith(daemon);

  await paseo.connect();
  await paseo.connect();

  assert.equal(calls.observeAgents, 1);
});

test("a failed subscription does not fail the connection and is retried on the next connect", async () => {
  const { daemon, calls } = fakeDaemon({ subscriptionFails: true });
  const paseo = clientWith(daemon);

  await assert.doesNotReject(paseo.connect());
  await new Promise((resolve) => setImmediate(resolve)); // let the rejection clear the stale subscription
  await paseo.connect();

  assert.equal(calls.observeAgents, 2);
});

test("closing releases the subscription and closes the client", async () => {
  const { daemon, calls } = fakeDaemon();
  const paseo = clientWith(daemon);
  await paseo.connect();

  paseo.close();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(calls.released, 1);
  assert.equal(calls.closed, 1);
});

test("closing before ever subscribing still closes the client", async () => {
  const { daemon, calls } = fakeDaemon();
  const paseo = clientWith(daemon);

  paseo.close();

  assert.equal(calls.released, 0);
  assert.equal(calls.closed, 1);
});
