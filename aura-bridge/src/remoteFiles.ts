/**
 * Remote file browsing (spec §37 — Code/files should work the same way on a
 * remote machine, just scoped by machine id). WorkspaceFiles reads the local
 * filesystem directly; there's no such thing as a "local path" on a machine
 * this process isn't running on, so this mirrors its public shape (listDir /
 * openFile) but backed by plain `find`/`head`/`wc` over the same SSH
 * connection — POSIX-only commands (no GNU-only flags), so this works
 * against a Linux or macOS remote alike.
 */
import { spawn } from "node:child_process";
import { extname } from "node:path";
import { fuzzyScore, normalizeSpokenQuery, type SearchHit, type VoiceResolution } from "./files.ts";

const IGNORED_DIRS = new Set([
  ".git", "node_modules", "dist", "build", "target", ".venv", "__pycache__",
  "Library", "Temp", "obj",
]);

const LANGUAGES: Record<string, string> = {
  ".ts": "typescript", ".tsx": "typescript", ".js": "javascript", ".jsx": "javascript",
  ".py": "python", ".rs": "rust", ".go": "go", ".c": "c", ".h": "c",
  ".cpp": "cpp", ".cc": "cpp", ".hpp": "cpp", ".cs": "csharp",
  ".json": "json", ".yaml": "yaml", ".yml": "yaml", ".md": "markdown",
  ".sh": "shell", ".zsh": "shell", ".bash": "shell", ".toml": "toml",
};

const MAX_FILE_BYTES = 2 * 1024 * 1024;

export interface RemoteDirEntry {
  name: string;
  kind: "file" | "dir";
  size: number | null;
  language: string | null;
}

export interface RemoteFileContent {
  path: string;
  content: string;
  language: string | null;
  truncated: boolean;
  size: number;
}

/** Single-quotes a path for POSIX shells, escaping embedded quotes. */
function shQuote(path: string): string {
  return `'${path.replace(/'/g, `'\\''`)}'`;
}

export class RemoteFiles {
  private machineId: string;
  /** Absolute POSIX path this instance is rooted at — chosen by the client
   * (a folder they picked), not resolved through any Herdr workspace. */
  private root: string;

  constructor(machineId: string, root: string) {
    this.machineId = machineId;
    this.root = root;
  }

  /** @param okExitCodes `grep`/`find` with no matches exit 1 — not a failure, just "nothing found". */
  private exec(command: string, okExitCodes: number[] = [0]): Promise<string> {
    return new Promise((resolve, reject) => {
      const proc = spawn("ssh", ["-o", "BatchMode=yes", "-o", "ConnectTimeout=8", this.machineId, command]);
      let out = "";
      let errOut = "";
      proc.stdout.on("data", (d) => (out += d));
      proc.stderr.on("data", (d) => (errOut += d));
      proc.on("error", reject);
      proc.on("close", (code) => {
        if (code !== null && okExitCodes.includes(code)) resolve(out);
        else reject(new Error(errOut.trim() || `remote command exited ${code}`));
      });
    });
  }

  /** Joins onto the root and refuses to escape it — same contract as WorkspaceFiles.safePath. */
  private resolvePath(relPath: string): string {
    const cleaned = relPath.split("/").filter((seg) => seg.length > 0 && seg !== ".");
    const out: string[] = [];
    for (const seg of cleaned) {
      if (seg === "..") {
        if (out.length === 0) throw new Error("Path is outside the workspace.");
        out.pop();
      } else {
        out.push(seg);
      }
    }
    return out.length === 0 ? this.root : `${this.root}/${out.join("/")}`;
  }

  async listDir(path: string): Promise<RemoteDirEntry[]> {
    const abs = this.resolvePath(path);
    const [dirsOut, filesOut] = await Promise.all([
      this.exec(`find ${shQuote(abs)} -mindepth 1 -maxdepth 1 -type d -exec basename {} \\;`),
      this.exec(`find ${shQuote(abs)} -mindepth 1 -maxdepth 1 -type f -exec basename {} \\;`),
    ]);
    const out: RemoteDirEntry[] = [];
    for (const name of dirsOut.split("\n").map((s) => s.trim()).filter(Boolean)) {
      if (name.startsWith(".") && name !== ".github") continue;
      if (IGNORED_DIRS.has(name)) continue;
      out.push({ name, kind: "dir", size: null, language: null });
    }
    for (const name of filesOut.split("\n").map((s) => s.trim()).filter(Boolean)) {
      if (name.startsWith(".") && name !== ".github") continue;
      out.push({ name, kind: "file", size: null, language: LANGUAGES[extname(name)] ?? null });
    }
    return out.sort((a, b) => (a.kind !== b.kind ? (a.kind === "dir" ? -1 : 1) : a.name.localeCompare(b.name)));
  }

  async openFile(path: string): Promise<RemoteFileContent> {
    const abs = this.resolvePath(path);
    const sizeOut = await this.exec(`wc -c < ${shQuote(abs)}`);
    const size = parseInt(sizeOut.trim(), 10) || 0;
    const content = await this.exec(`head -c ${MAX_FILE_BYTES} ${shQuote(abs)}`);
    this.recentlyOpened.set(path, Date.now());
    return {
      path,
      content,
      language: LANGUAGES[extname(abs)] ?? null,
      truncated: size > MAX_FILE_BYTES,
      size,
    };
  }

  /** path → last-opened timestamp, for recency ranking (spec §13, §14) — same scoring as WorkspaceFiles. */
  private recentlyOpened = new Map<string, number>();
  private fileListCache: { files: string[]; builtAt: number } | null = null;

  /** Fuzzy filename search + `grep` text fallback, merged and ranked exactly like WorkspaceFiles. */
  async search(query: string, limit: number): Promise<SearchHit[]> {
    const [nameHits, textHits] = await Promise.all([
      this.searchFilenames(query, limit),
      this.searchText(query, limit),
    ]);
    const withRecency = [...nameHits, ...textHits].map((hit) => ({
      ...hit,
      score: hit.score + this.recencyBonus(hit.path),
    }));
    const seen = new Set<string>();
    const merged: SearchHit[] = [];
    for (const hit of withRecency.sort((a, b) => b.score - a.score)) {
      const key = `${hit.kind}:${hit.path}`;
      if (seen.has(key)) continue;
      seen.add(key);
      merged.push(hit);
      if (merged.length >= limit) break;
    }
    return merged;
  }

  /** Voice search entry point (spec §13, §63) — identical confidence rule to WorkspaceFiles. */
  async resolveVoiceQuery(rawQuery: string, limit = 5): Promise<VoiceResolution> {
    const query = normalizeSpokenQuery(rawQuery);
    const hits = await this.search(query, Math.max(limit, 5));
    if (hits.length === 0) return { confident: false, candidates: [] };
    const [top, second] = hits;
    const isExact = top.kind === "filename" && top.score >= 1000;
    const gapOk = !second || top.score - second.score >= 150;
    const confident = isExact || (top.score >= 400 && gapOk);
    return confident
      ? { confident: true, resolvedPath: top.path, candidates: hits.slice(0, limit) }
      : { confident: false, candidates: hits.slice(0, limit) };
  }

  private recencyBonus(path: string): number {
    const openedAt = this.recentlyOpened.get(path);
    if (!openedAt) return 0;
    const age = Date.now() - openedAt;
    const windowMs = 10 * 60 * 1000;
    if (age >= windowMs) return 0;
    return 80 * (1 - age / windowMs);
  }

  /** All file paths under root, relative to it — same pruning as WorkspaceFiles.fileList, via `find` instead of readdir. */
  private async fileList(): Promise<string[]> {
    if (this.fileListCache && Date.now() - this.fileListCache.builtAt < 10_000) {
      return this.fileListCache.files;
    }
    const pruneExpr = [...IGNORED_DIRS].map((d) => `-name ${shQuote(d)}`).join(" -o ");
    const out = await this.exec(
      `find ${shQuote(this.root)} \\( -type d \\( ${pruneExpr} \\) -prune \\) -o -type f -not -name '.*' -print`,
    );
    const prefix = `${this.root}/`;
    this.fileListCache = {
      files: out
        .split("\n")
        .map((l) => l.trim())
        .filter(Boolean)
        .map((abs) => (abs.startsWith(prefix) ? abs.slice(prefix.length) : abs)),
      builtAt: Date.now(),
    };
    return this.fileListCache.files;
  }

  private async searchFilenames(query: string, limit: number): Promise<SearchHit[]> {
    const files = await this.fileList();
    const q = query.toLowerCase().replace(/\s+/g, "");
    const hits: SearchHit[] = [];
    for (const path of files) {
      const base = path.split("/").pop() ?? path;
      const score = fuzzyScore(q, path.toLowerCase(), base.toLowerCase());
      if (score > 0) hits.push({ path, score, kind: "filename" });
    }
    return hits.sort((a, b) => b.score - a.score).slice(0, limit);
  }

  /** No `rg` dependency — plain POSIX `grep`, since an arbitrary remote box may not have ripgrep installed. */
  private async searchText(query: string, limit: number): Promise<SearchHit[]> {
    try {
      const out = await this.exec(
        `cd ${shQuote(this.root)} && grep -rIn -i -F -- ${shQuote(query)} ` +
          `--exclude-dir=${shQuote(".git")} --exclude-dir=${shQuote("node_modules")} . 2>/dev/null | head -n ${limit}`,
        [0, 1],
      );
      const hits: SearchHit[] = [];
      for (const line of out.split("\n")) {
        if (!line.trim() || hits.length >= limit) continue;
        const match = line.match(/^\.\/(.+?):(\d+):(.*)$/);
        if (!match) continue;
        const [, path, lineNo, text] = match;
        hits.push({
          path,
          score: 10,
          kind: "text",
          preview: text.trim().slice(0, 200),
          line: parseInt(lineNo, 10),
        });
      }
      return hits;
    } catch {
      return []; // grep missing or errored → filename search only
    }
  }
}
