/**
 * The bridge's local control endpoint: lets tools on this computer (the `lenscode-bridge mcp` server
 * that Claude Code / Claude Desktop talk to) drive the paired headset.
 *
 * It is a plain HTTP listener bound to 127.0.0.1 only (never the LAN), on a random port, and every
 * request must carry the token the running bridge writes to ~/.config/lenscode-bridge/control.json
 * (mode 0600). So only this user's local processes can use it.
 */
import { createServer, type IncomingMessage, type Server } from "node:http";
import { randomBytes, timingSafeEqual } from "node:crypto";
import { mkdir, readFile, writeFile, chmod } from "node:fs/promises";
import { dirname, join } from "node:path";
import { homedir } from "node:os";

export const CONTROL_FILE = join(homedir(), ".config", "lenscode-bridge", "control.json");

/** What the bridge writes so local tools can find it. */
export interface ControlInfo {
  port: number;
  token: string;
  pid: number;
}

import { validateControl, type ControlCommand } from "./commands.ts";

/** Constant-time check of the `Authorization: Bearer <token>` header. */
export function tokenMatches(header: string | undefined, token: string): boolean {
  const given = header?.startsWith("Bearer ") ? header.slice(7) : "";
  const a = Buffer.from(given);
  const b = Buffer.from(token);
  return a.length === b.length && timingSafeEqual(a, b);
}

export type ControlHandler = (command: ControlCommand, args: Record<string, string>) => Promise<{ status: number; body: unknown }>;

const MAX_BODY = 64 * 1024;

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new Error("too large"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

/** Starts the loopback listener on a random port. */
export function startControlServer(handler: ControlHandler, token = randomBytes(32).toString("hex")): Promise<{
  server: Server;
  port: number;
  token: string;
}> {
  const server = createServer(async (req, res) => {
    const reply = (status: number, body: unknown) =>
      res.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify(body));
    if (req.method !== "POST" || req.url !== "/control") return reply(404, { error: "Not found." });
    if (!tokenMatches(req.headers.authorization, token)) return reply(401, { error: "Missing or wrong control token." });
    let parsed: unknown;
    try {
      parsed = JSON.parse(await readBody(req));
    } catch {
      return reply(400, { error: "Expected a JSON body." });
    }
    const valid = validateControl(parsed);
    if (!valid.ok) return reply(400, { error: valid.error });
    try {
      const { status, body } = await handler(valid.command, valid.args);
      reply(status, body);
    } catch (e) {
      reply(500, { error: String(e) });
    }
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      resolve({ server, port, token });
    });
  });
}

/** Saves where the running bridge's control endpoint is (readable by this user only). */
export async function writeControlFile(info: ControlInfo, path = CONTROL_FILE): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, JSON.stringify(info, null, 2), { mode: 0o600 });
  await chmod(path, 0o600);
}

export async function readControlFile(path = CONTROL_FILE): Promise<ControlInfo | null> {
  try {
    const parsed = JSON.parse(await readFile(path, "utf8")) as Partial<ControlInfo>;
    if (typeof parsed.port !== "number" || typeof parsed.token !== "string") return null;
    return { port: parsed.port, token: parsed.token, pid: typeof parsed.pid === "number" ? parsed.pid : 0 };
  } catch {
    return null;
  }
}
