package app.autopil.voice

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.app.admin.DevicePolicyManager

/**
 * Optional device admin. The system shows its own consent screen before this
 * becomes active. The only policy is locking the screen.
 */
class AutopilDeviceAdmin : DeviceAdminReceiver()

internal object DeviceAdmin {
    fun component(context: Context): ComponentName = ComponentName(context, AutopilDeviceAdmin::class.java)

    fun isActive(context: Context): Boolean {
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return manager.isAdminActive(component(context))
    }

    fun lockNow(context: Context): Boolean {
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!manager.isAdminActive(component(context))) return false
        manager.lockNow()
        return true
    }
}
