package app.autopil.voice

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

internal fun foreignRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
    val ours = service.packageName
    val all = service.windows?.mapNotNull { it.root }.orEmpty()
        .ifEmpty { listOfNotNull(service.rootInActiveWindow) }
    val foreign = all.filter { it.packageName?.toString() != ours }
    if (foreign.isEmpty()) return all
    all.filter { it.packageName?.toString() == ours }.forEach { it.recycle() }
    return foreign
}
