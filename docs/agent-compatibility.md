# Agent compatibility

LensCode uses Paseo's provider adapters for agent creation, messages, activity,
permissions, and archiving. Install and authenticate each agent on the machine
that will run it. The model and mode pickers load that machine's catalogs.

| Provider | Paseo ID | Permission modes |
| --- | --- | --- |
| Pi | `pi` | No permission modes; LensCode omits `modeId` |
| Codex | `codex` | Native modes returned by Paseo, including `auto`, `auto-review`, and `full-access` |
| Oh My Pi | `omp` | `ask`, `write`, `full` |

Mode IDs are discovered from the daemon rather than shared between providers.
Switching providers resets the selected mode to Provider default. A stale or
unavailable mode is omitted from agent creation. Model discovery can fail without
hiding a provider's modes, and mode discovery can fail without hiding its models.
Full-access modes retain the composer's confirmation step. A one-shot spoken
request does not turn on a full-access mode.

Oh My Pi is disabled by default in the inspected Paseo installation. Install and
authenticate `omp`, then merge the following into the **existing** Paseo daemon
configuration (`~/.paseo/config.json`), preserving its other settings:

```json
{
  "providers": {
    "omp": { "enabled": true }
  }
}
```

Reload the daemon configuration (`paseo reload`). Check `paseo provider ls` and
`paseo provider diagnostic omp` before choosing OMP in LensCode. The installed
Paseo OMP adapter requires OMP 16.3.9 or later. Check the adapter's requirement
again when upgrading Paseo. The `omp` executable must be on the daemon's PATH.

## Verification

From `lenscode-bridge/`:

```sh
npm test
npm run test:agents:live
# Or check just one provider:
npm run test:agents:live -- codex
```

The live check connects to `LENSCODE_PASEO_URL` (default
`ws://127.0.0.1:6767/ws`, optional `LENSCODE_PASEO_PASSWORD`), creates temporary
agents, sends an initial prompt and a follow-up, verifies assistant replies in
the activity timeline, and archives its sessions. It makes real model calls.
Missing/disabled providers and failed replies produce a nonzero exit code.
The check uses a temporary directory and asks agents not to use tools or edit files.

On 2026-10-21, Pi and Codex passed the live create/list/message/reply/follow-up/
timeline/archive check against the local Paseo 0.12.0 daemon with the bridge's
0.10.2 SDK. OMP was disabled and its executable was absent from PATH, so its live
check could not run. OMP config forwarding, modes, follow-up, timeline, permission
responses, and archiving have adapter regression coverage; that does not replace
an authenticated OMP live check.
