# Claude Auto mode (fewer permission prompts)

Why new Claude agents start in Auto mode.

- Paseo's Claude modes include `auto` ("Auto mode": a model classifier answers permission prompts, so the accept card rarely appears). New Claude agents now start in it unless a mode is chosen: `defaultModeIdFor` / `composerModeFor` in `ui/AgentComposer.kt`. It is pinned in the app because an older daemon, or Claude on Bedrock/Vertex (where Paseo drops Auto), defaults to "Always Ask". Only for `claude`: Codex's `auto` id is its ordinary approval flow. Voice: "auto mode" picks it; "accept edits" picks Accept File Edits.
