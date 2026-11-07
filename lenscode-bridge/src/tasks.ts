/**
 * Kanban data from the Paseo "Tasks" plugin (https://paseo.sh/plugins/anh-chu/tasks).
 *
 * The plugin's daemon is the only writer of its SQLite store, so the bridge never touches the
 * database. It talks to the same loopback endpoint the plugin's own `paseo-tasks` CLI uses
 * (`POST <bridge.json url>/cli` with a bearer token) and asks for `--json` output. On a remote
 * machine it runs that machine's `paseo-tasks` script over SSH instead.
 *
 * Agents already get the tracker's instructions injected by the plugin, so they create and update
 * tasks themselves; this module only reads the board and handles tasks made or moved from the headset.
 * Starting an agent on a task is not here: the plugin's own `delegate` command fails when called through
 * its CLI (its issue #2), so the app starts the agent through the normal agent.create path instead.
 */
import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { runSsh } from "./remote.ts";

export const TASK_STATUSES = ["backlog", "todo", "in_progress", "in_review", "done", "canceled"] as const;
export const TASK_PRIORITIES = ["urgent", "high", "medium", "low", "none"] as const;
export type TaskStatus = (typeof TASK_STATUSES)[number];
export type TaskPriority = (typeof TASK_PRIORITIES)[number];

/** Same pattern as the plugin: "ABC-12". */
const TASK_KEY = /^[A-Za-z][A-Za-z0-9]{0,9}-\d+$/;
const PROJECT_PREFIX = /^[A-Z][A-Z0-9]{0,9}$/;

const MAX_TASKS = 500;
const SUMMARY_CHARS = 1500;
/** Done and canceled tasks keep their agents' history, but the board doesn't need it per refresh. */
const THREAD_STATUSES = new Set<string>(["backlog", "todo", "in_progress", "in_review"]);
const THREAD_CONCURRENCY = 6;

export interface TaskThread {
  threadId: string;
  presetName: string;
  liveStatus: string;
}

export interface TaskCard {
  key: string;
  title: string;
  status: string;
  priority: string;
  dueDate: string | null;
  labels: string[];
  summary: string;
  agentsWorking: number;
  /** Agent sessions attached to the task (empty for done/canceled tasks). */
  threads: TaskThread[];
}

export interface TaskProject {
  prefix: string;
  name: string;
}

export interface TaskPreset {
  name: string;
  providerId: string;
  modelId: string;
  /** "project-default" or "new-worktree". */
  environmentKind: string;
  instructions: string;
}

export interface TasksBoard {
  available: boolean;
  /** Why the board is unavailable, in words a person can act on. */
  reason?: string;
  statuses: readonly string[];
  projects: TaskProject[];
  presets: TaskPreset[];
  tasks: TaskCard[];
}

export class TasksUnavailable extends Error {}

const INSTALL_HINT = "The Paseo Tasks plugin isn't running on that machine. Install it with `paseo plugin add anh-chu/tasks` (needs Paseo 0.11.2+ and Node 24+ on the daemon host).";

function tasksDataDir(): string {
  const home = process.env.PASEO_HOME?.trim() || join(homedir(), ".paseo");
  return process.env.PASEO_TASKS_DATA_DIR?.trim() || join(home, "plugin-data", "tasks");
}

/** Single-quotes a value as one literal POSIX shell argument (for the SSH path only). */
function quote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

/** Runs `paseo-tasks <argv> --json` on [machineId] and returns the parsed output. */
export async function runTasksJson(argv: string[], machineId = "local"): Promise<any> {
  const args = [...argv, "--json"];
  let stdout: string;
  if (machineId === "local") {
    let bridge: { url: string; token: string };
    try {
      bridge = JSON.parse(await readFile(join(tasksDataDir(), "bridge.json"), "utf8"));
    } catch {
      throw new TasksUnavailable(INSTALL_HINT);
    }
    let res: Response;
    try {
      res = await fetch(`${bridge.url}/cli`, {
        method: "POST",
        headers: { "content-type": "application/json", authorization: `Bearer ${bridge.token}` },
        body: JSON.stringify({ argv: args, cwd: process.cwd() }),
        signal: AbortSignal.timeout(15_000),
      });
    } catch {
      throw new TasksUnavailable("The Paseo Tasks plugin didn't answer. Is the Paseo daemon running?");
    }
    if (!res.ok) throw new Error(`Tasks plugin returned HTTP ${res.status}.`);
    const out = (await res.json()) as { stdout?: string; stderr?: string; exitCode?: number };
    if (out.exitCode && out.exitCode !== 0) throw new Error((out.stderr || out.stdout || "paseo-tasks failed").trim());
    stdout = out.stdout ?? "";
  } else {
    const script = '"$HOME/.paseo/plugin-data/tasks/bin/paseo-tasks"';
    try {
      stdout = await runSsh(machineId, `${script} ${args.map(quote).join(" ")}`, 15_000);
    } catch (e) {
      const msg = String(e);
      if (/not found|No such file/i.test(msg) || /bridge is not active/i.test(msg)) throw new TasksUnavailable(INSTALL_HINT);
      throw e;
    }
  }
  try {
    return JSON.parse(stdout);
  } catch {
    throw new Error(`Unexpected output from paseo-tasks: ${stdout.slice(0, 120)}`);
  }
}

function summarize(description: unknown): string {
  const flat = String(description ?? "").replace(/\s+/g, " ").trim();
  return flat.length > SUMMARY_CHARS ? flat.slice(0, SUMMARY_CHARS - 1) + "…" : flat;
}

/** Runs [fn] over [items] with at most [limit] in flight. */
async function mapLimit<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i]!);
      }
    }),
  );
  return out;
}

export function toCard(raw: any, threads: TaskThread[]): TaskCard {
  return {
    key: String(raw.key),
    title: String(raw.title ?? ""),
    status: String(raw.status ?? "backlog"),
    priority: String(raw.priority ?? "none"),
    dueDate: raw.dueDate ?? null,
    labels: Array.isArray(raw.labels) ? raw.labels.map(String) : [],
    summary: summarize(raw.description),
    agentsWorking: Number(raw.agentsWorking ?? 0),
    threads,
  };
}

export async function readBoard(machineId = "local"): Promise<TasksBoard> {
  const empty = { statuses: TASK_STATUSES, projects: [], presets: [], tasks: [] };
  try {
    const [projects, list, presets] = await Promise.all([
      runTasksJson(["project", "list"], machineId),
      runTasksJson(["list", "--limit", String(MAX_TASKS)], machineId),
      runTasksJson(["preset", "list"], machineId).catch(() => ({ presets: [] })),
    ]);
    const raws: any[] = list.tasks ?? [];
    const threads = await mapLimit(raws, THREAD_CONCURRENCY, async (t): Promise<TaskThread[]> => {
      if (!THREAD_STATUSES.has(t.status)) return [];
      try {
        const r = await runTasksJson(["threads", t.key], machineId);
        return (r.taskThreads ?? []).map((x: any) => ({
          threadId: String(x.threadId),
          presetName: String(x.presetName ?? ""),
          liveStatus: String(x.liveStatus ?? ""),
        }));
      } catch {
        return [];
      }
    });
    return {
      available: true,
      statuses: TASK_STATUSES,
      projects: (projects.projects ?? []).map((p: any) => ({ prefix: String(p.prefix), name: String(p.name) })),
      presets: (presets.presets ?? []).map((p: any) => ({
        name: String(p.name),
        providerId: String(p.providerId ?? ""),
        modelId: String(p.modelId ?? ""),
        environmentKind: String(p.environmentKind ?? "project-default"),
        instructions: String(p.instructions ?? ""),
      })),
      tasks: raws.map((t, i) => toCard(t, threads[i]!)),
    };
  } catch (e) {
    if (e instanceof TasksUnavailable) return { ...empty, available: false, reason: e.message };
    throw e;
  }
}

function checkKey(key: string): string {
  if (!TASK_KEY.test(key)) throw new Error(`"${key}" isn't a task key like ABC-12.`);
  return key;
}

function checkEnum<T extends string>(value: string, allowed: readonly T[], what: string): T {
  if (!(allowed as readonly string[]).includes(value)) throw new Error(`Unknown ${what}: ${value}`);
  return value as T;
}

export async function createTask(
  input: { title: string; project?: string; priority?: string; status?: string; description?: string },
  machineId = "local",
): Promise<TaskCard> {
  let project = input.project?.toUpperCase();
  if (!project) {
    const { projects } = await runTasksJson(["project", "list"], machineId);
    if (!projects || projects.length === 0) throw new Error("Create a project in Paseo Tasks first.");
    if (projects.length > 1) {
      throw new Error(`Which project? One of: ${projects.map((p: any) => p.prefix).join(", ")}.`);
    }
    project = String(projects[0].prefix);
  }
  if (!PROJECT_PREFIX.test(project)) throw new Error(`"${project}" isn't a project prefix like ABC.`);
  const argv = ["create", "--project", project, "--title", input.title];
  if (input.priority) argv.push("--priority", checkEnum(input.priority, TASK_PRIORITIES, "priority"));
  if (input.description) argv.push("--description", input.description);
  const created = await runTasksJson(argv, machineId);
  let task = created.task;
  if (input.status && input.status !== task.status) {
    task = (await runTasksJson(["update", task.key, "--status", checkEnum(input.status, TASK_STATUSES, "status")], machineId)).task;
  }
  return toCard(task, []);
}

export async function updateTask(
  key: string,
  patch: { status?: string; priority?: string },
  machineId = "local",
): Promise<TaskCard> {
  const argv = ["update", checkKey(key)];
  if (patch.status) argv.push("--status", checkEnum(patch.status, TASK_STATUSES, "status"));
  if (patch.priority) argv.push("--priority", checkEnum(patch.priority, TASK_PRIORITIES, "priority"));
  if (argv.length === 2) throw new Error("Nothing to update.");
  return toCard((await runTasksJson(argv, machineId)).task, []);
}
