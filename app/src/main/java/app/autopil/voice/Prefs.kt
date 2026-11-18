package app.autopil.voice

import android.content.Context

internal class Prefs(context: Context) {
    private val store = context.getSharedPreferences("autopil", Context.MODE_PRIVATE)

    var beforeMs: Long
        get() = store.getLong(BEFORE, 500L)
        set(value) {
            store.edit().putLong(BEFORE, value.coerceIn(0L, 2000L)).apply()
        }

    var afterMs: Long
        get() = store.getLong(AFTER, 800L)
        set(value) {
            store.edit().putLong(AFTER, value.coerceIn(0L, 2000L)).apply()
        }

    var continuous: Boolean
        get() = store.getBoolean(CONTINUOUS, true)
        set(value) {
            store.edit().putBoolean(CONTINUOUS, value).apply()
        }

    var preferOffline: Boolean
        get() = store.getBoolean(OFFLINE, true)
        set(value) {
            store.edit().putBoolean(OFFLINE, value).apply()
        }

    private companion object {
        const val BEFORE = "before"
        const val AFTER = "after"
        const val CONTINUOUS = "continuous"
        const val OFFLINE = "offline"
    }
}
