package app.autopil.core.apps

import app.autopil.core.match.TextNormalizer

object NameForms {
    fun variants(spoken: String): List<String> {
        val base = TextNormalizer.normalize(spoken)
        val stripped = base
            .removePrefix("моему ")
            .removePrefix("моей ")
            .removePrefix("моего ")
            .removePrefix("контакт ")
            .trim()
        val out = linkedSetOf(base, stripped)
        inflect(stripped.substringAfterLast(" "), out)
        if (!stripped.contains(" ")) inflect(stripped, out)
        return out.map { it.trim() }.filter { it.length >= 2 }.distinct()
    }

    fun score(spoken: String, contactName: String): Int {
        val contact = TextNormalizer.normalize(contactName)
        if (contact.isBlank()) return 0
        var best = 0
        for (variant in variants(spoken)) {
            val points = when {
                variant == contact -> 100
                contact.startsWith(variant) || variant.startsWith(contact) -> 80
                variant.length >= 4 && (contact.contains(variant) || variant.contains(contact)) -> 70
                else -> 0
            }
            if (points > best) best = points
        }
        return best
    }

    private fun inflect(token: String, out: MutableSet<String>) {
        if (token.length < 3) return
        when {
            token.endsWith("у") || token.endsWith("ю") -> out += token.dropLast(1)
            token.endsWith("е") -> {
                out += token.dropLast(1) + "а"
                out += token.dropLast(1) + "я"
                out += token.dropLast(1)
            }
            token.endsWith("и") -> {
                out += token.dropLast(1) + "а"
                out += token.dropLast(1) + "я"
            }
            token.endsWith("ой") && token.length > 4 -> out += token.dropLast(2) + "а"
        }
    }
}
