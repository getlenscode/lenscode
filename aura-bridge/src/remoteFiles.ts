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

  private exec(command: string): Promise<string> {
    return new Promise((resolve, reject) => {
      const proc = spawn("ssh", ["-o", "BatchMode=yes", "-o", "ConnectTimeout=8", this.machineId, command]);
      let out = "";
      let errOut = "";
      proc.stdout.on("data", (d) => (out += d));
      proc.stderr.on("data", (d) => (errOut += d));
      proc.on("error", reject);
      proc.on("close", (code) => {
        if (code === 0) resolve(out);
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
    return {
      path,
      content,
      language: LANGUAGES[extname(abs)] ?? null,
      truncated: size > MAX_FILE_BYTES,
      size,
    };
  }
}
