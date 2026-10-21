# Architecture: Android XR client, lenscode-bridge, and Paseo

Companion to [Try LensCode](try-it-workflows.md) and [Remote setup and current limits](remote-setup-and-limits.md).

The Android XR client never talks to any Paseo daemon directly. It only
connects to `lenscode-bridge`, which runs on the "bridge computer" (a regular
desktop/laptop, not the glasses/headset) alongside a Paseo daemon.
`lenscode-bridge` holds the bridge user's SSH keys/config and is the only
process that opens SSH tunnels to remote hosts, forwarding to each remote
Paseo daemon's loopback port (`127.0.0.1:6767`). Remote Paseo daemons are
never exposed publicly — the SSH tunnel is the only path to them.

```mermaid
flowchart TB
    subgraph GLASSES["Android XR device / emulator (thin UI client)"]
        APP["LensCode app<br/>MainActivity.kt → WorkspaceRoot.kt<br/>ui/*Panel.kt"]
        BC["BridgeClient.kt<br/>(OkHttp WebSocket + coroutines)<br/>PinnedTls.kt — certificate pinning"]
        APP --> BC
    end

    subgraph DESKTOP["Bridge computer (your desktop/laptop)"]
        BRIDGE["lenscode-bridge (Node/TS server)<br/>server.ts — routing & aggregation<br/>pairing.ts — device pairing<br/>machines.ts — SSH alias discovery<br/>remote.ts — SSH tunnel mgmt<br/>geminiHttp.ts — /setup page + /gemini proxy"]
        SSHCFG["~/.ssh/config<br/>(bridge user's keys & host aliases)"]
        LPASEO["Bridge computer's Paseo daemon<br/>127.0.0.1:6767 · /ws"]
        BRIDGE -. reads .-> SSHCFG
        BRIDGE <-->|localhost WS| LPASEO
    end

    subgraph DEVA["Remote host: dev-a"]
        RPASEOA["Remote Paseo daemon<br/>127.0.0.1:6767 · /ws<br/>(NOT publicly exposed)"]
        CLIA["Coding CLI run by Paseo<br/>(claude / codex)"]
        RPASEOA --- CLIA
    end

    subgraph DEVB["Remote host: dev-b"]
        RPASEOB["Remote Paseo daemon<br/>127.0.0.1:6767 · /ws<br/>(NOT publicly exposed)"]
        CLIB["Coding CLI run by Paseo"]
        RPASEOB --- CLIB
    end

    GEMINI["Google Gemini API<br/>(optional voice routing)"]

    BC <-->|"dev: ws:// :8137<br/>secure: wss:// :8443<br/>(10.0.2.2 in emulator)"| BRIDGE
    BRIDGE -->|"HTTPS, bridge-held key"| GEMINI

    BRIDGE -->|"SSH (BatchMode)<br/>tunnel → forward to<br/>127.0.0.1:6767"| RPASEOA
    BRIDGE -->|"SSH (BatchMode)<br/>tunnel → forward to<br/>127.0.0.1:6767"| RPASEOB

    EXT1["Independent SSH session<br/>(Route B: 'ssh dev-a')"] -.->|outside lenscode-bridge| DEVA
    EXT2["Independent SSH session<br/>(Route B: 'ssh dev-b')"] -.->|outside lenscode-bridge| DEVB

    classDef desktop fill:#e8f0fe,stroke:#4a7fd6,color:#1a1a1a;
    classDef remote fill:#fdece8,stroke:#d66a4a,color:#1a1a1a;
    classDef client fill:#eafbea,stroke:#4ac16a,color:#1a1a1a;
    classDef external fill:#f4f4f4,stroke:#8a8a8a,color:#1a1a1a;
    class DESKTOP,BRIDGE,LPASEO,SSHCFG desktop;
    class DEVA,DEVB,RPASEOA,RPASEOB,CLIA,CLIB remote;
    class GLASSES,APP,BC client;
    class GEMINI external;
```

## Key points

- **One hop for the client**: the app/glasses only ever speaks to
  `lenscode-bridge`, never directly to any Paseo daemon.
- **Two connection modes.** Dev mode (`npx lenscode-bridge`) is plain `ws://`
  on port 8137 and open to the local network; only debug builds accept it.
  Secure mode (`npx lenscode-bridge --secure`) is `wss://` on port 8443 with a
  certificate the bridge makes itself. On first contact the app shows the
  certificate's fingerprint, the user checks it against the one the bridge
  printed, and the app pins that certificate. A new headset then pairs with a
  6-digit code printed in the bridge console. Release builds only accept
  secure addresses. A certificate from a public authority (`--tls-cert` /
  `--tls-key`) also works and needs no fingerprint prompt.
- **`lenscode-bridge` is the only process holding SSH credentials** — that's
  why it lives on a desktop, not the headset.
- **The bridge computer is not a "machine" in the app.** The bridge refers to
  its own Paseo daemon as `"local"` internally, but the app's host pickers list
  only SSH hosts from `~/.ssh/config`.
- **Remote Paseo daemons stay on loopback** (`127.0.0.1:6767`) on their own
  hosts; they're reachable only via the SSH tunnel `lenscode-bridge` opens,
  never exposed publicly.
- **The Gemini key stays on the bridge.** It is set on the bridge's `/setup`
  page (or `GEMINI_API_KEY`), and the headset calls Gemini through the bridge's
  `/gemini/*` proxy, which adds the key. In secure mode the page needs the setup
  code the bridge prints, and the proxy needs a paired device.
- **Route B** (dotted lines): a coding CLI started in an independent
  `ssh dev-a`/`dev-b` session runs outside `lenscode-bridge`, which is why
  those sessions don't show up as XR agent cards.

## Implementation references

- [Window setup](../client-android/app/src/main/java/com/lenscode/app/MainActivity.kt)
- [Navigation, panels, agent targeting, and voice actions](../client-android/app/src/main/java/com/lenscode/app/WorkspaceRoot.kt)
- [Client file roots and terminal machine selection](../client-android/app/src/main/java/com/lenscode/app/net/BridgeClient.kt)
- [Bridge address parsing and default ports](../client-android/app/src/main/java/com/lenscode/app/net/BridgeAddress.kt)
- [Certificate probe and pinning](../client-android/app/src/main/java/com/lenscode/app/net/PinnedTls.kt)
- [SSH alias discovery](../lenscode-bridge/src/machines.ts)
- [Remote Paseo tunnel](../lenscode-bridge/src/remote.ts)
- [Bridge routing and agent handlers](../lenscode-bridge/src/server.ts)
- [Device pairing](../lenscode-bridge/src/pairing.ts)
- [Self-made certificate for secure mode](../lenscode-bridge/src/selfSigned.ts)
- [Gemini setup page and proxy](../lenscode-bridge/src/geminiHttp.ts)
