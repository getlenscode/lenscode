import { loadConfig } from "./config.ts";
import { BridgeServer } from "./server.ts";
import { log } from "./log.ts";

const cfg = loadConfig();
const server = new BridgeServer(cfg);
await server.start();

process.on("SIGINT", () => {
  log("info", "shutting down");
  server.close();
  process.exit(0);
});
