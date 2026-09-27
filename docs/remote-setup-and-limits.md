# Remote setup and current limits

Companion to [Try LensCode](try-it-workflows.md). These notes describe the checked-in implementation, not a guarantee that your hosts or provider credentials are configured.

## Prepare two machines

“Local” means the computer running `lenscode-bridge` and its connected Paseo daemon. The tablet emulator is the UI client. Remote aliases are read from the bridge user's `~/.ssh/config`.

1. On the bridge computer, add explicit aliases to `~/.ssh/config`, using your actual hosts, users, and keys:

   ```sshconfig
   Host dev-a
       HostName YOUR_FIRST_HOST
       User alex
       IdentityFile ~/.ssh/id_ed25519

   Host dev-b
       HostName YOUR_SECOND_HOST
       User alex
       IdentityFile ~/.ssh/id_ed25519
   ```

2. From that same computer/user, connect to each host interactively once. Resolve normal host-key verification and authentication before opening XR.
3. Confirm unattended SSH works:

   ```sh
   ssh -o BatchMode=yes dev-a 'hostname; pwd'
   ssh -o BatchMode=yes dev-b 'hostname; pwd'
   ```

4. Have a compatible Paseo daemon installed and running on each remote machine. This bridge forwards to `127.0.0.1:6767` on the remote host and connects to `/ws`. Its **Connect** button does not install or start Paseo. Use your installed Paseo version's daemon setup instructions; the repository migration notes use `paseo daemon`.
5. Have local Paseo and `lenscode-bridge` running. In an already configured checkout, the bridge's development command is `npm run dev` from `lenscode-bridge/`.
6. For a local XR agent doing remote work, make SSH available to the agent's runtime too. If the agent runs in a sandbox or container, successful SSH from your desktop alone does not prove the agent can reach it. First send a read-only task asking it to verify the remote hostname and working directory.
7. For AI running directly on a remote host, install and authenticate your chosen coding CLI on that host. This guide assumes it is already available; installing providers is a separate setup task.
8. Launch the app, complete pairing if prompted, and check **bridge connected** and **paseo connected**. Then click **Remote** and confirm both aliases appear.

Use a dedicated project directory for each demo. You do not need to expose Paseo's port publicly: the bridge uses SSH forwarding.

## Current limits that affect these exercises

| Feature | Current behavior |
| --- | --- |
| Remote folder browsing and Code | Routes file operations to the selected SSH machine and directory. Code is a source viewer, not a live app preview or general editor. |
| Remote terminals | Routed to each remote Paseo daemon. XR displays output and offers New, Stop, and Close; no terminal typing field is present. |
| Terminal vs. file selection | Separate machine context. Use Remote → Terminals for terminal switching and Remote → Browse → Open this folder for code switching. |
| New project folder | No New Project or New Folder button. Ask an agent with SSH access to create it, or create it in an external SSH shell. |
| XR agent list, messages, creation, permissions | Bound to local Paseo; native remote agents are not aggregated into these views. |
| Agent card → Open Workspace | Explicitly switches to local. If no workspace ID exists, it opens Terminal at the agent's working directory instead of opening Code. |
| New AI from voice | Requires the configured Gemini/Koog router; creates local Claude with cwd `.` when it cannot reuse an existing target. No machine/provider creation form exists. |
| Follow-up voice tasks | An open agent card wins; otherwise the previous live voice agent can be reused. Saying “new project” does not necessarily create a fresh agent. |
| Agent permission mode | Agents offers Auto, Bypass, and Manual for newly voice-created agents. Manual allows you to inspect requests in Permissions. Changing it does not reconfigure existing agents. |
| Code updates | Reopen a file to fetch new content; reopen the remote folder to refresh its listing. Do not assume live file watching. |
| Git and Diff | Use the current file machine/root context. Open the intended project in Code before inspecting its Git or Diff view. |
| Web preview | No embedded browser preview in the Code panel. Use an external browser and the project's run instructions. |
| Launch layout | Main panel starts centered at its default size; agents and dock are placed relative to its edges. Old saved move/resize transforms are not restored. |

Some Remote UI text still says “Herdr.” The implementation now connects to Paseo; do not install Herdr to satisfy that label.

## Troubleshooting

### Remote shows “No SSH hosts found.”

Check `~/.ssh/config` for the user running the bridge, not the emulator. Use explicit named Host entries. Wildcards, negated aliases, and bare IP aliases are excluded. The registry reads this file directly and does not expand SSH `Include` files; put the named entries in the main file. Leave and reopen Remote to refresh the list.

### Connect fails

Run the BatchMode SSH check above. If SSH works but the app says Paseo is unreachable, check the remote daemon and its port. The current remote port is fixed at 6767. The UI cannot answer an SSH password prompt.

### AI changed files on the wrong machine

Opening remote Code does not change where a local agent runs. Inspect the agent's activity, then explicitly provide the SSH alias and absolute directory. Verify the target with a read-only prompt before retrying a modification.

### My remote AI is missing from Agents

That is a current product limitation. Use its external SSH session for messages and approvals, and browse its files in XR. Running a standalone CLI does not register an XR agent.

### “New” created a terminal, but I cannot type

The XR terminal is currently an output viewer. Use an external SSH terminal or ask an existing XR agent to run the intended command. An external shell's output is not automatically mirrored into Paseo's terminals.

### I sent a task, but nothing seems to happen

Reopen the agent card and read Activity and any error. Check Permissions for a pending local request. For a remote CLI, inspect that CLI's terminal. With voice, ensure you pressed Submit after reviewing the transcript; dictation into an agent field still requires Send.

### New files or edits are not visible

Check the machine and root in Code. Reopen the remote folder through Browse, then click the file again. A selected Terminal on another machine does not retarget Code.

### Diff says “No changes” after creating a project

Check that the correct folder is open and is a Git repository. New/untracked files may not produce a textual hunk diff; Code is the first place to verify a freshly generated project. Use `git status --short` in the external SSH session to check the actual repository state. “Accept” in the diff workflow stages changes; it is not a commit or deployment.

## Implementation references

- [Workspace UI labels and fields](../client-android/app/src/main/java/com/lenscode/app/ui/WorkspaceUi.kt)
- [Navigation, local agent targeting, voice creation, and launch layout](../client-android/app/src/main/java/com/lenscode/app/MainActivity.kt)
- [Client file roots and terminal machine selection](../client-android/app/src/main/java/com/lenscode/app/net/BridgeClient.kt)
- [SSH alias discovery](../lenscode-bridge/src/machines.ts)
- [Remote Paseo tunnel](../lenscode-bridge/src/remote.ts)
- [Bridge routing and local-only agent handlers](../lenscode-bridge/src/server.ts)
- [Migration notes](paseo-migration-plan.md)
