import { test } from "node:test";
import assert from "node:assert/strict";
import { isTailscaleIp, tailscaleAddresses, parseMagicDnsName } from "../src/tailscale.ts";

const nic = (address: string, extra = {}) => ({ address, family: "IPv4", internal: false, ...extra }) as any;

test("only 100.64.0.0/10 counts as a Tailscale address", () => {
  assert.equal(isTailscaleIp("100.96.103.69"), true);
  assert.equal(isTailscaleIp("100.64.0.1"), true);
  assert.equal(isTailscaleIp("100.127.255.254"), true);
  assert.equal(isTailscaleIp("100.63.0.1"), false);
  assert.equal(isTailscaleIp("100.128.0.1"), false);
  assert.equal(isTailscaleIp("192.168.1.157"), false);
});

test("finds the Tailscale address among the interfaces", () => {
  const found = tailscaleAddresses({
    en0: [nic("192.168.1.157")],
    utun4: [nic("100.96.103.69")],
    lo0: [nic("100.64.0.9", { internal: true })],
    utun5: [{ address: "fd7a::1", family: "IPv6", internal: false } as any],
  });
  assert.deepEqual(found, ["100.96.103.69"]);
});

test("reads the MagicDNS name without its trailing dot", () => {
  assert.equal(parseMagicDnsName('{"Self":{"DNSName":"my-mac.tail1234.ts.net."}}'), "my-mac.tail1234.ts.net");
  assert.equal(parseMagicDnsName("not json"), undefined);
  assert.equal(parseMagicDnsName("{}"), undefined);
});
