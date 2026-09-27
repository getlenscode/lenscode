package com.lenscode.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error."
                    else -> "Voice search failed (code $error)."
                }
                onError(message)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(intent)
    }
}
