package app.autopil.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import app.autopil.core.match.TextNormalizer

internal class SpeechLoop(
    private val context: Context,
    private val prefs: Prefs,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val isSpeaking: () -> Boolean,
    private val isAwaitingConfirm: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var ducked = false
    private var pushToTalk = false
    private var offlineFailed = false
    private var followupUntil = 0L
    private var backoff = 300L
    private var lastHeard = ""
    private var lastHeardAt = 0L

    private val listenRunnable = Runnable { listen() }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus(context.getString(R.string.speech_missing))
            return
        }
        running = true
        ensure()
        if (prefs.continuous) listen() else recognizer?.cancel()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    fun listenOnce() {
        pushToTalk = true
        running = true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus(context.getString(R.string.speech_missing))
            return
        }
        ensure()
        listen()
    }

    fun duck() {
        ducked = true
        recognizer?.cancel()
    }

    fun unduck() {
        ducked = false
        if (running && prefs.continuous) schedule(180)
    }

    private fun ensure() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
        }
    }

    private fun listen() {
        if (!running || ducked || isSpeaking()) {
            if (running && prefs.continuous) schedule(250)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus(context.getString(R.string.status_need_mic))
            return
        }
        ensure()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, prefs.preferOffline && !offlineFailed)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
        }
        try {
            recognizer?.cancel()
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            schedule(backoff)
        }
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delayMs)
    }

    private fun scheduleNext() {
        if (prefs.continuous || pushToTalk) {
            pushToTalk = false
            if (prefs.continuous) schedule(280)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = first(partialResults)
            if (text.isNotBlank() && !isSpeaking()) onStatus(text)
        }

        override fun onResults(results: Bundle?) {
            backoff = 300
            handle(first(results))
            scheduleNext()
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                onStatus(context.getString(R.string.status_need_mic))
                return
            }
            if (prefs.preferOffline && !offlineFailed && error in OFFLINE_FAILURES) {
                offlineFailed = true
                schedule(200)
                return
            }
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (prefs.continuous) schedule(220)
                return
            }
            backoff = (backoff + 200).coerceAtMost(1600)
            if (prefs.continuous) schedule(backoff)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handle(text: String) {
        val cleaned = text.trim()
        if (cleaned.isBlank() || isSpeaking()) return
        val now = SystemClock.elapsedRealtime()
        if (cleaned == lastHeard && now - lastHeardAt < 1200) return
        val normalized = TextNormalizer.normalize(cleaned)
        val armed = pushToTalk || isAwaitingConfirm() || now < followupUntil
        if (!(armed || startsWithWake(normalized) || isInterrupt(normalized))) return
        if (normalized == "автопил" || normalized == "автопилот") {
            followupUntil = now + 6000
            onStatus(context.getString(R.string.listen))
            return
        }
        followupUntil = 0
        lastHeard = cleaned
        lastHeardAt = now
        pushToTalk = false
        onText(cleaned)
    }

    private fun first(bundle: Bundle?): String {
        return bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    }

    private fun startsWithWake(normalized: String): Boolean {
        return normalized == "автопил" ||
            normalized == "автопилот" ||
            normalized.startsWith("автопил ") ||
            normalized.startsWith("автопилот ")
    }

    private fun isInterrupt(normalized: String): Boolean {
        return normalized == "стоп" ||
            normalized == "остановись" ||
            normalized == "хватит" ||
            normalized == "отмена" ||
            normalized == "отмени" ||
            normalized == "stop"
    }

    private companion object {
        val OFFLINE_FAILURES = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        )
    }
}
