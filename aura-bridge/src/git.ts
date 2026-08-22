/**
 * Git status + diff (spec §23–26, §70). Shells out to the system `git` —
 * scoped to one workspace root, same root a WorkspaceFiles instance uses, so
 * "Diff" always reflects the same tree "Code" is browsing. When [machineId]
 * is set (spec §37), the exact same `git` invocations run over SSH against
 * that machine instead of locally — same commands, same parsing, just a
 * different place they execute.
 */
import { spawn } from "node:child_process";

/** Single-quotes an argument for a POSIX remote shell, escaping embedded quotes. */
function shQuote(arg: string): string {
  return `'${arg.replace(/'/g, `'\\''`)}'`;
}

export interface GitFileStatus {
  path: string;
  /** Renamed entries carry both; every other status only ever sets `path`. */
  oldPath?: string;
  status: "added" | "modified" | "deleted" | "renamed" | "untracked";
}

export interface GitStatusResult {
  branch: string | null;
  files: GitFileStatus[];
}

export class GitService {
  private root: string;
  /** Set for a connected remote machine (spec §37); undefined runs `git` locally. */
  private machineId?: string;
  /** Repo top-level, lazily resolved — `status()` reports paths relative to
   * this, not to `root`, whenever `root` is a subdirectory of the repo. */
  private repoRootPromise: Promise<string> | null = null;

  constructor(root: string, machineId?: string) {
    this.root = root;
    this.machineId = machineId;
  }

  private repoRoot(): Promise<string> {
    if (!this.repoRootPromise) {
      this.repoRootPromise = this.run(["rev-parse", "--show-toplevel"]).then((out) => out.trim());
    }
    return this.repoRootPromise;
  }

  /**
   * @param okExitCodes Exit codes treated as success (stdout still returned).
   * `git diff --no-index` exits 1 for "a diff was found" — the normal case,
   * not a failure — so callers that use it must include 1 here.
   * @param cwd Defaults to the workspace root; `diff()` overrides this to the
   * repo top-level so its paths agree with `status()` (see there).
   */
  private run(args: string[], okExitCodes: number[] = [0], cwd: string = this.root): Promise<string> {
    return new Promise((resolve, reject) => {
      const proc = this.machineId
        ? spawn("ssh", [
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=8",
            this.machineId,
            `git -C ${shQuote(cwd)} ${args.map(shQuote).join(" ")}`,
          ])
        : spawn("git", ["-C", cwd, ...args]);
      let out = "";
      let errOut = "";
      proc.stdout.on("data", (d) => (out += d));
      proc.stderr.on("data", (d) => (errOut += d));
      proc.on("error", reject);
      proc.on("close", (code) => {
        if (code !== null && okExitCodes.includes(code)) resolve(out);
        else reject(new Error(errOut.trim() || `git ${args.join(" ")} exited ${code}`));
      });
    });
  }

  async status(): Promise<GitStatusResult> {
    const [branchOut, statusOut] = await Promise.all([
      this.run(["branch", "--show-current"]).catch(() => ""),
      this.run(["status", "--porcelain=v1"]),
    ]);
    const files: GitFileStatus[] = [];
    for (const rawLine of statusOut.split("\n")) {
      if (!rawLine.trim()) continue;
      const code = rawLine.slice(0, 2);
      const rest = rawLine.slice(3);
      if (code.includes("R")) {
        const [oldPath, newPath] = rest.split(" -> ");
        files.push({ path: newPath ?? rest, oldPath, status: "renamed" });
      } else if (code === "??") {
        files.push({ path: rest, status: "untracked" });
      } else if (code.includes("D")) {
        files.push({ path: rest, status: "deleted" });
      } else if (code.includes("A")) {
        files.push({ path: rest, status: "added" });
      } else {
        files.push({ path: rest, status: "modified" });
      }
    }
    return { branch: branchOut.trim() || null, files };
  }

  /**
   * Unified diff text for one file. Untracked files have nothing to diff
   * against HEAD, so they're shown as an all-added diff against empty
   * instead (spec §25 — new files still need to be reviewable).
   *
   * `path` (like everything `status()` returns) is repo-root-relative, but
   * plain pathspecs to `git diff` resolve relative to `-C <root>` instead —
   * the two disagree whenever root is a subdirectory of the repo (e.g. this
   * bridge's own project root), silently returning an empty diff. Running
   * from the repo top-level instead makes `path` mean the same thing on
   * both sides, and (unlike passing an absolute path) keeps `--no-index`'s
   * diff header clean too, since it never resolves/shortens its arguments.
   */
  async diff(path: string): Promise<string> {
    const repoRoot = await this.repoRoot();
    const { files } = await this.status();
    const entry = files.find((f) => f.path === path);
    if (entry?.status === "untracked") {
      return this.run(["diff", "--no-index", "--", "/dev/null", path], [0, 1], repoRoot);
    }
    try {
      return await this.run(["diff", "HEAD", "--", path], [0], repoRoot);
    } catch {
      // No commits yet (brand-new repo) — HEAD doesn't resolve.
      return this.run(["diff", "--no-index", "--", "/dev/null", path], [0, 1], repoRoot).catch(() => "");
    }
  }

  /** Local branch names for the quick switcher (spec §26), current branch first. */
  async listBranches(): Promise<{ current: string | null; branches: string[] }> {
    const [currentOut, allOut] = await Promise.all([
      this.run(["branch", "--show-current"]).catch(() => ""),
      this.run(["for-each-ref", "refs/heads", "--format=%(refname:short)"]),
    ]);
    const current = currentOut.trim() || null;
    const branches = allOut
      .split("\n")
      .map((b) => b.trim())
      .filter(Boolean)
      .sort((a, b) => (a === current ? -1 : b === current ? 1 : a.localeCompare(b)));
    return { current, branches };
  }

  /** Plain checkout — caller must have already confirmed the tree is clean. */
  async checkout(target: string): Promise<void> {
    await this.run(["checkout", target]);
  }

  /**
   * Stashes local changes (including untracked) before switching (spec §27's
   * "stash" resolution). If the checkout itself then fails — most commonly
   * because the target branch is already checked out in one of this repo's
   * other worktrees, which `git checkout` refuses outright — the stash is
   * popped back immediately rather than left behind. A failed switch must
   * never look like "my uncommitted changes just vanished".
   */
  async stashAndCheckout(target: string): Promise<void> {
    await this.run(["stash", "push", "-u", "-m", `aura-xr: before switching to ${target}`]);
    try {
      await this.run(["checkout", target]);
    } catch (e) {
      await this.run(["stash", "pop"]).catch(() => {
        // Extremely unlikely (nothing should have touched the tree between
        // the two calls above), but surfacing the original checkout error is
        // more useful than a rollback failure either way.
      });
      throw e;
    }
  }
}

/** Extracts the worktree path from git's "already used by worktree" refusal, if that's what failed. */
export function worktreeConflictPath(errorMessage: string): string | null {
  const match = errorMessage.match(/is already used by worktree at '(.+)'/);
  return match?.[1] ?? null;
}
