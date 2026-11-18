package app.autopil.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

internal class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)

    @Volatile
    var speaking: Boolean = false
        private set

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val russian = tts.setLanguage(Locale("ru", "RU"))
        ready = russian != TextToSpeech.LANG_MISSING_DATA && russian != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) {
            ready = tts.setLanguage(Locale.getDefault()) != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!awaitReady()) return
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                speaking = false
                tts.stop()
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking = true
                }

                override fun onDone(utteranceId: String?) {
                    speaking = false
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in the platform listener")
                override fun onError(utteranceId: String?) {
                    speaking = false
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onError(utteranceId)
                }
            })
            speaking = true
            val id = "a${System.nanoTime()}"
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result == TextToSpeech.ERROR) {
                speaking = false
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    fun shutdown() {
        speaking = false
        tts.shutdown()
    }

    private suspend fun awaitReady(): Boolean {
        if (ready) return true
        return withTimeoutOrNull(1500) {
            while (!ready) delay(40)
            true
        } == true
    }
}
