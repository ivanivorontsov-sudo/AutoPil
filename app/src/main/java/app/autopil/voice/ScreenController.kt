package app.autopil.voice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import app.autopil.core.match.TextNormalizer
import app.autopil.core.model.Bounds
import app.autopil.core.model.Direction
import app.autopil.core.model.GlobalAction
import app.autopil.core.model.UiNode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class ScreenController(private val service: AccessibilityService) {
    fun capture() = NodeSnapshots.capture(service)

    suspend fun click(target: UiNode): Boolean {
        if (performClick(target)) return true
        return gestureClick(target.bounds)
    }

    suspend fun typeText(target: UiNode?, text: String): Boolean {
        if (target != null && performSetText(target, text)) return true
        if (performSetTextOnFocused(text)) return true
        return paste(text)
    }

    suspend fun scroll(direction: Direction): Boolean {
        val action = when (direction) {
            Direction.Forward, Direction.Right -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            Direction.Backward, Direction.Left -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (performScroll(action)) return true
        return gestureScroll(direction)
    }

    fun global(action: GlobalAction): Boolean {
        val id = when (action) {
            GlobalAction.Back -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.Home -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.Recents -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalAction.Notifications -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QuickSettings -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            GlobalAction.LockScreen -> {
                if (Build.VERSION.SDK_INT < 28) return false
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            }
            GlobalAction.Screenshot -> {
                if (Build.VERSION.SDK_INT < 31) return false
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            }
        }
        return service.performGlobalAction(id)
    }

    private fun performClick(target: UiNode): Boolean {
        val roots = foreignRoots(service)
        try {
            for (root in roots) {
                if (clickWalk(root, target) == Hit.Acted) return true
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return false
    }

    private fun clickWalk(node: AccessibilityNodeInfo, target: UiNode): Hit {
        if (same(node, target)) {
            return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) Hit.Acted else Hit.Matched
        }
        var matched = Hit.Miss
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val hit = clickWalk(child, target)
            child.recycle()
            if (hit == Hit.Acted) return Hit.Acted
            if (hit == Hit.Matched) matched = Hit.Matched
        }
        return matched
    }

    private fun performSetText(target: UiNode, text: String): Boolean {
        val roots = foreignRoots(service)
        try {
            for (root in roots) {
                if (typeWalk(root, target, text)) return true
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return false
    }

    private fun typeWalk(node: AccessibilityNodeInfo, target: UiNode, text: String): Boolean {
        if (same(node, target)) return setText(node, text)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val typed = typeWalk(child, target, text)
            child.recycle()
            if (typed) return true
        }
        return false
    }

    private fun performSetTextOnFocused(text: String): Boolean {
        val roots = foreignRoots(service)
        try {
            for (root in roots) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    val typed = setText(focused, text)
                    if (focused !== root) focused.recycle()
                    if (typed) return true
                }
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return false
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK) && pasteInto(node, text)
    }

    private fun paste(text: String): Boolean {
        val roots = foreignRoots(service)
        try {
            for (root in roots) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                val pasted = pasteInto(focused, text)
                if (focused !== root) focused.recycle()
                if (pasted) return true
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return false
    }

    private fun pasteInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
        val previous = clipboard.primaryClip
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } finally {
            try {
                if (previous != null) clipboard.setPrimaryClip(previous)
                else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            } catch (_: Exception) {
                // The previous clipboard contents are best-effort.
            }
        }
    }

    private fun performScroll(action: Int): Boolean {
        val roots = foreignRoots(service)
        try {
            for (root in roots) {
                if (scrollWalk(root, action)) return true
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return false
    }

    private fun scrollWalk(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable && node.performAction(action)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val scrolled = scrollWalk(child, action)
            child.recycle()
            if (scrolled) return true
        }
        return false
    }

    private suspend fun gestureClick(bounds: Bounds): Boolean {
        if (bounds.width < 2 || bounds.height < 2) return false
        val x = bounds.centerX.toFloat()
        val y = bounds.centerY.toFloat()
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        return gesture(path, 60)
    }

    private suspend fun gestureScroll(direction: Direction): Boolean {
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val path = Path()
        when (direction) {
            Direction.Forward -> {
                path.moveTo(width / 2f, height * 0.72f)
                path.lineTo(width / 2f, height * 0.32f)
            }
            Direction.Backward -> {
                path.moveTo(width / 2f, height * 0.32f)
                path.lineTo(width / 2f, height * 0.72f)
            }
            Direction.Left -> {
                path.moveTo(width * 0.75f, height / 2f)
                path.lineTo(width * 0.2f, height / 2f)
            }
            Direction.Right -> {
                path.moveTo(width * 0.2f, height / 2f)
                path.lineTo(width * 0.75f, height / 2f)
            }
        }
        return gesture(path, 280)
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
        )
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    private fun same(node: AccessibilityNodeInfo, target: UiNode): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.left != target.bounds.left || rect.top != target.bounds.top ||
            rect.right != target.bounds.right || rect.bottom != target.bounds.bottom
        ) {
            return false
        }
        return TextNormalizer.normalize(liveLabel(node)) == TextNormalizer.normalize(target.label())
    }

    private fun liveLabel(node: AccessibilityNodeInfo): String {
        val description = node.contentDescription?.toString().orEmpty()
        val hint = node.hintText?.toString().orEmpty()
        if (node.isPassword) return description.ifBlank { hint }
        val text = node.text?.toString().orEmpty()
        return text.ifBlank { description.ifBlank { hint } }
    }

    private enum class Hit { Miss, Matched, Acted }
}
