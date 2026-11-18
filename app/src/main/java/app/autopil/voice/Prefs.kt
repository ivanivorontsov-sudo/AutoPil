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

    var noticeVersion: String
        get() = store.getString(NOTICE, "").orEmpty()
        set(value) {
            store.edit().putString(NOTICE, value).apply()
        }

    val noticeAccepted: Boolean
        get() = noticeVersion == NOTICE_VERSION

    companion object {
        const val NOTICE_VERSION = "2026-11-18"
        private const val BEFORE = "before"
        private const val AFTER = "after"
        private const val CONTINUOUS = "continuous"
        private const val OFFLINE = "offline"
        private const val NOTICE = "notice"
    }
}
