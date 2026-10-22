/** How the MCP server reaches the running bridge: through its control endpoint (endpoint.ts). */
import { request } from "node:http";
import { readControlFile } from "./endpoint.ts";
import type { ControlCommand } from "./commands.ts";

export type BridgeCall = (command: ControlCommand, args: Record<string, unknown>) => Promise<{ ok: boolean; body: unknown }>;

/** Sends one control command to the running bridge on this machine. */
export const callBridge: BridgeCall = async (command, args) => {
  const info = await readControlFile();
  if (!info) {
    return {
      ok: false,
      body: { error: "The LensCode bridge isn't running on this computer. Start it with `npx lenscode-bridge --secure`." },
    };
  }
  const payload = JSON.stringify({ command, args });
  return new Promise((resolve) => {
    const req = request(
      {
        host: "127.0.0.1",
        port: info.port,
        path: "/control",
        method: "POST",
        headers: {
          authorization: `Bearer ${info.token}`,
          "content-type": "application/json",
          "content-length": Buffer.byteLength(payload),
        },
        timeout: 15000,
      },
      (res) => {
        let text = "";
        res.on("data", (d) => (text += d));
        res.on("end", () => {
          let body: unknown = text;
          try {
            body = JSON.parse(text);
          } catch {
            /* keep the raw text */
          }
          resolve({ ok: (res.statusCode ?? 500) < 300, body });
        });
      },
    );
    req.on("timeout", () => req.destroy(new Error("timed out")));
    req.on("error", (e) =>
      resolve({
        ok: false,
        body: {
          error:
            (e as NodeJS.ErrnoException).code === "ECONNREFUSED"
              ? "The LensCode bridge isn't running on this computer (it may have stopped). Start it with `npx lenscode-bridge --secure`."
              : `Couldn't reach the LensCode bridge: ${e.message}`,
        },
      }),
    );
    req.end(payload);
  });
};
