import { test } from "node:test";
import assert from "node:assert/strict";
import {
  PASEO_START_COMMAND,
  connectStartingPaseo,
  isPaseoUnreachable,
  startRemotePaseo,
  type ConnectDeps,
} from "../../src/mcp/index.ts";

const unreachable = new Error(
  "Tunnel to box is up, but Paseo isn't reachable there: Error: Received network error or non-101 status code.",
);

/** Fake connect/run/sleep that records what happened, in order. */
function deps(opts: { connects: Array<Error | null>; run?: (id: string, cmd: string) => Promise<string> }) {
  const log: string[] = [];
  let i = 0;
  const d: ConnectDeps = {
    async connect(id) {
      log.push(`connect ${id}`);
      const outcome = opts.connects[Math.min(i++, opts.connects.length - 1)];
      if (outcome) throw outcome;
    },
    async run(id, cmd) {
      log.push(`run ${id}`);
      return opts.run ? opts.run(id, cmd) : "Started: PID 4242";
    },
    async sleep(ms) {
      log.push(`sleep ${ms}`);
    },
  };
  return { d, log };
}

// ── recognising the case ─────────────────────────────────────────────────

test("a tunnel that is up with no Paseo behind it is recognised; other failures are not", () => {
  assert.equal(isPaseoUnreachable(unreachable), true);
  assert.equal(isPaseoUnreachable("Tunnel to box is up, but Paseo isn't reachable there: x"), true);
  assert.equal(isPaseoUnreachable(new Error("The SSH tunnel to box closed before it came up.")), false);
  assert.equal(isPaseoUnreachable(new Error("ssh: Could not resolve hostname box")), false);
});

// ── the start command ────────────────────────────────────────────────────

test("the start command finds paseo on a non-interactive ssh PATH and starts its daemon", () => {
  assert.match(PASEO_START_COMMAND, /^bash -lc '/);
  assert.match(PASEO_START_COMMAND, /\.nvm\/nvm\.sh/);
  assert.match(PASEO_START_COMMAND, /\.npm-global\/bin/);
  assert.match(PASEO_START_COMMAND, /command -v paseo/);
  assert.match(PASEO_START_COMMAND, /paseo daemon start/);
});

test("starting reports success, a missing paseo, or an ssh failure", async () => {
  assert.deepEqual(await startRemotePaseo("box", async () => "Started: PID 1, listening on 127.0.0.1:6767"), { ok: true });

  const missing = await startRemotePaseo("box", async () => "NO_PASEO\n");
  assert.equal(missing.ok, false);
  assert.match(!missing.ok ? missing.reason : "", /isn't installed on box.*@getpaseo\/cli/);

  const sshDown = await startRemotePaseo("box", async () => {
    throw new Error("ssh to box exited 255");
  });
  assert.equal(sshDown.ok, false);
  assert.match(!sshDown.ok ? sshDown.reason : "", /Couldn't start Paseo on box: ssh to box exited 255/);
});

// ── connect, starting Paseo when needed ──────────────────────────────────

test("a machine that connects first time is left alone", async () => {
  const { d, log } = deps({ connects: [null] });

  assert.deepEqual(await connectStartingPaseo("box", d), { ok: true, startedPaseo: false });
  assert.deepEqual(log, ["connect box"]);
});

test("with no Paseo answering, the daemon is started and the connection retried", async () => {
  const { d, log } = deps({ connects: [unreachable, null] });

  assert.deepEqual(await connectStartingPaseo("box", d), { ok: true, startedPaseo: true });
  assert.deepEqual(log, ["connect box", "run box", "sleep 2000", "connect box"]);
});

test("a machine without paseo installed is reported, not retried", async () => {
  const { d, log } = deps({ connects: [unreachable], run: async () => "NO_PASEO" });
  const result = await connectStartingPaseo("box", d);

  assert.equal(result.ok, false);
  assert.equal(!result.ok && result.status, 502);
  assert.match(!result.ok ? result.error : "", /isn't installed on box/);
  assert.deepEqual(log, ["connect box", "run box"]);
});

test("a daemon that still doesn't answer after starting says where to look", async () => {
  const { d } = deps({ connects: [unreachable, unreachable] });
  const result = await connectStartingPaseo("box", d);

  assert.equal(result.ok, false);
  assert.match(!result.ok ? result.error : "", /Started Paseo on box, but it still isn't answering.*daemon\.log/);
});

test("an ssh or registry failure is reported as it is, without starting anything", async () => {
  const { d, log } = deps({ connects: [new Error("ssh to box exited 255")] });
  const result = await connectStartingPaseo("box", d);

  assert.equal(result.ok, false);
  assert.match(!result.ok ? result.error : "", /Couldn't connect to box: ssh to box exited 255/);
  assert.deepEqual(log, ["connect box"]);
});
