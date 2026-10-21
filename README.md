# LensCode

Mixed-reality agent command center for Android XR glasses (XREAL LensCode-class).
A spatial control surface for coding agents (Claude, Codex, Hermes, Pi), terminals,
Git branches/worktrees/diffs, and remote SSH machines — driven by gaze + pinch + voice.

## Architecture

```
Android XR client (Jetpack XR / Compose for XR)   → client-android/
   │  WSS/HTTPS (TLS, paired devices only)
   ▼
lenscode-bridge (auth, pairing, files, search, Git, Paseo session adapter)  → lenscode-bridge/
   │  WebSocket (@getpaseo/client)
   ▼
Paseo daemon (terminals, agents, workspaces, worktrees — authoritative)
  https://github.com/getpaseo/paseo
```

`client-unity/` (Unity 6 + OpenXR) is archived — the client stack switched to
Jetpack XR. The backend switched to Paseo in protocol v2; see
`docs/paseo-migration-plan.md` for the migration.

## Layout

- `client-android/` — Jetpack XR (Compose for XR) Android XR client
- `client-unity/` — archived Unity 6 + OpenXR client (no longer developed)
- `lenscode-bridge/` — Node/TypeScript XR gateway, now a Paseo session adapter
- `protocol/` — XR protocol v3 schema (single source of truth; generates TS + C# types)
- `docs/` — design notes, migration plan, QA scenarios
- `website/` — product story and technical documentation site

## References

- [Try it: remote projects, AI coding, and switching machines](docs/try-it-workflows.md) — click-by-click exercises and example prompts.
- [Remote setup and current limits](docs/remote-setup-and-limits.md) — prerequisites, supported paths, and troubleshooting.
- [Website competitive analysis](docs/website-competitive-analysis.md) — product, documentation, and positioning patterns reviewed before the site build.
- `../MR App Ideas for Productivity.html` — visual mockup of the six primary views
- `docs/paseo-migration-plan.md` — Paseo migration plan and phase status
- Implementation plan: milestones M1–M6 (Phase 1 Foundation), then Git/Agents,
  Remote Development, Advanced Productivity.
