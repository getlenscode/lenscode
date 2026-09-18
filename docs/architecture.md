# Architecture: Android XR client, aura-bridge, and Paseo

Companion to [Try Aura Workspace](try-it-workflows.md) and [Remote setup and current limits](remote-setup-and-limits.md).

The Android XR client never talks to any Paseo daemon directly. It only
connects to `aura-bridge`, which runs on the "bridge computer" (a regular
desktop/laptop, not the glasses/headset) alongside a local Paseo daemon.
`aura-bridge` holds the bridge user's SSH keys/config and is the only
process that opens SSH tunnels to remote hosts, forwarding to each remote
Paseo daemon's loopback port (`127.0.0.1:6767`). Remote Paseo daemons are
never exposed publicly — the SSH tunnel is the only path to them.

```mermaid
flowchart TB
    subgraph GLASSES["Android XR device / emulator (thin UI client)"]
        APP["Aura Workspace app<br/>WorkspaceUi.kt / MainActivity.kt"]
        BC["BridgeClient.kt<br/>(OkHttp + coroutines)"]
        APP --> BC
    end

    subgraph DESKTOP["Bridge computer ('local' machine — your desktop/laptop)"]
        BRIDGE["aura-bridge (Node/TS server)<br/>server.ts — routing & aggregation<br/>machines.ts — SSH alias discovery<br/>remote.ts — SSH tunnel mgmt"]
        SSHCFG["~/.ssh/config<br/>(bridge user's keys & host aliases)"]
        LPASEO["Local Paseo daemon<br/>127.0.0.1:6767 · /ws"]
        BRIDGE -. reads .-> SSHCFG
        BRIDGE <-->|localhost WS| LPASEO
    end

    subgraph DEVA["Remote host: dev-a"]
        RPASEOA["Remote Paseo daemon<br/>127.0.0.1:6767 · /ws<br/>(NOT publicly exposed)"]
        CLIA["Coding CLI<br/>(claude / codex, run by an<br/>XR agent's SSH commands,<br/>or an independent SSH session)"]
        RPASEOA --- CLIA
    end

    subgraph DEVB["Remote host: dev-b"]
        RPASEOB["Remote Paseo daemon<br/>127.0.0.1:6767 · /ws<br/>(NOT publicly exposed)"]
        CLIB["Coding CLI"]
        RPASEOB --- CLIB
    end

    BC <-->|"WS/HTTP :8137<br/>(10.0.2.2 in emulator)"| BRIDGE

    BRIDGE -->|"SSH (BatchMode)<br/>tunnel → forward to<br/>127.0.0.1:6767"| RPASEOA
    BRIDGE -->|"SSH (BatchMode)<br/>tunnel → forward to<br/>127.0.0.1:6767"| RPASEOB

    EXT1["External SSH terminal<br/>(Route B: 'ssh dev-a')"] -.->|independent session,<br/>not seen by aura-bridge| RPASEOA
    EXT2["External SSH terminal<br/>(Route B: 'ssh dev-b')"] -.->|independent session,<br/>not seen by aura-bridge| RPASEOB

    classDef desktop fill:#e8f0fe,stroke:#4a7fd6,color:#1a1a1a;
    classDef remote fill:#fdece8,stroke:#d66a4a,color:#1a1a1a;
    classDef client fill:#eafbea,stroke:#4ac16a,color:#1a1a1a;
    class DESKTOP,BRIDGE,LPASEO,SSHCFG desktop;
    class DEVA,DEVB,RPASEOA,RPASEOB,CLIA,CLIB remote;
    class GLASSES,APP,BC client;
```

## Key points

- **One hop for the client**: the app/glasses only ever speaks to
  `aura-bridge`, never directly to any Paseo daemon.
- **`aura-bridge` is the only process holding SSH credentials** — that's
  why it lives on a desktop, not the headset.
- **Remote Paseo daemons stay on loopback** (`127.0.0.1:6767`) on their own
  hosts; they're reachable only via the SSH tunnel `aura-bridge` opens,
  never exposed publicly.
- **Route B** (dotted lines) — an independent `ssh dev-a`/`dev-b` session
  with a CLI running directly — talks to the same remote Paseo daemon but
  completely outside `aura-bridge`'s awareness, which is why those sessions
  don't show up as XR agent cards.

## Implementation references

- [Workspace UI labels and fields](../client-android/app/src/main/java/dev/aura/xr/ui/WorkspaceUi.kt)
- [Navigation, local agent targeting, voice creation, and launch layout](../client-android/app/src/main/java/dev/aura/xr/MainActivity.kt)
- [Client file roots and terminal machine selection](../client-android/app/src/main/java/dev/aura/xr/net/BridgeClient.kt)
- [SSH alias discovery](../aura-bridge/src/machines.ts)
- [Remote Paseo tunnel](../aura-bridge/src/remote.ts)
- [Bridge routing and local-only agent handlers](../aura-bridge/src/server.ts)
