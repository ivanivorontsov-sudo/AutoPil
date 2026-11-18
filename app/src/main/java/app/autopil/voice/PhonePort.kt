package app.autopil.voice

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.autopil.core.agent.DevicePort
import app.autopil.core.model.Bounds
import app.autopil.core.model.ContactMatch
import app.autopil.core.model.Direction
import app.autopil.core.model.GlobalAction
import app.autopil.core.model.OpenAppResult
import app.autopil.core.model.ScreenSnapshot
import app.autopil.core.model.UiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PhonePort(
    private val service: AutopilAccessibilityService,
    private val speaker: Speaker,
    private val hud: OverlayHud,
    private val apps: AppIndex,
    private val contacts: ContactDirectory,
    private val onSpoken: (String) -> Unit,
    private val onLog: (String) -> Unit,
) : DevicePort {
    private val screen = ScreenController(service)
    var speech: SpeechLoop? = null

    override suspend fun capture(): ScreenSnapshot = screen.capture()

    override suspend fun highlight(bounds: Bounds?) {
        hud.highlight(bounds)
    }

    override suspend fun click(target: UiNode): Boolean = screen.click(target)

    override suspend fun typeText(target: UiNode?, text: String): Boolean = screen.typeText(target, text)

    override suspend fun scroll(direction: Direction): Boolean = screen.scroll(direction)

    override suspend fun global(action: GlobalAction): Boolean = screen.global(action)

    override suspend fun openApp(name: String, packages: List<String>, terms: List<String>): OpenAppResult {
        return apps.open(name, packages, terms)
    }

    override suspend fun speak(text: String) {
        onSpoken(text)
        hud.setStatus(text)
        if (text.contains("подтверди")) vibrate()
        speech?.duck()
        try {
            speaker.speak(text)
        } finally {
            speech?.unduck()
        }
    }

    override suspend fun log(line: String) {
        onLog(line)
    }

    override suspend fun resolveContact(spokenName: String): ContactMatch {
        return withContext(Dispatchers.IO) { contacts.resolve(spokenName) }
    }

    override suspend fun showSmsDraft(phone: String, displayName: String, body: String): Boolean {
        val uri = Uri.fromParts("smsto", phone.filter { it.isDigit() || it == '+' }, null)
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent)
    }

    override suspend fun showDialer(phone: String, displayName: String): Boolean {
        val uri = Uri.fromParts("tel", phone.filter { it.isDigit() || it == '+' }, null)
        val intent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent)
    }

    private fun launch(intent: Intent): Boolean {
        return try {
            service.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            service.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            service.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
