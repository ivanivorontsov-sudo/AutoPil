package app.autopil.voice

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object AutopilHub {
    interface Controls {
        fun listenOnce()
        fun submit(text: String)
        fun refreshListening()
    }

    data class State(
        val serviceOn: Boolean = false,
        val listening: Boolean = false,
        val status: String = "",
        val heard: String = "",
        val prompt: String = "",
        val log: List<String> = emptyList(),
    )

    @Volatile
    var controls: Controls? = null

    @Volatile
    var state: State = State()
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(block: (State) -> State) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(block)
        } else {
            main.post { apply(block) }
        }
    }

    fun listen(listener: (State) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners -= listener }
    }

    private fun apply(block: (State) -> State) {
        val next = block(state)
        state = next
        listeners.forEach { it(next) }
    }
}
