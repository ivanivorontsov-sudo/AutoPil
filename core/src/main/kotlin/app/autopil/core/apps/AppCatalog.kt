package app.autopil.core.apps

import app.autopil.core.match.TextNormalizer

object AppCatalog {
    val taxiPackages: List<String> = listOf(
        "ru.yandex.taxi",
        "com.ubercab",
        "com.taxsee.taxsee",
        "sinet.startup.inDriver",
        "ru.citymobil.app",
    )

    fun searchTerms(spokenName: String): List<String> {
        val key = TextNormalizer.normalize(spokenName)
        val alias = ALIASES[key]
            ?: ALIASES.entries.firstOrNull { (name, _) -> key.split(" ").any { it == name } }?.value
            ?: emptyList()
        return (listOf(spokenName) + alias).distinct()
    }

    fun bestLabel(labels: List<String>, terms: List<String>): String? {
        var best: String? = null
        var bestScore = 0
        val wanted = terms.map { TextNormalizer.normalize(it) }.filter { it.length >= 3 }
        for (label in labels) {
            val normalized = TextNormalizer.normalize(label)
            val score = wanted.maxOfOrNull { scoreLabel(normalized, it) } ?: 0
            if (score > bestScore) {
                bestScore = score
                best = label
            }
        }
        return best?.takeIf { bestScore >= 88 }
    }

    fun scoreLabel(label: String, term: String): Int {
        if (term.length < 3 || label.isBlank()) return 0
        if (label == term) return 100
        val words = label.split(" ")
        if (words.any { it == term }) return 96
        val startsWord = words.any { word ->
            word.startsWith(term) && (word.length == term.length || word.getOrNull(term.length)?.isLetter() == false)
        }
        if (startsWord) return 90
        if (label.startsWith(term) && (label.length == term.length || label[term.length] == ' ')) return 88
        return 0
    }

    private val ALIASES = mapOf(
        "сообщения" to listOf("сообщения", "messages", "sms"),
        "смс" to listOf("сообщения", "messages", "sms"),
        "телефон" to listOf("телефон", "phone", "звонки"),
        "звонки" to listOf("телефон", "phone", "звонки"),
        "настройки" to listOf("настройки", "settings"),
        "камера" to listOf("камера", "camera"),
        "браузер" to listOf("браузер", "chrome", "browser"),
        "хром" to listOf("chrome", "хром"),
        "карты" to listOf("карты", "maps"),
        "телеграм" to listOf("telegram", "телеграм"),
        "ватсап" to listOf("whatsapp", "ватсап"),
        "вотсап" to listOf("whatsapp", "вотсап"),
        "почта" to listOf("почта", "gmail", "mail"),
        "такси" to listOf("такси", "яндекс go", "yandex go", "uber", "убер", "ситимобил", "maxim"),
    )
}
