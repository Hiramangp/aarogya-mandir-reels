package com.aarogyamandir.reels.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's built-in SpeechRecognizer so it keeps listening
 * continuously instead of stopping after one sentence. Android's
 * recognizer session always ends after a short pause in speech, so this
 * class simply restarts it every time it ends, until [stop] is called.
 *
 * Note: this uses the device's default recognizer (usually Google's),
 * which needs the microphone at the same time as the audio recorder.
 * Most modern devices support that, but it is not guaranteed on every
 * device/OEM — see the project README for the manual-tap fallback.
 */
class SpeechEngine(
    context: Context,
    private val onText: (text: String, isFinal: Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())

    private val recognizerIntent: Intent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            restart()
        }

        override fun onError(error: Int) {
            // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT are normal during silence; just restart.
            restart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onText(text, false)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onText(text, true)
            restart()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun restart() {
        if (!listening) return
        handler.postDelayed({
            if (!listening) return@postDelayed
            try {
                recognizer?.cancel()
                recognizer?.startListening(recognizerIntent)
            } catch (_: Exception) {
                // Will try again on the next cycle triggered by onError/onEndOfSpeech.
            }
        }, 200)
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return
        listening = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(listener)
        }
        recognizer?.startListening(recognizerIntent)
    }

    fun stop() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
