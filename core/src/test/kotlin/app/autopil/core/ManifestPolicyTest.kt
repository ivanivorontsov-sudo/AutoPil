package app.autopil.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestPolicyTest {
    @Test
    fun voiceControlDoesNotAskForDeviceAdminOrNetwork() {
        val manifest = File("../app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
        assertFalse(manifest.contains("DEVICE_ADMIN"))
        assertFalse(manifest.contains("DeviceAdminReceiver"))
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("CALL_PHONE"))
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
    }
}
