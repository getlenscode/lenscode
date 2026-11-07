import { test } from "node:test";
import assert from "node:assert/strict";
import { buildAdvert } from "../src/discovery.ts";

test("advert names the host and carries port, scheme and fingerprint", () => {
  const a = buildAdvert({ host: "MacBook", port: 8443, secure: true, version: "0.1.0", fingerprint: "510D-4148" });
  assert.equal(a.name, "LensCode bridge (MacBook)");
  assert.equal(a.port, 8443);
  assert.deepEqual(a.txt, { v: "0.1.0", secure: "1", fp: "510D-4148" });
});

test("a plain bridge advertises secure=0 and no fingerprint", () => {
  const a = buildAdvert({ host: "h", port: 8137, secure: false, version: "0.1.0" });
  assert.deepEqual(a.txt, { v: "0.1.0", secure: "0" });
});
