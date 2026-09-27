# LensCode website competitive analysis

Reviewed September 17, 2026. This note informed the product and documentation site in `website/`.

## Moshi

- **Product:** Mobile terminal and agent companion for long-running shells, SSH/Mosh sessions, multiplexers, approvals, chat, diffs, previews, files, and notifications.
- **Audience and position:** Developers who want their existing machines and terminal workflows available from a phone without moving execution to a hosted IDE.
- **Documentation:** Exceptional task-first information architecture: five ordered start steps, then connections, multiplexers, input, integrations, settings, and troubleshooting.
- **Strong patterns:** Clear prerequisites, honest host/client boundaries, short route into first success, and deep troubleshooting pages.
- **Weakness:** The breadth creates a large navigation surface, and the core interaction remains a handheld terminal.
- **Useful for LensCode:** Lead with a numbered success path and keep current limitations beside the relevant workflow.
- **LensCode difference:** LensCode targets spatial surfaces, gaze/pinch/voice, and movable XR panels rather than optimizing terminal interaction for a phone.
- Source: [Moshi documentation](https://getmoshi.app/docs)

## Litter / KittyLitter

- **Product:** Open-source native iOS and Android client for Codex, Claude, OpenCode, Pi, and Droid, connecting locally, through SSH, or through Alleycat pairing.
- **Audience and position:** Developers who want a friendly, free, multi-provider agent client with minimal setup.
- **Documentation:** The product site emphasizes a feature grid and a three-step loop rather than deep reference material.
- **Strong patterns:** Memorable identity and a concise story: find a server, pick an agent, get to work.
- **Weakness:** Limited technical depth and relatively little architecture or real-product demonstration.
- **Useful for LensCode:** Explain the complete loop before exposing implementation detail.
- **LensCode difference:** Agents are one part of a broader spatial workspace that also includes Git, files, terminals, remote hosts, permissions, and physical placement.
- Source: [Litter product site](https://kittylitter.app/)

## Paseo

- **Product:** Self-hosted, multi-provider control plane for coding agents with desktop/mobile clients, a daemon, CLI, SDK, workspaces, terminals, Git workflows, plugins, and schedules.
- **Audience and position:** Developers and teams running one or many agents across machines and providers.
- **Documentation:** Separate product, CLI, SDK, architecture, and self-hosting surfaces.
- **Strong patterns:** The product itself is the proof: the homepage uses realistic workflow states, interactive tabs, dense but legible UI, and direct download/install actions.
- **Weakness:** New users may need help choosing among the app, daemon, CLI, SDK, host, and relay layers.
- **Useful for LensCode:** Show the real command surface prominently and connect every claim to an inspectable product state.
- **LensCode difference:** LensCode builds on Paseo as the authoritative agent/terminal layer, adding an XR-specific bridge, trust boundary, and spatial interaction surface.
- Sources: [Paseo product site](https://paseo.sh/), [Paseo SDK](https://github.com/getpaseo/paseo/blob/main/public-docs/sdk/index.md)

## Happy / Happier

- **Product:** Open-source remote control for coding agents running on the user's computers, with phone, web, and desktop clients and end-to-end encrypted session sync.
- **Audience and position:** Developers who want to leave the desk without leaving an agent blocked on permissions, questions, or errors.
- **Documentation:** Problem-led product site plus a thorough getting-started path covering client, CLI, relay, account, and agent prerequisites.
- **Strong patterns:** Strong human problem framing, simple install moment, real phone imagery, social proof, and a legible encryption data-flow explanation.
- **Weakness:** Happy/Happier naming has evolved, and the model still centers conventional screens.
- **Useful for LensCode:** Explain architecture through a human continuity problem, not infrastructure alone.
- **LensCode difference:** LensCode extends continuity into glasses, where head-relative panels and gaze/pinch/voice can change the interaction rather than only resize it.
- Sources: [Happy product site](https://happy.engineering/), [Happier getting started](https://docs.happier.dev/getting-started)

## XREAL AURA and Android XR

- XREAL describes AURA as optical-see-through glasses paired with a dedicated Android XR compute puck.
- Official specifications relevant to LensCode include a 70-degree field of view, 1920 x 1200 Micro-OLED display per eye, refresh rates up to 120 Hz, two world-facing tracking cameras, 6DoF spatial anchoring, hand tracking, and native hand, voice, and puck-touchpad input.
- Android's XR Catalyst material explicitly identifies wired XR glasses such as XREAL AURA as targets for Jetpack XR, Unity, Godot, and Unreal development.
- The website uses official XREAL imagery and attributes it. Hardware behavior and availability should still be re-verified before a public launch.
- Sources: [XREAL AURA](https://www.xreal.com/aura), [Android XR Catalyst](https://developer.android.com/develop/xr/catalyst)

## Positioning conclusion

The adjacent category already validates mobile continuity, self-hosting, multi-provider control, remote approvals, and durable sessions. LensCode differs by treating the next device as a spatial environment rather than another rectangular remote.

The defensible language is **“an open-source approach to a persistent spatial computing interaction layer.”** This research does not substantiate a “first open-source” claim, so the site does not make one.
