import { test } from "node:test";
import assert from "node:assert/strict";
import {
  PROTOCOL_VERSION,
  XrRequest,
  XrServerMessage,
} from "../src/index.ts";

test("hello request round-trips", () => {
  const msg = {
    id: "r1",
    type: "hello",
    protocolVersion: PROTOCOL_VERSION,
    deviceId: "dev-123",
    clientInfo: { app: "lenscode-xr", version: "0.1.0", platform: "android-xr" },
  };
  const parsed = XrRequest.parse(msg);
  assert.equal(parsed.type, "hello");
});

test("terminal.close addresses a terminal by id", () => {
  const msg = {
    id: "r2",
    type: "terminal.close",
    terminalId: "term-52",
  };
  const parsed = XrRequest.parse(msg);
  assert.equal(parsed.type, "terminal.close");
});

test("unknown request type is rejected", () => {
  assert.throws(() =>
    XrRequest.parse({ id: "r3", type: "server.stop", params: {} }),
  );
});

test("search.files applies default limit", () => {
  const parsed = XrRequest.parse({
    id: "r4",
    type: "search.files",
    workspaceId: "ws-1",
    query: "authentication middleware",
  });
  assert.equal(parsed.type, "search.files");
  if (parsed.type === "search.files") assert.equal(parsed.limit, 20);
});

test("server error message is structured", () => {
  const parsed = XrServerMessage.parse({
    id: "r5",
    error: {
      code: "backend_unavailable",
      message: "Paseo is not running on this machine.",
      retryable: true,
    },
  });
  assert.ok("error" in parsed);
});

test("agent.create requires provider and cwd", () => {
  const parsed = XrRequest.parse({
    id: "r6",
    type: "agent.create",
    provider: "claude",
    cwd: "/Users/me/dev/storefront",
    prompt: "Review the checkout flow.",
  });
  assert.equal(parsed.type, "agent.create");
  assert.throws(() =>
    XrRequest.parse({ id: "r7", type: "agent.create", cwd: "/tmp" }),
  );
});

test("permission.respond carries the answered request id", () => {
  const parsed = XrRequest.parse({
    id: "r8",
    type: "permission.respond",
    agentId: "agent-1",
    permissionRequestId: "perm-1",
    behavior: "allow",
  });
  assert.equal(parsed.type, "permission.respond");
});

test("event envelope carries topic and seq", () => {
  const parsed = XrServerMessage.parse({
    type: "event",
    topic: "backend",
    event: "terminals_changed",
    data: { terminalId: "term-1" },
    seq: 7,
  });
  assert.ok("seq" in parsed);
});
