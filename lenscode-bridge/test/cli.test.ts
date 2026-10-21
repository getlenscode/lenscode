import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { applyCliArgs, USAGE } from "../src/cli.ts";
import { BRIDGE_VERSION } from "../src/server.ts";
import { defaultPort, loadConfig } from "../src/config.ts";

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

test("--secure turns on secure mode and refuses to be mixed with your own certificate", () => {
  const { result, env } = run(["--secure"]);
  assert.equal(result.action, "run");
  assert.equal(env.LENSCODE_SECURE, "1");
  assert.equal(env.LENSCODE_MODE, "prod");
  const mixed = run(["--secure", "--tls-cert", "a", "--tls-key", "b"]).result;
  assert.match(mixed.action === "exit" ? mixed.message : "", /don't combine/);
});

test("plain mode defaults to 8137 and secure mode to 8443, unless --port says otherwise", () => {
  assert.equal(defaultPort("dev"), 8137);
  assert.equal(defaultPort("prod"), 8443);
  assert.equal(loadConfig(run([]).env).port, 8137);

  const withCertificate = (argv: string[]) => {
    const { env } = run(argv);
    // main.ts fills these in after creating the --secure certificate, before loadConfig
    env.LENSCODE_TLS_CERT = "cert.pem";
    env.LENSCODE_TLS_KEY = "key.pem";
    return loadConfig(env);
  };
  assert.equal(withCertificate(["--secure"]).port, 8443);
  assert.equal(withCertificate(["--secure"]).mode, "prod");
  assert.equal(withCertificate(["--secure", "--port", "9000"]).port, 9000);
  const own = run(["--tls-cert", "a.pem", "--tls-key", "b.pem"]);
  assert.equal(loadConfig(own.env).port, 8443);
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
