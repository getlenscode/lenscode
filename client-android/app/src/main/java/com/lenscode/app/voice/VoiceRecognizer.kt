package com.lenscode.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper over Android's on-device [SpeechRecognizer] for voice file
 * search (spec §13, §63). One recognizer instance per listening session —
 * `destroy()` after each result/error, since the platform API doesn't like
 * being reused across many start/stop cycles.
 */
class VoiceRecognizer(private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts one listening session. Exactly one of [onResult] / [onError]
     * fires. [onPartial] is best-effort UI feedback only — the query actually
     * sent to the bridge always comes from [onResult].
     */
    fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!isAvailable()) {
            onError("Voice recognition isn't available on this device.")
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                recognizer.destroy()
                if (text.isNullOrBlank()) onError("Didn't catch that.") else onResult(text)
            }

            override fun onError(error: Int) {
                recognizer.destroy()
                onError(errorMessage(error))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(intent)
    }

    /**
     * Continuous dictation — SpeechRecognizer always endpoint-detects and has
     * a hard per-utterance cap, so a long dictate is actually a restart loop:
     * each finalized utterance fires [onSegment], the recognizer is recreated,
     * and listening resumes until [DictationSession.stop]. Silence-tolerance
     * extras raise the mid-thought pause window (best-effort — honored by
     * Google's recognizer, ignored by some OEM services). Transient errors
     * retry with a short backoff; a run of them, or a fatal one, ends the
     * session via [onError].
     */
    fun startDictation(
        onPartial: (String) -> Unit,
        onSegment: (String) -> Unit,
        onError: (String) -> Unit,
    ): DictationSession {
        if (!isAvailable()) {
            onError("Voice recognition isn't available on this device.")
            return DictationSession {}
        }
        val main = Handler(Looper.getMainLooper())
        var stopped = false
        var recognizer: SpeechRecognizer? = null
        var consecutiveErrors = 0

        fun listen() {
            if (stopped) return
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_UTTERANCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, MAYBE_SILENCE_MS)
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) onPartial(text)
                }

                override fun onResults(results: Bundle?) {
                    consecutiveErrors = 0
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) onSegment(text)
                    runCatching { r.destroy() }
                    recognizer = null
                    // A brief gap between segments — recreating the recognizer
                    // instantly races the audio session teardown.
                    main.postDelayed({ listen() }, SEGMENT_RESTART_DELAY_MS)
                }

                override fun onError(error: Int) {
                    runCatching { r.destroy() }
                    recognizer = null
                    val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
                    if (fatal || ++consecutiveErrors >= MAX_CONSECUTIVE_TRANSIENT_ERRORS) {
                        stopped = true
                        onError(errorMessage(error))
                    } else {
                        main.postDelayed({ listen() }, ERROR_RETRY_DELAY_MS)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            r.startListening(intent)
        }
        listen()
        return DictationSession {
            stopped = true
            recognizer?.let { r -> runCatching { r.stopListening(); r.destroy() } }
            recognizer = null
        }
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error."
        else -> "Voice input failed (code $error)."
    }

    /** Opaque end-control for [startDictation] — call [stop] once; idempotent. */
    class DictationSession internal constructor(private val stopFn: () -> Unit) {
        fun stop() = stopFn()
    }

    private companion object {
        // Silence-tolerance hints for dictation: thinking pauses shouldn't end
        // the utterance. Honored by Google's recognizer, ignored by some OEMs.
        const val MIN_UTTERANCE_MS = 4_000L
        const val COMPLETE_SILENCE_MS = 8_000L
        const val MAYBE_SILENCE_MS = 6_000L
        // Recreating the recognizer instantly races audio-session teardown.
        const val SEGMENT_RESTART_DELAY_MS = 250L
        const val ERROR_RETRY_DELAY_MS = 350L
        // A run of back-to-back transient errors means the recognizer is dead
        // (offline model missing etc.) — surface it rather than looping forever.
        const val MAX_CONSECUTIVE_TRANSIENT_ERRORS = 4
    }
}
