# Speech-to-text (AssemblyAI)

How the headset turns speech into text. What the text becomes is in [voice-routing.md](voice-routing.md).

- The Speak buttons and dictation use AssemblyAI streaming only. There is deliberately **no fallback** to Google's on-device `VoiceRecognizer` (kept in the tree, unused): a missing key or unreachable bridge raises the `VoiceProblemOverlay` popup. `voice/SpeechInput` wraps it; `voice/AssemblyAiRecognizer.kt` streams 16 kHz PCM; `AssemblyAiProtocol.kt` is the testable message/URL logic.
- The key lives on the bridge, like OpenRouter's: paste it on `/setup` (second section), or `ASSEMBLYAI_API_KEY` / `--assemblyai-key`. Stored in `~/.config/lenscode-bridge/assemblyai.json` (0600). The headset calls `GET /assemblyai/token` (paired-device headers in prod) and gets a 60 s temporary token plus the model; the account key never reaches it.
- Universal-2 is AssemblyAI's pre-recorded model, not a streaming one. Streaming models: `universal-streaming-english` (default), `universal-streaming-multilingual`, `universal-3-*-pro`; change it on `/setup` or `LENSCODE_ASSEMBLYAI_MODEL`.
- Single-shot waits 1.2 s after an end-of-turn for another turn, because spoken digit groups pause. Not yet run against the real service from the headset.
