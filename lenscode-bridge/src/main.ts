import { applyCliArgs } from "./cli.ts";
import { loadConfig } from "./config.ts";
import { BridgeServer, BRIDGE_VERSION } from "./server.ts";
import { lanAddresses } from "./geminiHttp.ts";
import { log } from "./log.ts";
import { ensureSelfSignedCert, shortFingerprint, type SelfSignedCert } from "./selfSigned.ts";
import { runMcpServer, writeControlFile } from "./mcp/index.ts";

const cli = applyCliArgs(process.argv.slice(2), process.env, BRIDGE_VERSION);
if (cli.action === "exit") {
  (cli.code === 0 ? process.stdout : process.stderr).write(cli.message);
  process.exit(cli.code);
}
// The MCP server is a separate, short-lived process the MCP client starts; it relays to the
// bridge that is already running, so it must not start a second one.
if (cli.action === "mcp") {
  await runMcpServer(BRIDGE_VERSION);
  process.exit(0);
}

// --secure: make (or reuse) the bridge's own certificate and serve it, in place of a user-supplied one.
let selfSigned: SelfSignedCert | undefined;
if (process.env.LENSCODE_SECURE === "1") {
  selfSigned = await ensureSelfSignedCert();
  process.env.LENSCODE_TLS_CERT = selfSigned.certPath;
  process.env.LENSCODE_TLS_KEY = selfSigned.keyPath;
}

let cfg;
try {
  cfg = loadConfig();
} catch (e) {
  process.stderr.write(`${e instanceof Error ? e.message : String(e)}\n`);
  process.exit(2);
}
const server = new BridgeServer(cfg);
await server.start();
// Where `lenscode-bridge mcp` finds this bridge's loopback control endpoint.
if (server.controlEndpoint) {
  await writeControlFile({ ...server.controlEndpoint, pid: process.pid }).catch((e) =>
    log("warn", "could not write the control file; the MCP server won't find this bridge", { error: String(e) }),
  );
}

// The JSON log lines above are for machines; this is what a person needs to set up the app.
const secure = cfg.mode === "prod" && !cfg.insecureProd;
const addresses = lanAddresses().map((ip) => `${secure ? "wss" : "ws"}://${ip}:${cfg.port}`);
process.stdout.write(
  [
    "",
    `lenscode-bridge ${BRIDGE_VERSION} — ${secure ? "secure (TLS) mode: the headset pairs with a 6-digit code shown here" : "dev mode: plain ws://, open to your local network"}`,
    addresses.length > 0
      ? `In the app: Settings → Bridge connection → ${addresses.join("  or  ")}`
      : "No network address found — connect this machine to your network.",
    ...(selfSigned
      ? [
          `Certificate fingerprint: ${shortFingerprint(selfSigned.fingerprint)}`,
          "  The headset shows this when it first connects. Confirm they match, then pair with the code below.",
        ]
      : []),
    server.backendConnected
      ? "Paseo daemon: connected."
      : `Paseo daemon: NOT reachable at ${cfg.paseoUrl}. Install and start Paseo on this machine (the bridge keeps retrying).`,
    "",
  ].join("\n"),
);

// SIGTERM is what launchd/systemd/docker send; without it they would have to kill us.
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    log("info", "shutting down");
    server.close();
    process.exit(0);
  });
}
