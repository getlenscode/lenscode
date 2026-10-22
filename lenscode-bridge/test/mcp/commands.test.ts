import { test } from "node:test";
import assert from "node:assert/strict";
import { expandHome, shellQuote, validateControl } from "../../src/mcp/index.ts";

// ── validation ───────────────────────────────────────────────────────────

test("unknown commands and non-objects are refused", () => {
  assert.equal(validateControl(null).ok, false);
  assert.equal(validateControl({ command: "rm -rf" }).ok, false);
});

test("open_url takes web addresses only", () => {
  assert.deepEqual(validateControl({ command: "open_url", args: { url: "https://x.dev" } }), {
    ok: true,
    command: "open_url",
    args: { url: "https://x.dev" },
  });
  assert.equal(validateControl({ command: "open_url", args: { url: "192.168.1.5:3000" } }).ok, true);
  assert.equal(validateControl({ command: "open_url", args: { url: "localhost:9000/x" } }).ok, true);
  assert.equal(validateControl({ command: "open_url", args: { url: "example.com" } }).ok, true);
  for (const url of ["javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end"]) {
    assert.equal(validateControl({ command: "open_url", args: { url } }).ok, false, url);
  }
  assert.equal(validateControl({ command: "open_url", args: {} }).ok, false);
});

test("open_tmux only accepts session names that are safe to type into a shell", () => {
  assert.equal(validateControl({ command: "open_tmux", args: { session: "lc-ab12" } }).ok, true);
  assert.equal(validateControl({ command: "open_tmux", args: { session: "web.dev_2" } }).ok, true);
  for (const session of ["a b", "x;rm -rf ~", "$(id)", "a`b`", "", "x".repeat(65)]) {
    assert.equal(validateControl({ command: "open_tmux", args: { session } }).ok, false, session);
  }
});

test("a machine id that looks like an ssh option is refused", () => {
  assert.equal(validateControl({ command: "open_tmux", args: { session: "s", machineId: "-oProxyCommand=x" } }).ok, false);
});

test("arguments a command does not use are dropped", () => {
  assert.deepEqual(validateControl({ command: "show_view", args: { view: "code", extra: "x" } }), {
    ok: true,
    command: "show_view",
    args: { view: "code" },
  });
  assert.equal(validateControl({ command: "show_view", args: { view: "nope" } }).ok, false);
});

test("list_tmux defaults to this machine and notify is capped at 300 characters", () => {
  assert.deepEqual(validateControl({ command: "list_tmux" }), { ok: true, command: "list_tmux", args: { machineId: "local" } });
  const v = validateControl({ command: "notify", args: { message: "x".repeat(500) } });
  assert.equal(v.ok && v.args.message.length, 300);
});

test("open_project needs a machine and a folder, and keeps ~ for the bridge to resolve", () => {
  assert.deepEqual(validateControl({ command: "open_project", args: { machineId: "doraemon_arch", path: "~/Documents/app" } }), {
    ok: true,
    command: "open_project",
    args: { machineId: "doraemon_arch", path: "~/Documents/app" },
  });
  assert.equal(validateControl({ command: "open_project", args: { path: "~/x" } }).ok, false);
  assert.equal(validateControl({ command: "open_project", args: { machineId: "box" } }).ok, false);
  assert.equal(validateControl({ command: "open_project", args: { machineId: "-oProxyCommand=x", path: "/x" } }).ok, false);
});

test("~ and relative paths resolve under the machine's home; absolute paths are only tidied", () => {
  assert.equal(expandHome("~", "/home/me"), "/home/me");
  assert.equal(expandHome("~/Documents/app", "/home/me/"), "/home/me/Documents/app");
  assert.equal(expandHome("Documents/app", "/home/me"), "/home/me/Documents/app");
  assert.equal(expandHome("/srv/app/", "/home/me"), "/srv/app");
  assert.equal(expandHome("/", "/home/me"), "/");
});

test("a path is quoted as one shell word, quotes included", () => {
  assert.equal(shellQuote("/a b/c"), "'/a b/c'");
  assert.equal(shellQuote("it's"), `'it'\\''s'`);
  assert.equal(shellQuote("$(id); rm -rf ~"), "'$(id); rm -rf ~'");
});
