package app.autopil.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestPolicyTest {
    @Test
    fun deviceAdminIsOptInAndCanOnlyLockTheScreen() {
        val manifest = File("../app/src/main/AndroidManifest.xml").readText()
        val policy = File("../app/src/main/res/xml/device_admin.xml").readText()
        assertTrue(manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(manifest.contains("BIND_DEVICE_ADMIN"))
        assertTrue(manifest.contains("DEVICE_ADMIN_ENABLED"))
        assertTrue(policy.contains("force-lock"))
        assertFalse(policy.contains("wipe-data"))
        assertFalse(policy.contains("reset-password"))
        assertFalse(policy.contains("expire-password"))
        assertFalse(policy.contains("disable-camera"))
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("CALL_PHONE"))
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
    }
}
