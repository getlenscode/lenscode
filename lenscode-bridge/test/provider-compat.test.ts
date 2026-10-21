import { test } from "node:test";
import assert from "node:assert/strict";
import { Agents, listProviders } from "../src/agents.ts";
import type { PaseoClient } from "../src/paseo.ts";

function client(daemon: object): PaseoClient {
  return { daemon } as unknown as PaseoClient;
}

test("provider discovery preserves Pi, Codex and OMP modes independently of model discovery", async () => {
  const modes: Record<string, object[]> = {
    pi: [],
    codex: [{ id: "auto", label: "Default Permissions" }, { id: "full-access", label: "Full Access", colorTier: "dangerous" }],
    omp: [{ id: "ask", label: "Always Ask" }, { id: "write", label: "Write Approval" }, { id: "full", label: "Full Access", colorTier: "dangerous" }],
  };
  const providers = await listProviders(client({
    listAvailableProviders: async () => ({ providers: ["pi", "codex", "omp"].map((provider) => ({ provider, available: true })) }),
    listProviderModels: async (provider: string) => {
      if (provider === "omp") throw new Error("model catalog unavailable");
      return { models: [{ id: `${provider}-model` }] };
    },
    listProviderModes: async (provider: string) => ({ modes: modes[provider] }),
  }));
  assert.deepEqual(providers.map((p) => p.modes?.map((m) => m.id)), [[], ["auto", "full-access"], ["ask", "write", "full"]]);
  assert.equal(providers[1].modes?.[1].dangerous, true);
  assert.equal(providers[2].modes?.[2].dangerous, true);
  assert.deepEqual(providers[2].models, []);
  assert.equal(providers[2].available, true);
});

test("unavailable providers do not trigger catalog calls; mode failure preserves models", async () => {
  const providers = await listProviders(client({
    listAvailableProviders: async () => ({ providers: [{ provider: "omp", available: false }, { provider: "pi", available: true }] }),
    listProviderModels: async (provider: string) => {
      assert.equal(provider, "pi");
      return { models: [{ id: "provider/model" }] };
    },
    listProviderModes: async (provider: string) => {
      assert.equal(provider, "pi");
      throw new Error("catalog unavailable");
    },
  }));
  assert.deepEqual(providers[0], { provider: "omp", label: "omp", available: false, models: [], modes: [] });
  assert.deepEqual(providers[1].models, ["provider/model"]);
  assert.deepEqual(providers[1].modes, []);
});

for (const [provider, modeId] of [["pi", undefined], ["codex", "auto"], ["omp", "ask"]] as const) {
  test(`${provider} creation forwards its native config and supports follow-up/history/permissions/archive`, async () => {
    const calls: string[] = [];
    const snapshot = { id: "a1", provider, model: "model", title: "test", status: "idle", cwd: "/project", pendingPermissions: [] };
    const agents = new Agents(client({
      createAgent: async (request: any) => {
        assert.deepEqual(request, { config: { provider, cwd: "/project", model: "model", ...(modeId ? { modeId } : {}) }, initialPrompt: "hello" });
        calls.push("create"); return snapshot;
      },
      sendAgentMessage: async (id: string, text: string) => { assert.equal(id, "a1"); assert.equal(text, "follow-up"); calls.push("send"); },
      fetchAgentTimeline: async () => ({ entries: [{ timestamp: "2026-10-21T00:00:00Z", item: { type: "assistant_message", text: "done" } }] }),
      respondToPermission: async (id: string, permissionId: string, response: object) => {
        assert.equal(id, "a1"); assert.equal(permissionId, "p1"); assert.deepEqual(response, { behavior: "allow", selectedActionId: "allow_once" }); calls.push("permission");
      },
      archiveAgent: async () => { calls.push("archive"); },
    }));
    assert.equal((await agents.create({ provider, modeId, cwd: "/project", model: "model", prompt: "hello" })).provider, provider);
    await agents.send("a1", "follow-up");
    assert.equal((await agents.timeline("a1"))[0].text, "done");
    await agents.respondToPermission("a1", "p1", "allow", "allow_once");
    await agents.archive("a1");
    assert.deepEqual(calls, ["create", "send", "permission", "archive"]);
  });
}
