import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readBoard, createTask, updateTask, toCard } from "../src/tasks.ts";

// A stand-in for the plugin's loopback /cli endpoint: records argv, answers from a table.
let server: Server;
const calls: string[][] = [];
let answers: (argv: string[]) => unknown;

before(async () => {
  server = createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      assert.equal(req.headers.authorization, "Bearer tok");
      const { argv } = JSON.parse(body) as { argv: string[] };
      calls.push(argv);
      try {
        res.end(JSON.stringify({ exitCode: 0, stdout: JSON.stringify(answers(argv)) }));
      } catch (e) {
        res.end(JSON.stringify({ exitCode: 1, stderr: String(e) }));
      }
    });
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const dir = await mkdtemp(join(tmpdir(), "tasks-"));
  await writeFile(join(dir, "bridge.json"), JSON.stringify({ url: `http://127.0.0.1:${(server.address() as any).port}`, token: "tok" }));
  process.env.PASEO_TASKS_DATA_DIR = dir;
});
after(() => server.close());

const task = (key: string, status: string, extra = {}) => ({ key, title: `T ${key}`, status, priority: "high", description: "d", labels: ["x"], agentsWorking: 0, ...extra });

test("board joins projects, presets, tasks and attached agent threads", async () => {
  calls.length = 0;
  answers = (argv) => {
    if (argv[0] === "project") return { projects: [{ prefix: "ABC", name: "App" }] };
    if (argv[0] === "preset") return { presets: [{ name: "Claude", providerId: "claude", modelId: "m", environmentKind: "new-worktree", instructions: "do it" }] };
    if (argv[0] === "threads") return { taskThreads: [{ threadId: "agent-1", presetName: "Claude", liveStatus: "working" }] };
    return { tasks: [task("ABC-1", "in_progress", { agentsWorking: 1 }), task("ABC-2", "done")] };
  };
  const board = await readBoard();
  assert.equal(board.available, true);
  assert.deepEqual(board.projects, [{ prefix: "ABC", name: "App" }]);
  assert.equal(board.tasks[0]!.threads[0]!.threadId, "agent-1");
  // Finished tasks don't cost a threads call.
  assert.deepEqual(board.tasks[1]!.threads, []);
  assert.equal(calls.filter((c) => c[0] === "threads").length, 1);
  assert.ok(calls.every((c) => c.at(-1) === "--json"));
});

test("missing plugin is reported as unavailable, not an error", async () => {
  const saved = process.env.PASEO_TASKS_DATA_DIR;
  process.env.PASEO_TASKS_DATA_DIR = "/nonexistent/dir";
  const board = await readBoard();
  process.env.PASEO_TASKS_DATA_DIR = saved;
  assert.equal(board.available, false);
  assert.match(board.reason!, /paseo plugin add/);
});

test("create picks the only project, then applies a status the CLI can't set on create", async () => {
  calls.length = 0;
  answers = (argv) => {
    if (argv[0] === "project") return { projects: [{ prefix: "ABC", name: "App" }] };
    if (argv[0] === "create") return { task: task("ABC-3", "backlog") };
    return { task: task("ABC-3", "todo") };
  };
  const t = await createTask({ title: "Fix login", priority: "high", status: "todo" });
  assert.equal(t.status, "todo");
  const create = calls.find((c) => c[0] === "create")!;
  assert.deepEqual(create.slice(0, 5), ["create", "--project", "ABC", "--title", "Fix login"]);
  assert.deepEqual(calls.find((c) => c[0] === "update")!.slice(0, 4), ["update", "ABC-3", "--status", "todo"]);
});

test("create with several projects asks which one", async () => {
  answers = () => ({ projects: [{ prefix: "ABC" }, { prefix: "XYZ" }] });
  await assert.rejects(createTask({ title: "x" }), /ABC, XYZ/);
});

test("keys, statuses and priorities are validated before reaching the CLI", async () => {
  calls.length = 0;
  await assert.rejects(updateTask("not a key; rm -rf", { status: "done" }), /task key/);
  await assert.rejects(updateTask("ABC-1", { status: "finished" }), /Unknown status/);
  await assert.rejects(updateTask("ABC-1", {}), /Nothing to update/);
  assert.equal(calls.length, 0);
});

test("a long description becomes a one-line summary", () => {
  assert.ok(toCard(task("A-1", "todo", { description: "word ".repeat(1000) }), []).summary.length <= 1500);
});
