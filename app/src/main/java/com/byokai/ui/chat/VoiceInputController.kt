package com.byokai.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import com.byokai.R
import java.util.Locale

/**
 * Thin main-thread wrapper around [SpeechRecognizer] for voice input.
 *
 * Honesty notes (device behavior, not unit-testable):
 * - Recognition is NOT purely on-device: it requires Google's speech
 *   recognition service (or the device's chosen recognizer) and usually
 *   a network connection. [start] reports [R.string.voice_error_unsupported]
 *   when [SpeechRecognizer.isRecognitionAvailable] is false.
 * - All methods must be called on the main thread ([MainThread]); the
 *   underlying [SpeechRecognizer] is not thread-safe.
 * - Only final results are delivered via [onResult] (the first
 *   hypothesis string); partial results are requested but not surfaced,
 *   keeping the contract minimal per Phase 9B scope.
 * - [destroy] is idempotent; call it when the owning screen is disposed
 *   (the [ChatViewModel] also calls it defensively in `onCleared`).
 */
class VoiceInputController(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /**
     * Starts listening. Exactly one of [onResult]/[onError] fires per
     * start (an error after a result, or a second start, cannot
     * double-deliver). A previous session is stopped first.
     */
    @MainThread
    fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        this.onResult = onResult
        this.onError = onError
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            deliverError(context.getString(R.string.voice_error_unsupported))
            return
        }
        stopInternal()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        this.recognizer = recognizer
        recognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    /** Stops listening; a final result may still be delivered. */
    @MainThread
    fun stop() {
        recognizer?.stopListening()
    }

    /** Releases the recognizer. Idempotent. */
    @MainThread
    fun destroy() {
        stopInternal()
        onResult = null
        onError = null
    }

    private fun stopInternal() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun deliverResult(text: String) {
        val callback = onResult
        onResult = null
        onError = null
        stopInternal()
        callback?.invoke(text)
    }

    private fun deliverError(message: String) {
        val callback = onError
        onResult = null
        onError = null
        stopInternal()
        callback?.invoke(message)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val text = results
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            deliverResult(text)
        }

        override fun onError(error: Int) {
            deliverError(context.getString(errorMessageRes(error)))
        }

        // Partial results are intentionally not surfaced (see KDoc).
        override fun onPartialResults(partialResults: Bundle) = Unit
        override fun onReadyForSpeech(params: Bundle) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle) = Unit
    }

    private fun errorMessageRes(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_SERVER,
        -> R.string.voice_error_network
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> R.string.voice_error_no_match
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_error_permission
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_busy
        // ERROR_CLIENT (caller stopped early), ERROR_AUDIO, and any
        // future codes: generic failure, never a crash.
        else -> R.string.voice_error_unknown
    }
}
