/** The MCP tools Claude is offered, and how each maps onto a control command. */
import { VIEWS, type ControlCommand } from "./commands.ts";

const machineProp = {
  type: "string",
  description:
    "SSH host alias from ~/.ssh/config (see lenscode_list_machines). Omit to use the machine the " +
    "headset's Code view is on.",
};

interface Tool {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  command: ControlCommand;
  /** Maps the tool's arguments onto the control command's. */
  args: (input: Record<string, unknown>) => Record<string, unknown>;
}

export const TOOLS: Tool[] = [
  {
    name: "lenscode_status",
    description: "Whether the LensCode bridge is running and how many headsets (XR glasses) are connected to it.",
    inputSchema: { type: "object", properties: {} },
    command: "status",
    args: () => ({}),
  },
  {
    name: "lenscode_open_url",
    description:
      "Open a web address in the LensCode app's own browser on the user's XR glasses (not on this " +
      "computer). Use for a dev server preview, docs, a PR. http and https only; a bare " +
      "\"192.168.1.5:3000\" works. \"localhost\" means the glasses, so give this computer's LAN address.",
    inputSchema: {
      type: "object",
      properties: { url: { type: "string", description: "The address to open." } },
      required: ["url"],
    },
    command: "open_url",
    args: (i) => ({ url: i.url }),
  },
  {
    name: "lenscode_list_tmux_sessions",
    description: "List the tmux sessions open on a machine, to pick one for lenscode_open_tmux_session.",
    inputSchema: {
      type: "object",
      properties: {
        machine: { type: "string", description: "SSH host alias, or \"local\" for this computer (the default)." },
      },
    },
    command: "list_tmux",
    args: (i) => ({ machineId: i.machine }),
  },
  {
    name: "lenscode_open_tmux_session",
    description:
      "Show a tmux session in the Terminal view on the user's XR glasses (attaches to it, creating it if " +
      "it doesn't exist). The session name may use letters, digits, '.', '-' and '_'.",
    inputSchema: {
      type: "object",
      properties: { session: { type: "string", description: "tmux session name." }, machine: machineProp },
      required: ["session"],
    },
    command: "open_tmux",
    args: (i) => ({ session: i.session, machineId: i.machine }),
  },
  {
    name: "lenscode_open_project",
    description:
      "Open a project folder on a remote machine in the Code view on the user's XR glasses. Connects to " +
      "the machine over passwordless SSH first (so it shows as connected in the app's Remote view), " +
      "then opens the folder. If Paseo isn't running on that machine it starts it. The machine is an SSH host alias from ~/.ssh/config; the path may start " +
      "with ~, e.g. machine \"doraemon_arch\", path \"~/Documents/app\".",
    inputSchema: {
      type: "object",
      properties: {
        machine: { type: "string", description: "SSH host alias from ~/.ssh/config." },
        path: { type: "string", description: "Folder on that machine; ~ is its home directory." },
      },
      required: ["machine", "path"],
    },
    command: "open_project",
    args: (i) => ({ machineId: i.machine, path: i.path }),
  },
  {
    name: "lenscode_open_file",
    description: "Open a file in the Code view on the user's XR glasses.",
    inputSchema: {
      type: "object",
      properties: {
        path: { type: "string", description: "Absolute path, or a path relative to the folder Code has open." },
        machine: machineProp,
      },
      required: ["path"],
    },
    command: "open_file",
    args: (i) => ({ path: i.path, machineId: i.machine }),
  },
  {
    name: "lenscode_show_view",
    description: "Switch the LensCode app on the user's XR glasses to a view.",
    inputSchema: {
      type: "object",
      properties: { view: { type: "string", enum: [...VIEWS] } },
      required: ["view"],
    },
    command: "show_view",
    args: (i) => ({ view: i.view }),
  },
  {
    name: "lenscode_notify",
    description: "Show a short message on the user's XR glasses (a toast), e.g. that a long task finished.",
    inputSchema: {
      type: "object",
      properties: { message: { type: "string", description: "Up to 300 characters." } },
      required: ["message"],
    },
    command: "notify",
    args: (i) => ({ message: i.message }),
  },
  {
    name: "lenscode_list_machines",
    description: "List the SSH machines the bridge knows (from ~/.ssh/config) and whether each is connected.",
    inputSchema: { type: "object", properties: {} },
    command: "list_machines",
    args: () => ({}),
  },
];
