// Removes leftover bridge-test fixtures from the local Paseo daemon. Older runs of
// test/worktrees.test.ts registered a throwaway repo (lenscode-worktree-fixture-*) and a
// terminal with the daemon and never took them out, which inflated the Home screen's
// "workspaces" and "panes" counts. The test now cleans up after itself; this clears
// what it left behind.
//
//   node scripts/prune-paseo-test-fixtures.mjs                dry run: lists what would go and what stays
//   node scripts/prune-paseo-test-fixtures.mjs --apply        removes them
//   node scripts/prune-paseo-test-fixtures.mjs --apply --limit=1   removes only one project first, to check
//
// Only projects rooted in the OS temp dir AND named like a test fixture are removed, and
// only terminals in such directories (or named wt-test/dbg in the temp dir). Everything
// else is printed as "keep" and left alone. Set LENSCODE_PASEO_URL for a non-default daemon.
import { DaemonClient } from "../node_modules/@getpaseo/client/dist/daemon-client.js";

const apply = process.argv.includes("--apply");
const limitArg = process.argv.find((a) => a.startsWith("--limit="));
const limit = limitArg ? Number(limitArg.split("=")[1]) : Infinity;
const url = process.env.LENSCODE_PASEO_URL ?? "ws://127.0.0.1:6767/ws";

const TMP = /^\/(private\/)?var\/folders\//;
const FIXTURE = /\/(lenscode-worktree-fixture|wt-dbg\d*)-[A-Za-z0-9]{6}$/;
const isFixtureDir = (p) => typeof p === "string" && TMP.test(p) && FIXTURE.test(p);
const short = (s) => String(s ?? "").replace(process.env.HOME ?? "\0", "~");

const client = new DaemonClient({ url, clientId: `prune-${Date.now()}`, clientType: "cli" });
await client.connect();
try {
  const projects = (await client.listProjects()).projects;
  const terminals = (await client.listTerminals()).terminals;

  const dropProjects = projects.filter((p) => isFixtureDir(p.projectRootPath));
  const keepProjects = projects.filter((p) => !dropProjects.includes(p));
  const dropTerminals = terminals.filter(
    (t) => isFixtureDir(t.cwd) || (TMP.test(t.cwd ?? "") && /^(wt-test|dbg)$/.test(t.name ?? "")),
  );
  const keepTerminals = terminals.filter((t) => !dropTerminals.includes(t));

  console.log(`projects:  ${projects.length} total -> remove ${dropProjects.length}, keep ${keepProjects.length}`);
  for (const p of keepProjects) console.log(`  keep project:  ${p.projectDisplayName} | ${short(p.projectRootPath)}`);
  console.log(`terminals: ${terminals.length} total -> remove ${dropTerminals.length}, keep ${keepTerminals.length}`);
  for (const t of keepTerminals) console.log(`  keep terminal: ${t.name} | ${short(t.cwd)}`);

  if (!apply) {
    console.log("\nDRY RUN — nothing was changed. Re-run with --apply to remove the items above.");
  } else {
    let killed = 0;
    let removed = 0;
    for (const t of dropTerminals) {
      await client.killTerminal(t.id).then(() => killed++, (e) => console.log(`could not kill ${t.id}: ${e}`));
    }
    for (const p of dropProjects.slice(0, limit)) {
      await client.removeProject(p.projectId).then(() => removed++, (e) => console.log(`could not remove ${p.projectId}: ${e}`));
    }
    console.log(`\nkilled ${killed} terminals, removed ${removed} projects`);
  }
} finally {
  await client.close();
}
