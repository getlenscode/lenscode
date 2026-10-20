import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { applyCliArgs, USAGE } from "../src/cli.ts";
import { BRIDGE_VERSION } from "../src/server.ts";
import { loadConfig } from "../src/config.ts";

const run = (argv: string[], env: NodeJS.ProcessEnv = {}) => {
  const result = applyCliArgs(argv, env, "9.9.9");
  return { result, env };
};

test("no arguments just runs, leaving the environment alone", () => {
  const { result, env } = run([]);
  assert.deepEqual(result, { action: "run" });
  assert.deepEqual(env, {});
});

test("flags become the LENSCODE_* variables loadConfig reads", () => {
  const { result, env } = run(["--port", "9000", "--paseo-url=ws://10.0.0.5:6767/ws", "--project-root", "/work"]);
  assert.equal(result.action, "run");
  const cfg = loadConfig(env);
  assert.equal(cfg.port, 9000);
  assert.equal(cfg.paseoUrl, "ws://10.0.0.5:6767/ws");
  assert.equal(cfg.projectRoot, "/work");
  assert.equal(cfg.mode, "dev");
});

test("giving a certificate and key turns on prod mode", () => {
  const { result, env } = run(["--tls-cert", "/c/full.pem", "--tls-key", "/c/key.pem"]);
  assert.equal(result.action, "run");
  const cfg = loadConfig(env);
  assert.equal(cfg.mode, "prod");
  assert.equal(cfg.tlsCertPath, "/c/full.pem");
  assert.equal(cfg.tlsKeyPath, "/c/key.pem");
});

test("an explicit LENSCODE_MODE is not overridden by the TLS flags", () => {
  const { env } = run(["--tls-cert", "a", "--tls-key", "b"], { LENSCODE_MODE: "dev" });
  assert.equal(env.LENSCODE_MODE, "dev");
});

test("--help and --version exit cleanly with their text", () => {
  assert.deepEqual(run(["--help"]).result, { action: "exit", code: 0, message: USAGE });
  assert.deepEqual(run(["-v"]).result, { action: "exit", code: 0, message: "9.9.9\n" });
});

test("mistakes exit non-zero with an explanation", () => {
  const unknown = run(["--prot", "1"]).result;
  assert.equal(unknown.action === "exit" && unknown.code, 2);
  assert.match(unknown.action === "exit" ? unknown.message : "", /Unknown option: --prot/);

  const missing = run(["--port"]).result;
  assert.match(missing.action === "exit" ? missing.message : "", /--port needs a value/);

  const badPort = run(["--port", "http"]).result;
  assert.match(badPort.action === "exit" ? badPort.message : "", /must be a number/);

  const half = run(["--tls-cert", "only-a-cert.pem"]).result;
  assert.match(half.action === "exit" ? half.message : "", /together/);
});

test("the version in the code matches package.json (the published version)", () => {
  const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
  assert.equal(BRIDGE_VERSION, pkg.version);
});
