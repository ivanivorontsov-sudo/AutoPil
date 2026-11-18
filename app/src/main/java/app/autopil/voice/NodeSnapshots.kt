package app.autopil.voice

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.autopil.core.model.Bounds
import app.autopil.core.model.ScreenSnapshot
import app.autopil.core.model.UiNode
import app.autopil.core.model.withoutSecrets

internal object NodeSnapshots {
    fun capture(service: AccessibilityService): ScreenSnapshot {
        val metrics = service.resources.displayMetrics
        val roots = foreignRoots(service)
        var counter = 0
        return try {
            ScreenSnapshot(
                roots = roots.map { mapNode(it) { "n${counter++}" }.withoutSecrets() },
                width = metrics.widthPixels,
                height = metrics.heightPixels,
            )
        } finally {
            roots.forEach { it.recycle() }
        }
    }

    private fun mapNode(node: AccessibilityNodeInfo, nextId: () -> String): UiNode {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val children = buildList {
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    add(mapNode(child, nextId))
                } finally {
                    child.recycle()
                }
            }
        }
        val password = node.isPassword
        return UiNode(
            id = nextId(),
            text = if (password) "" else node.text?.toString().orEmpty(),
            description = node.contentDescription?.toString().orEmpty(),
            hint = node.hintText?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            editable = node.isEditable,
            password = password,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            visible = node.isVisibleToUser || rect.width() > 0,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }
}
