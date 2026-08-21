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
    clientInfo: { app: "aura-xr", version: "0.1.0", platform: "android-xr" },
  };
  const parsed = XrRequest.parse(msg);
  assert.equal(parsed.type, "hello");
});

test("herdr proxy request validates native envelope", () => {
  const msg = {
    id: "r2",
    type: "herdr.request",
    request: { id: "52", method: "agent.list", params: {} },
  };
  const parsed = XrRequest.parse(msg);
  assert.equal(parsed.type, "herdr.request");
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
      code: "herdr_unavailable",
      message: "Herdr is not running on this machine.",
      retryable: true,
    },
  });
  assert.ok("error" in parsed);
});

test("event envelope carries topic and seq", () => {
  const parsed = XrServerMessage.parse({
    type: "event",
    topic: "herdr",
    event: "pane_created",
    data: { pane_id: "p1" },
    seq: 7,
  });
  assert.ok("seq" in parsed);
});
