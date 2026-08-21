/**
 * File services (spec §12–14): lazy directory listing, file open with metadata,
 * and workspace-scoped search (fuzzy filename + ripgrep text fallback).
 * Nothing here ever ships a whole repository to the client.
 */
import { readdir, readFile, stat } from "node:fs/promises";
import { spawn } from "node:child_process";
import { join, relative, resolve, sep, extname, basename } from "node:path";

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

export interface DirEntry {
  name: string;
  kind: "file" | "dir";
  size: number | null;
  language: string | null;
}

export interface SearchHit {
  path: string;
  score: number;
  kind: "filename" | "text";
  /** For text hits: the first matching line preview. */
  preview?: string;
  line?: number;
}

export class WorkspaceFiles {
  private root: string;
  /** Cached flat file list for filename search; rebuilt lazily. */
  private fileListCache: { files: string[]; builtAt: number } | null = null;

  constructor(root: string) {
    this.root = resolve(root);
  }

  /** Resolves a client path against the root, refusing escapes (spec §54). */
  private safePath(p: string): string {
    const abs = resolve(this.root, "." + sep + p);
    if (abs !== this.root && !abs.startsWith(this.root + sep)) {
      throw new Error("Path is outside the workspace.");
    }
    return abs;
  }

  async listDir(path: string): Promise<DirEntry[]> {
    const abs = this.safePath(path);
    const entries = await readdir(abs, { withFileTypes: true });
    const out: DirEntry[] = [];
    for (const e of entries) {
      if (e.name.startsWith(".") && e.name !== ".github") continue;
      const kind = e.isDirectory() ? "dir" : "file";
      if (kind === "dir" && IGNORED_DIRS.has(e.name)) continue;
      let size: number | null = null;
      if (kind === "file") {
        try {
          size = (await stat(join(abs, e.name))).size;
        } catch { /* race: entry vanished */ }
      }
      out.push({
        name: e.name,
        kind,
        size,
        language: kind === "file" ? LANGUAGES[extname(e.name)] ?? null : null,
      });
    }
    return out.sort((a, b) =>
      a.kind !== b.kind ? (a.kind === "dir" ? -1 : 1) : a.name.localeCompare(b.name),
    );
  }

  async openFile(path: string): Promise<{
    path: string;
    language: string | null;
    size: number;
    truncated: boolean;
    content: string;
  }> {
    const abs = this.safePath(path);
    const info = await stat(abs);
    if (!info.isFile()) throw new Error("Not a file.");
    const truncated = info.size > MAX_FILE_BYTES;
    const buf = await readFile(abs);
    return {
      path,
      language: LANGUAGES[extname(abs)] ?? null,
      size: info.size,
      truncated,
      content: buf.subarray(0, MAX_FILE_BYTES).toString("utf8"),
    };
  }

  /** Fuzzy filename search + ripgrep text fallback, merged and ranked. */
  async search(query: string, limit: number): Promise<SearchHit[]> {
    const [nameHits, textHits] = await Promise.all([
      this.searchFilenames(query, limit),
      this.searchText(query, limit),
    ]);
    const seen = new Set<string>();
    const merged: SearchHit[] = [];
    for (const hit of [...nameHits, ...textHits].sort((a, b) => b.score - a.score)) {
      const key = `${hit.kind}:${hit.path}`;
      if (seen.has(key)) continue;
      seen.add(key);
      merged.push(hit);
      if (merged.length >= limit) break;
    }
    return merged;
  }

  private async fileList(): Promise<string[]> {
    // 10 s cache keeps repeated voice queries under the latency target without
    // serving long-stale trees.
    if (this.fileListCache && Date.now() - this.fileListCache.builtAt < 10_000) {
      return this.fileListCache.files;
    }
    const files: string[] = [];
    const walk = async (dir: string): Promise<void> => {
      let entries;
      try {
        entries = await readdir(dir, { withFileTypes: true });
      } catch {
        return;
      }
      await Promise.all(entries.map(async (e) => {
        if (e.name.startsWith(".")) return;
        const abs = join(dir, e.name);
        if (e.isDirectory()) {
          if (!IGNORED_DIRS.has(e.name)) await walk(abs);
        } else {
          files.push(relative(this.root, abs));
        }
      }));
    };
    await walk(this.root);
    this.fileListCache = { files, builtAt: Date.now() };
    return files;
  }

  private async searchFilenames(query: string, limit: number): Promise<SearchHit[]> {
    const files = await this.fileList();
    const q = query.toLowerCase().replace(/\s+/g, "");
    const hits: SearchHit[] = [];
    for (const path of files) {
      const score = fuzzyScore(q, path.toLowerCase(), basename(path).toLowerCase());
      if (score > 0) hits.push({ path, score, kind: "filename" });
    }
    return hits.sort((a, b) => b.score - a.score).slice(0, limit);
  }

  private searchText(query: string, limit: number): Promise<SearchHit[]> {
    return new Promise((resolvePromise) => {
      const rg = spawn("rg", [
        "--json", "--max-count", "1", "--ignore-case",
        "--max-filesize", "1M", "-m", String(limit),
        "--fixed-strings", query, ".",
      ], { cwd: this.root });
      const hits: SearchHit[] = [];
      let buf = "";
      rg.stdout.on("data", (d: Buffer) => {
        buf += d.toString();
        let idx;
        while ((idx = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, idx);
          buf = buf.slice(idx + 1);
          try {
            const msg = JSON.parse(line);
            if (msg.type === "match" && hits.length < limit) {
              hits.push({
                path: msg.data.path.text,
                // Text matches rank below strong filename matches by design.
                score: 10,
                kind: "text",
                preview: String(msg.data.lines.text ?? "").trim().slice(0, 200),
                line: msg.data.line_number,
              });
            }
          } catch { /* ignore non-JSON rg output */ }
        }
      });
      rg.on("close", () => resolvePromise(hits));
      rg.on("error", () => resolvePromise([])); // rg missing → filename search only
    });
  }
}

/**
 * Simple subsequence fuzzy score: exact basename match ≫ basename prefix ≫
 * basename subsequence ≫ path subsequence. Good enough for M5; the semantic
 * index replaces it in M6.
 */
function fuzzyScore(q: string, path: string, base: string): number {
  if (!q) return 0;
  const baseNoExt = base.includes(".") ? base.slice(0, base.lastIndexOf(".")) : base;
  if (base === q || baseNoExt === q) return 1000;
  if (base.startsWith(q)) return 500 + q.length / base.length;
  if (isSubsequence(q, base)) return 100 + q.length / base.length;
  if (path.includes(q)) return 50;
  if (isSubsequence(q, path)) return 20;
  return 0;
}

function isSubsequence(needle: string, haystack: string): boolean {
  let i = 0;
  for (const ch of haystack) {
    if (ch === needle[i]) i++;
    if (i === needle.length) return true;
  }
  return false;
}
