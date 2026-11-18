package app.autopil.core.match

import java.util.Locale

object TextNormalizer {
    fun normalize(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[«»\"'`.,!?()\\[\\]{}:;_/\\\\|—–-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Keeps the original case. Speech text that will be typed must not be lowercased. */
    fun tidy(raw: String): String {
        return raw.replace('ё', 'е')
            .replace('Ё', 'Е')
            .replace(Regex("[\\t\\r\\n]+"), " ")
            .replace(Regex(" +"), " ")
            .trim()
            .trimEnd('.', '!', '?', '…')
            .trim()
    }
}

internal fun stemToken(token: String): String {
    if (token.length <= 4) return token
    val endings = listOf(
        "иями", "ями", "ами", "ого", "ему", "ыми", "ими",
        "иях", "ах", "ях", "ов", "ев", "ей", "ий", "ый", "ой",
        "ая", "яя", "ое", "ее", "ии", "ие", "ые", "ую", "юю",
        "ом", "ем", "ам", "ям", "а", "я", "ы", "и", "у", "ю", "е", "о",
    )
    for (ending in endings) {
        if (token.endsWith(ending) && token.length - ending.length >= 4) {
            return token.dropLast(ending.length)
        }
    }
    return token
}

internal fun sameStem(a: String, b: String): Boolean {
    val left = stemToken(a)
    val right = stemToken(b)
    if (left == right) return true
    val shorter = if (left.length < right.length) left else right
    val longer = if (left.length < right.length) right else left
    if (shorter.length < 6) return false
    return longer.startsWith(shorter) && longer.length - shorter.length <= 2
}

internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val prev = IntArray(b.length + 1) { it }
    val cur = IntArray(b.length + 1)
    for (i in a.indices) {
        cur[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
        }
        for (j in prev.indices) prev[j] = cur[j]
    }
    return prev[b.length]
}
