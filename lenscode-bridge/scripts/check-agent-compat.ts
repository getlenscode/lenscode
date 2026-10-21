/** Opt-in live smoke test. Sends two short prompts per provider and archives its own agents. */
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PaseoClient } from "../src/paseo.ts";
import { Agents, listProviders } from "../src/agents.ts";

const paseo = new PaseoClient({ url: process.env.LENSCODE_PASEO_URL ?? "ws://127.0.0.1:6767/ws", password: process.env.LENSCODE_PASEO_PASSWORD });
const agents = new Agents(paseo);
const cwd = await mkdtemp(join(tmpdir(), "lenscode-agent-compat-"));
let failed = false;
try {
  await paseo.connect();
  const providers = await listProviders(paseo);
  for (const provider of process.argv.slice(2).length ? process.argv.slice(2) : ["pi", "codex", "omp"]) {
    if (!providers.some((p) => p.provider === provider && p.available)) {
      console.error(`${provider}: UNAVAILABLE (install/authenticate the CLI and enable the Paseo provider)`);
      failed = true;
      continue;
    }
    let agentId: string | undefined;
    try {
      const modeId = provider === "codex" ? "auto" : provider === "omp" ? "ask" : undefined;
      const created = await agents.create({ provider, cwd, modeId, title: "LensCode compatibility smoke test" });
      agentId = created.agentId;
      for (const token of ["LENSCODE_COMPAT_OK", "LENSCODE_FOLLOWUP_OK"]) {
        await agents.send(agentId, `Reply with exactly ${token}. Do not call tools or change any files.`);
        const deadline = Date.now() + 120_000;
        let replied = false;
        while (Date.now() < deadline) {
          const snapshot = (await agents.list()).find((a) => a.agentId === agentId);
          if (snapshot?.status === "error" || snapshot?.lastError) throw new Error(snapshot.lastError ?? "Agent entered error state");
          const timeline = await agents.timeline(agentId);
          if (timeline.some((e) => e.kind === "assistant_message" && e.text?.includes(token))) {
            replied = true;
            break;
          }
          await new Promise((resolve) => setTimeout(resolve, 1000));
        }
        if (!replied) throw new Error(`Timed out waiting for ${token}`);
      }
      console.log(`${provider}: PASS (create, list, prompt, reply, follow-up, timeline)`);
    } catch (error) {
      failed = true;
      console.error(`${provider}: FAIL ${String(error)}`);
    } finally {
      if (agentId) await agents.archive(agentId);
    }
  }
} finally {
  paseo.close();
  await rm(cwd, { recursive: true, force: true });
}
process.exitCode = failed ? 1 : 0;
