package com.lenscode.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.lenscode.app.net.AssemblyTokenResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * Streaming speech-to-text through AssemblyAI. The microphone is sent as 16 kHz PCM over a WebSocket
 * and the transcript comes back while you speak. The account key stays on the bridge: [fetchToken]
 * asks it for a one-minute token, so what this class holds can't be reused for long.
 *
 * Same shape as [VoiceRecognizer] (the on-device engine, kept but no longer used). There is
 * deliberately no fallback to it: when the bridge has no key, or can't be reached, [onUnavailable]
 * fires instead of any other callback so the caller can tell the user what to fix.
 */
class AssemblyAiRecognizer(
    private val fetchToken: () -> AssemblyTokenResult,
    private val client: OkHttpClient = OkHttpClient(),
) {
    /**
     * One utterance (an address, a file query). Spoken numbers pause between groups, so after the
     * service says a turn ended we wait [TURN_GRACE_MS] for another before finishing.
     */
    fun listen(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onUnavailable: (AssemblyTokenResult.Unavailable) -> Unit,
    ) {
        Session(
            dictation = false,
            onPartial = onPartial,
            onSegment = onResult,
            onError = onError,
            onUnavailable = onUnavailable,
        ).start()
    }

    /** Keeps the mic open until [VoiceRecognizer.DictationSession.stop]; each finished turn is a segment. */
    fun startDictation(
        onPartial: (String) -> Unit,
        onSegment: (String) -> Unit,
        onError: (String) -> Unit,
        onUnavailable: (AssemblyTokenResult.Unavailable) -> Unit,
    ): VoiceRecognizer.DictationSession {
        val session = Session(
            dictation = true,
            onPartial = onPartial,
            onSegment = onSegment,
            onError = onError,
            onUnavailable = onUnavailable,
        )
        session.start()
        return VoiceRecognizer.DictationSession { session.stop() }
    }

    private inner class Session(
        val dictation: Boolean,
        val onPartial: (String) -> Unit,
        val onSegment: (String) -> Unit,
        val onError: (String) -> Unit,
        val onUnavailable: (AssemblyTokenResult.Unavailable) -> Unit,
    ) {
        private val main = Handler(Looper.getMainLooper())
        private val turns = AssemblyTurns(wantFormatted = dictation)
        private var socket: WebSocket? = null
        private var recorder: AudioRecord? = null

        @Volatile private var recording = false

        // Touched only on the main thread.
        private var finished = false
        private var heardAnything = false
        private val finishTask = Runnable { finishSingleShot() }
        private val noSpeechTask = Runnable { fail("No speech detected.") }
        private val hardCapTask = Runnable { if (turns.finishedText.isBlank()) fail("No speech detected.") else finishSingleShot() }
        private val closeTask = Runnable { closeSocket() }

        fun start() {
            Thread({
                when (val token = fetchToken()) {
                    is AssemblyTokenResult.Unavailable -> main.post { if (!finished) { finished = true; onUnavailable(token) } }
                    is AssemblyTokenResult.Ok -> main.post { connect(token) }
                }
            }, "assemblyai-token").start()
        }

        private fun connect(token: AssemblyTokenResult.Ok) {
            if (finished) return
            val url = AssemblyAiProtocol.streamUrl(token.url, token.token, token.model, formatTurns = dictation)
            socket = client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = startMic(webSocket)

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        main.post { handle(AssemblyAiProtocol.parse(text)) }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        main.post { closed(AssemblyAiProtocol.closeMessage(code, reason)) }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        main.post { closed(t.message ?: "Speech service unreachable.") }
                    }
                },
            )
            if (!dictation) {
                main.postDelayed(noSpeechTask, NO_SPEECH_MS)
                main.postDelayed(hardCapTask, HARD_CAP_MS)
            }
        }

        @SuppressLint("MissingPermission") // RECORD_AUDIO is requested before any voice mode starts
        private fun startMic(webSocket: WebSocket) {
            val minBuffer = AudioRecord.getMinBufferSize(
                AssemblyAiProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AssemblyAiProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_BYTES * 4),
                )
            }.getOrNull()
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                main.post { fail("Microphone unavailable.") }
                return
            }
            recorder = record
            recording = true
            record.startRecording()
            Thread({
                val buf = ByteArray(FRAME_BYTES)
                while (recording) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) webSocket.send(buf.toByteString(0, n))
                    else if (n < 0) break
                }
                runCatching { record.stop() }
                record.release()
            }, "assemblyai-mic").start()
        }

        private fun handle(event: AssemblyEvent) {
            if (finished && !dictation) return
            when (event) {
                is AssemblyEvent.Turn -> {
                    val done = turns.onTurn(event)
                    if (turns.text.isNotBlank()) {
                        heardAnything = true
                        main.removeCallbacks(noSpeechTask)
                        onPartial(turns.text)
                    }
                    if (done != null) {
                        if (dictation) onSegment(done) else rearmFinish()
                    } else if (!dictation) {
                        main.removeCallbacks(finishTask) // still talking
                    }
                }
                AssemblyEvent.Termination -> closeSocket()
                is AssemblyEvent.Error -> fail(event.message)
                AssemblyEvent.Begin, AssemblyEvent.Ignored -> Unit
            }
        }

        private fun rearmFinish() {
            main.removeCallbacks(finishTask)
            main.postDelayed(finishTask, TURN_GRACE_MS)
        }

        private fun finishSingleShot() {
            if (finished) return
            finished = true
            val text = turns.finishedText
            teardown()
            if (text.isBlank()) onError("Didn't catch that.") else onSegment(text)
        }

        /** Ends a dictation: the open turn is finalized by Terminate, so give its last segment a moment to arrive. */
        fun stop() {
            main.post {
                if (finished) return@post
                if (!dictation) { finishSingleShot(); return@post }
                finished = true
                stopMic()
                socket?.send(AssemblyAiProtocol.TERMINATE_MESSAGE)
                main.postDelayed(closeTask, CLOSE_WAIT_MS)
            }
        }

        private fun closed(message: String) {
            if (finished && !dictation) return
            val wasLive = !finished
            finished = true
            teardown()
            if (wasLive && message.isNotEmpty()) onError(message)
        }

        private fun fail(message: String) {
            if (finished) return
            finished = true
            teardown()
            onError(message)
        }

        private fun teardown() {
            listOf(finishTask, noSpeechTask, hardCapTask, closeTask).forEach(main::removeCallbacks)
            stopMic()
            socket?.send(AssemblyAiProtocol.TERMINATE_MESSAGE)
            closeSocket()
        }

        private fun stopMic() {
            recording = false
        }

        private fun closeSocket() {
            socket?.close(NORMAL_CLOSE, null)
            socket = null
        }
    }

    private companion object {
        // 100 ms of 16 kHz mono PCM16; the service wants 50–1000 ms per frame.
        const val FRAME_BYTES = 3_200
        const val NORMAL_CLOSE = 1000
        const val TURN_GRACE_MS = 1_200L
        const val NO_SPEECH_MS = 8_000L
        const val HARD_CAP_MS = 30_000L
        const val CLOSE_WAIT_MS = 2_000L
    }
}

/**
 * The speech engine the app uses: AssemblyAI streaming, nothing else. A missing key or an unreachable
 * bridge is reported through [onSetupProblem] (the app shows it as a popup) rather than quietly
 * switching to another recognizer.
 */
class SpeechInput(
    private val assembly: AssemblyAiRecognizer,
    private val onSetupProblem: (AssemblyTokenResult.Unavailable) -> Unit,
) {
    fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) =
        assembly.listen(onPartial, onResult, onError, onSetupProblem)

    fun startDictation(
        onPartial: (String) -> Unit,
        onSegment: (String) -> Unit,
        onError: (String) -> Unit,
    ): VoiceRecognizer.DictationSession = assembly.startDictation(onPartial, onSegment, onError, onSetupProblem)
}

/** What to tell the user when [SpeechInput] reports a setup problem. */
fun speechSetupMessage(problem: AssemblyTokenResult.Unavailable, setupUrl: String): String =
    if (problem.keyMissing) {
        "Speech-to-text needs an AssemblyAI API key. On your phone or PC, open $setupUrl and paste it " +
            "under \"AssemblyAI key\" (or start the bridge with ASSEMBLYAI_API_KEY set)."
    } else {
        "Speech-to-text isn't available: ${problem.reason}. Check the bridge is running and its " +
            "AssemblyAI key is valid at $setupUrl."
    }
