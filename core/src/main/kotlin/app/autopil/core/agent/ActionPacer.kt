package app.autopil.core.agent

import kotlinx.coroutines.delay

/**
 * Steady pauses, not random ones. The highlight stays visible long enough to say «стоп»,
 * and the next screen has time to finish opening. The length is chosen in settings.
 */
class ActionPacer(
    private val beforeMs: () -> Long,
    private val afterMs: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val shouldAbort: () -> Boolean = { false },
) {
    fun isAborted(): Boolean = shouldAbort()

    suspend fun beforeAction(): Boolean = wait(beforeMs().coerceAtLeast(0))

    suspend fun afterAction(): Boolean = wait(afterMs().coerceAtLeast(0))

    suspend fun settle(millis: Long): Boolean = wait(millis.coerceAtLeast(0))

    private suspend fun wait(total: Long): Boolean {
        var left = total
        while (left > 0) {
            if (shouldAbort()) return false
            val step = minOf(SLICE_MS, left)
            sleep(step)
            left -= step
        }
        return !shouldAbort()
    }

    private companion object {
        const val SLICE_MS = 50L
    }
}
