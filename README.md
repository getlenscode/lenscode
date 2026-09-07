# Aura Workspace

Mixed-reality agent command center for Android XR glasses (XREAL Aura-class).
A spatial control surface for coding agents (Claude, Codex, Hermes, Pi), terminals,
Git branches/worktrees/diffs, and remote SSH machines — driven by gaze + pinch + voice.

## Architecture

```
Android XR client (Jetpack XR / Compose for XR)   → client-android/
   │  WSS/HTTPS (TLS, paired devices only)
   ▼
aura-bridge (auth, pairing, files, search, Git, Paseo session adapter)  → aura-bridge/
   │  WebSocket (@getpaseo/client)
   ▼
Paseo daemon (terminals, agents, workspaces, worktrees — authoritative)
  https://github.com/getpaseo/paseo
```

`client-unity/` (Unity 6 + OpenXR) is archived — the client stack switched to
Jetpack XR. The backend switched from Herdr to Paseo in protocol v2; see
`docs/paseo-migration-plan.md` for the migration and `docs/herdr-ipc.md` for
the retired Herdr integration this replaced.

## Layout

- `client-android/` — Jetpack XR (Compose for XR) Android XR client
- `client-unity/` — archived Unity 6 + OpenXR client (no longer developed)
- `aura-bridge/` — Node/TypeScript XR gateway, now a Paseo session adapter
- `protocol/` — XR protocol v2 schema (single source of truth; generates TS + C# types)
- `docs/` — design notes, migration plan, QA scenarios

## References

- `../Project Specifications Herdr XR.md` — full product specification
- `../MR App Ideas for Productivity.html` — visual mockup of the six primary views
- `docs/paseo-migration-plan.md` — Herdr → Paseo migration plan and phase status
- Implementation plan: milestones M1–M6 (Phase 1 Foundation), then Git/Agents,
  Remote Development, Advanced Productivity.
