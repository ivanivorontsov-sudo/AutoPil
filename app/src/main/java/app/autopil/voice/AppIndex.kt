package app.autopil.voice

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import app.autopil.core.apps.AppCatalog
import app.autopil.core.model.OpenAppResult

internal class AppIndex(private val service: AccessibilityService) {
    fun open(name: String, packages: List<String>, terms: List<String>): OpenAppResult {
        val manager = service.packageManager
        for (packageName in packages) {
            val launch = manager.getLaunchIntentForPackage(packageName) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                service.startActivity(launch)
                val label = manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
                return OpenAppResult.Opened(label, packageName)
            } catch (_: Exception) {
                continue
            }
        }
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = manager.queryIntentActivities(probe, 0)
        val labels = activities.map { it.loadLabel(manager).toString() }
        val best = AppCatalog.bestLabel(labels, terms.ifEmpty { listOf(name) }) ?: return OpenAppResult.NotFound
        val chosen = activities.firstOrNull { it.loadLabel(manager).toString() == best } ?: return OpenAppResult.NotFound
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(chosen.activityInfo.packageName, chosen.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            service.startActivity(launch)
            OpenAppResult.Opened(best, chosen.activityInfo.packageName)
        } catch (_: Exception) {
            OpenAppResult.NotFound
        }
    }
}
