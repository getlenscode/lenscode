import { applyCliArgs } from "./cli.ts";
import { loadConfig } from "./config.ts";
import { BridgeServer, BRIDGE_VERSION } from "./server.ts";
import { lanAddresses } from "./geminiHttp.ts";
import { log } from "./log.ts";

const cli = applyCliArgs(process.argv.slice(2), process.env, BRIDGE_VERSION);
if (cli.action === "exit") {
  (cli.code === 0 ? process.stdout : process.stderr).write(cli.message);
  process.exit(cli.code);
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
