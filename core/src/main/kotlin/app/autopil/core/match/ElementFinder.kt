package app.autopil.core.match

import app.autopil.core.model.FindResult
import app.autopil.core.model.FindRole
import app.autopil.core.model.ScreenSnapshot
import app.autopil.core.model.UiNode

/**
 * Picks the control a person meant. Text may sit on a child of the clickable row,
 * speech may differ by a Russian ending, and a full-screen container is never a button.
 */
class ElementFinder {
    fun find(screen: ScreenSnapshot, query: String, role: FindRole): FindResult {
        val wanted = TextNormalizer.normalize(query)
        if (wanted.isBlank()) return FindResult.None
        val flat = flatten(screen.roots)
        val byId = flat.associateBy { it.node.id }
        val screenArea = screen.width.toLong() * screen.height.toLong()
        val scored = flat.mapNotNull { score(it, wanted, role, screen, screenArea, byId) }
        if (scored.isEmpty()) return FindResult.None
        val sorted = scored.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { it.node.bounds.top }
                .thenBy { it.node.bounds.left },
        )
        val best = sorted.first()
        if (best.score < MIN_SCORE) return FindResult.None
        val rivals = sorted.drop(1)
            .filter { best.score - it.score <= AMBIGUOUS_GAP && it.score >= MIN_SCORE && it.node.id != best.node.id }
            .distinctBy { it.node.id }
            .take(3)
        if (rivals.isNotEmpty() && !uniqueExact(best.node, wanted, rivals)) {
            return FindResult.Ambiguous(listOf(best.asMatch()) + rivals.map { it.asMatch() })
        }
        return FindResult.Match(best.node, best.score)
    }

    private fun uniqueExact(best: UiNode, query: String, rivals: List<Scored>): Boolean {
        if (!isExact(best, query)) return false
        return rivals.none { isExact(it.node, query) }
    }

    private fun isExact(node: UiNode, query: String): Boolean {
        return listOf(node.text, node.description, node.hint).any { TextNormalizer.normalize(it) == query }
    }

    private fun score(
        item: Flat,
        query: String,
        role: FindRole,
        screen: ScreenSnapshot,
        screenArea: Long,
        byId: Map<String, Flat>,
    ): Scored? {
        val node = item.node
        if (!node.enabled || !node.visible) return null
        if (node.bounds.width < 8 || node.bounds.height < 8) return null
        if (screenArea > 0 && node.bounds.area() > screenArea * 0.85) return null
        if (node.bounds.centerX !in 0..screen.width || node.bounds.centerY !in 0..screen.height) return null
        if (node.password && role != FindRole.Editable && role != FindRole.Any) return null

        var points = labelScore(node, query)
        if (points <= 0) return null

        val target = if (role == FindRole.Clickable || role == FindRole.Any) {
            promote(node, byId, screenArea)
        } else {
            node
        }

        when (role) {
            FindRole.Clickable -> {
                if (node.editable || target.editable) return null
                if (target.clickable) points += 12
                if (target.className.contains("Button", ignoreCase = true)) points += 6
            }
            FindRole.Editable -> {
                if (target.editable || node.editable) {
                    points += 12
                } else {
                    points -= 20
                }
            }
            FindRole.Scrollable -> if (!node.scrollable && !target.scrollable) return null
            FindRole.Any -> if (target.clickable) points += 6
        }
        if (points < MIN_SCORE) return null
        return Scored(target, points)
    }

    private fun labelScore(node: UiNode, query: String): Int {
        var best = 0
        for (raw in listOf(node.text, node.description, node.hint)) {
            val field = TextNormalizer.normalize(raw)
            if (field.isBlank()) continue
            val direct = when {
                field == query -> 100
                field.startsWith(query) && query.length >= 2 -> 88
                query.startsWith(field) && field.length >= 3 && field.length.toFloat() / query.length >= 0.6f -> 84
                field.contains(query) && query.length >= 3 -> 80
                query.contains(field) && field.length >= 5 && field.length.toFloat() / query.length >= 0.6f -> 76
                else -> 0
            }
            val fuzzy = fuzzyScore(field, query)
            val tokens = tokenScore(field, query)
            best = maxOf(best, direct, fuzzy, tokens)
        }
        return best
    }

    private fun fuzzyScore(field: String, query: String): Int {
        if (field.length < 4 || query.length < 4 || field.length > 32 || query.length > 32) return 0
        val distance = levenshtein(field, query)
        val maxLen = maxOf(field.length, query.length)
        if (distance <= 2 && distance.toFloat() / maxLen <= 0.34f) return 82 - distance * 8
        return 0
    }

    private fun tokenScore(field: String, query: String): Int {
        val fieldTokens = field.split(" ").filter { it.length > 2 }
        val queryTokens = query.split(" ").filter { it.length > 2 }
        if (fieldTokens.isEmpty() || queryTokens.isEmpty()) return 0
        var hits = 0
        for (token in queryTokens) {
            if (fieldTokens.any { it == token || sameStem(it, token) }) hits++
        }
        if (hits == 0) return 0
        return (70f * hits / queryTokens.size).toInt()
    }

    private fun promote(node: UiNode, byId: Map<String, Flat>, screenArea: Long): UiNode {
        if (node.clickable) return node
        var current = node.id
        var guard = 0
        while (guard++ < 12) {
            val parentId = byId[current]?.parentId ?: return node
            val parent = byId[parentId]?.node ?: return node
            if (screenArea > 0 && parent.bounds.area() > screenArea * 0.85) return node
            if (parent.clickable && parent.enabled && !parent.editable) return parent
            current = parent.id
        }
        return node
    }

    private fun flatten(nodes: List<UiNode>, parentId: String? = null, out: MutableList<Flat> = mutableListOf()): List<Flat> {
        for (node in nodes) {
            out += Flat(node, parentId)
            flatten(node.children, node.id, out)
        }
        return out
    }

    private data class Flat(val node: UiNode, val parentId: String?)
    private data class Scored(val node: UiNode, val score: Int) {
        fun asMatch() = FindResult.Match(node, score)
    }

    private companion object {
        const val MIN_SCORE = 64
        const val AMBIGUOUS_GAP = 8
    }
}
