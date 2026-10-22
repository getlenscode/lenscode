/** Everything about driving the headset from Claude Code / Claude Desktop. See README.md in this folder. */
export * from "./commands.ts";
export * from "./endpoint.ts";
export { TOOLS } from "./tools.ts";
export { callBridge, type BridgeCall } from "./client.ts";
export { handleMcpMessage, runMcpServer } from "./server.ts";
export * from "./remoteDaemon.ts";
