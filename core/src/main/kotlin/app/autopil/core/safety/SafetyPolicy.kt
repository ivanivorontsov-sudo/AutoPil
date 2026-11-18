package app.autopil.core.safety

import app.autopil.core.match.TextNormalizer
import app.autopil.core.model.UiNode

/**
 * Final actions stay behind a second spoken word, «подтверди».
 * Naming the button in the first command is not enough: ordering, paying,
 * sending, deleting and resetting are irreversible.
 */
class SafetyPolicy {
    fun isSensitive(node: UiNode): Boolean {
        return isSensitiveLabel(node.text) ||
            isSensitiveLabel(node.description) ||
            isSensitiveLabel(node.hint)
    }

    fun isSensitiveLabel(raw: String): Boolean {
        val label = TextNormalizer.normalize(raw)
        if (label.isBlank()) return false
        if (label.length > MAX_LABEL) return LONG_PHRASES.any { label.contains(it) }
        if (label in EXACT) return true
        return PREFIX.any { label == it || label.startsWith("$it ") }
    }

    private companion object {
        const val MAX_LABEL = 42
        val EXACT = setOf(
            "оплатить",
            "оплата",
            "pay",
            "pay now",
            "checkout",
            "купить",
            "buy",
            "buy now",
            "заказать",
            "оформить",
            "отправить",
            "send",
            "удалить",
            "delete",
            "стереть",
            "перевести",
            "перевод",
            "поехали",
            "вызвать",
        )
        val PREFIX = listOf(
            "оплатить",
            "оплата",
            "pay",
            "checkout",
            "купить",
            "buy",
            "заказать",
            "оформить заказ",
            "подтвердить заказ",
            "подтвердить оплату",
            "отправить",
            "send",
            "удалить",
            "delete",
            "стереть",
            "перевести",
            "подтвердить перевод",
            "сбросить",
            "вызвать",
        )
        val LONG_PHRASES = listOf(
            "подтвердить перевод",
            "отправить деньги",
            "оплатить заказ",
            "factory reset",
            "сбросить настройки",
            "подтвердить заказ",
        )
    }
}
