package app.autopil.core.match

import app.autopil.core.model.ScreenSnapshot
import app.autopil.core.model.UiNode

object ScreenReader {
    fun script(screen: ScreenSnapshot, limit: Int = 18): String {
        val lines = mutableListOf<String>()
        fun walk(node: UiNode) {
            if (!node.visible || lines.size >= limit) return
            if (node.password) {
                if (lines.lastOrNull() != PASSWORD) lines += PASSWORD
                return
            }
            val own = node.text.trim().ifBlank {
                if (node.children.isEmpty()) node.description.trim() else ""
            }
            val echoedByChild = node.children.any { child ->
                child.text.trim() == own || child.description.trim() == own
            }
            if (own.isNotBlank() && !echoedByChild && lines.lastOrNull() != own) {
                lines += own
            }
            node.children.forEach { walk(it) }
        }
        screen.roots.forEach { walk(it) }
        if (lines.isEmpty()) return "На экране нет текста"
        val visible = lines.take(limit)
        val more = if (lines.size > limit) ". Дальше ещё элементы." else ""
        return visible.joinToString(". ") + more
    }

    private const val PASSWORD = "поле пароля"
}
