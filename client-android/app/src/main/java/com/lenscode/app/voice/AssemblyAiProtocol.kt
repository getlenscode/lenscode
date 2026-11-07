package com.lenscode.app.voice

import org.json.JSONObject
import java.net.URLEncoder

/**
 * The parts of AssemblyAI's streaming v3 WebSocket protocol that need no microphone or socket, so they
 * can be unit-tested: the connection URL and the server's JSON messages.
 */
sealed class AssemblyEvent {
    data object Begin : AssemblyEvent()

    /**
     * A running transcript of the current turn. [endOfTurn] marks it final; with `format_turns` on, the
     * server sends the turn twice: once raw, then again with punctuation and casing ([formatted]).
     */
    data class Turn(val transcript: String, val endOfTurn: Boolean, val formatted: Boolean) : AssemblyEvent()

    data object Termination : AssemblyEvent()
    data class Error(val message: String) : AssemblyEvent()

    /** Anything we don't act on (future message types, malformed JSON). */
    data object Ignored : AssemblyEvent()
}

object AssemblyAiProtocol {
    const val SAMPLE_RATE = 16_000

    /**
     * [base] is the bridge-supplied `wss://streaming.assemblyai.com/v3/ws`. The temporary [token] goes in
     * the query string (a WebSocket can't be given headers from some clients); it expires in a minute and
     * is not the account key.
     */
    fun streamUrl(base: String, token: String, model: String, formatTurns: Boolean): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$base?sample_rate=$SAMPLE_RATE&encoding=pcm_s16le&speech_model=${enc(model)}" +
            "&format_turns=$formatTurns&token=${enc(token)}"
    }

    fun parse(text: String): AssemblyEvent {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return AssemblyEvent.Ignored
        return when (json.optString("type")) {
            "Begin" -> AssemblyEvent.Begin
            "Turn" -> AssemblyEvent.Turn(
                transcript = json.optString("transcript", "").trim(),
                endOfTurn = json.optBoolean("end_of_turn", false),
                formatted = json.optBoolean("turn_is_formatted", false),
            )
            "Termination" -> AssemblyEvent.Termination
            else -> if (json.has("error")) AssemblyEvent.Error(json.optString("error")) else AssemblyEvent.Ignored
        }
    }

    const val TERMINATE_MESSAGE = """{"type":"Terminate"}"""

    /** Human wording for a socket that closed early; AssemblyAI puts the reason in the close frame. */
    fun closeMessage(code: Int, reason: String): String = when {
        code == 1000 || code == 1005 -> ""
        reason.isNotBlank() -> "Speech service closed: $reason"
        else -> "Speech service closed the connection (code $code)."
    }
}

/**
 * Folds the stream of [AssemblyEvent.Turn]s into running text and finished turns. [wantFormatted] says
 * which copy of a finished turn counts: with `format_turns` on the raw copy of a turn is shown as live
 * text and the formatted copy completes it; with it off the raw copy completes it.
 */
class AssemblyTurns(private val wantFormatted: Boolean) {
    private val finished = mutableListOf<String>()
    private var live = ""

    /** Everything heard so far: finished turns plus the one in progress. */
    val text: String get() = (finished + live).filter { it.isNotBlank() }.joinToString(" ")

    /** Just the finished turns, for a single-shot result. */
    val finishedText: String get() = finished.joinToString(" ")

    /** Returns the turn's text when this message completes it; null while it is still in progress. */
    fun onTurn(turn: AssemblyEvent.Turn): String? {
        val completes = turn.endOfTurn && (!wantFormatted || turn.formatted)
        if (!completes) {
            live = turn.transcript
            return null
        }
        live = ""
        if (turn.transcript.isBlank()) return null
        finished += turn.transcript
        return turn.transcript
    }
}
