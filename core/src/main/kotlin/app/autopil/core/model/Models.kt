package app.autopil.core.model

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun area(): Long = width.toLong() * height.toLong()
}

data class UiNode(
    val id: String,
    val text: String = "",
    val description: String = "",
    val hint: String = "",
    val className: String = "",
    val viewId: String = "",
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val editable: Boolean = false,
    val password: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val visible: Boolean = true,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
    val children: List<UiNode> = emptyList(),
) {
    fun label(): String = sequenceOf(text, description, hint).firstOrNull { it.isNotBlank() }.orEmpty()
}

data class ScreenSnapshot(
    val roots: List<UiNode>,
    val width: Int,
    val height: Int,
)

enum class FindRole { Any, Clickable, Editable, Scrollable }

sealed class FindResult {
    data class Match(val node: UiNode, val score: Int) : FindResult()
    data class Ambiguous(val options: List<Match>) : FindResult()
    data object None : FindResult()
}

enum class Direction { Forward, Backward, Left, Right }

enum class GlobalAction {
    Back,
    Home,
    Recents,
    Notifications,
    QuickSettings,
    LockScreen,
    Screenshot,
}

data class Contact(val displayName: String, val phone: String)

sealed class ContactMatch {
    data class Found(val contact: Contact) : ContactMatch()
    data class Ambiguous(val contacts: List<Contact>) : ContactMatch()
    data object NotFound : ContactMatch()
    data object NoPermission : ContactMatch()
}

sealed class OpenAppResult {
    data class Opened(val label: String, val packageName: String) : OpenAppResult()
    data object NotFound : OpenAppResult()
}

fun ScreenSnapshot.flatten(): List<UiNode> = roots.flatMap { it.flatten() }

fun UiNode.flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }

/** Password text never leaves the device snapshot, even if a field is revealed. */
fun UiNode.withoutSecrets(): UiNode {
    if (!password) return copy(children = children.map { it.withoutSecrets() })
    return copy(text = "", children = children.map { it.redactSecret() })
}

private fun UiNode.redactSecret(): UiNode = copy(
    text = "",
    description = "",
    children = children.map { it.redactSecret() },
)
