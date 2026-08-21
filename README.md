# Aura Workspace

Mixed-reality agent command center for Android XR glasses (XREAL Aura-class).
A spatial control surface for coding agents (Claude, Codex, Hermes, Pi), terminals,
Git branches/worktrees/diffs, and remote SSH machines — driven by gaze + pinch + voice.

## Architecture

```
Unity XR client (Android XR glasses / emulator)   → client-unity/
   │  WSS/HTTPS (TLS, paired devices only)
   ▼
aura-bridge (auth, pairing, files, search, Git, Herdr IPC proxy)  → aura-bridge/
   │  local IPC
   ▼
Herdr (PTYs, terminals, workspaces, panes, agents, worktrees — authoritative)
```

## Layout

- `client-unity/` — Unity 6 + OpenXR Android XR client
- `aura-bridge/` — Node/TypeScript XR gateway
- `protocol/` — XR protocol v1 schema (single source of truth; generates TS + C# types)
- `docs/` — design notes, Herdr IPC documentation, QA scenarios

## References

- `../Project Specifications Herdr XR.md` — full product specification
- `../MR App Ideas for Productivity.html` — visual mockup of the six primary views
- Implementation plan: milestones M1–M6 (Phase 1 Foundation), then Git/Agents,
  Remote Development, Advanced Productivity.
