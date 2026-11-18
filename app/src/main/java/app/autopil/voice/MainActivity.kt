package app.autopil.voice

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { Prefs(this) }
    private var unsubscribe: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        render()
        if (granted) {
            AutopilHub.controls?.refreshListening()
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        render()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wire()
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        unsubscribe?.invoke()
        super.onDestroy()
    }

    private fun wire() {
        findViewById<MaterialButton>(R.id.listenButton).setOnClickListener {
            AutopilHub.controls?.listenOnce() ?: showOff()
        }
        findViewById<MaterialButton>(R.id.micButton).setOnClickListener {
            if (!granted(Manifest.permission.RECORD_AUDIO)) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<MaterialButton>(R.id.accessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.contactsButton).setOnClickListener {
            if (!granted(Manifest.permission.READ_CONTACTS)) {
                contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            }
        }
        findViewById<MaterialButton>(R.id.batteryButton).setOnClickListener { openBatterySettings() }
        findViewById<MaterialButton>(R.id.adminButton).setOnClickListener { requestDeviceAdmin() }
        findViewById<MaterialButton>(R.id.runCommandButton).setOnClickListener { runTypedCommand() }
        findViewById<TextInputEditText>(R.id.commandInput).setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runTypedCommand()
                true
            } else {
                false
            }
        }

        val before = findViewById<Slider>(R.id.beforeSlider)
        val after = findViewById<Slider>(R.id.afterSlider)
        configure(before, prefs.beforeMs) { prefs.beforeMs = it }
        configure(after, prefs.afterMs) { prefs.afterMs = it }
        showPauses()

        val continuous = findViewById<MaterialSwitch>(R.id.continuousSwitch)
        continuous.isChecked = prefs.continuous
        continuous.setOnCheckedChangeListener { _, checked ->
            prefs.continuous = checked
            AutopilHub.controls?.refreshListening()
        }
        val offline = findViewById<MaterialSwitch>(R.id.offlineSwitch)
        offline.isChecked = prefs.preferOffline
        offline.setOnCheckedChangeListener { _, checked -> prefs.preferOffline = checked }

        unsubscribe = AutopilHub.listen { render() }
    }

    private fun configure(slider: Slider, initial: Long, save: (Long) -> Unit) {
        slider.valueFrom = 0f
        slider.valueTo = 2000f
        slider.stepSize = 50f
        slider.value = initial.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            save(value.toLong())
            showPauses()
        }
    }

    private fun runTypedCommand() {
        val field = findViewById<TextInputEditText>(R.id.commandInput)
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val controls = AutopilHub.controls
        if (controls == null) {
            showOff()
            return
        }
        controls.submit(text)
        field.text?.clear()
    }

    private fun render() {
        val state = AutopilHub.state
        val serviceOn = state.serviceOn || accessibilityEnabled()
        findViewById<TextView>(R.id.statusText).text = when {
            state.prompt.isNotBlank() -> getString(R.string.status_waiting)
            state.status.isNotBlank() && serviceOn -> state.status
            serviceOn && !granted(Manifest.permission.RECORD_AUDIO) -> getString(R.string.status_need_mic)
            serviceOn -> getString(R.string.status_on)
            else -> getString(R.string.status_off)
        }
        findViewById<TextView>(R.id.heardText).text = state.heard.ifBlank { getString(R.string.heard_empty) }
        val confirm = findViewById<android.view.View>(R.id.confirmCard)
        val confirmText = findViewById<TextView>(R.id.confirmText)
        if (state.prompt.isBlank()) {
            confirm.visibility = android.view.View.GONE
        } else {
            confirm.visibility = android.view.View.VISIBLE
            confirmText.text = state.prompt
        }
        val dot = findViewById<android.view.View>(R.id.statusDot)
        val dotColor = when {
            state.prompt.isNotBlank() -> R.color.wait
            serviceOn && granted(Manifest.permission.RECORD_AUDIO) -> R.color.safe
            else -> R.color.muted
        }
        dot.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, dotColor))
        findViewById<MaterialButton>(R.id.micButton).setText(
            if (granted(Manifest.permission.RECORD_AUDIO)) R.string.mic_ready else R.string.mic_allow,
        )
        findViewById<MaterialButton>(R.id.accessButton).setText(
            if (accessibilityEnabled()) R.string.access_ready else R.string.access_open,
        )
        findViewById<MaterialButton>(R.id.contactsButton).setText(
            if (granted(Manifest.permission.READ_CONTACTS)) R.string.contacts_ready else R.string.contacts_allow,
        )
        findViewById<MaterialButton>(R.id.adminButton).setText(
            if (DeviceAdmin.isActive(this)) R.string.admin_ready else R.string.admin_allow,
        )
        findViewById<TextView>(R.id.logText).text = if (state.log.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            state.log.joinToString("\n")
        }
        showPauses()
    }

    private fun showPauses() {
        findViewById<TextView>(R.id.beforeLabel).text = getString(R.string.pause_before) + " · " + seconds(prefs.beforeMs)
        findViewById<TextView>(R.id.afterLabel).text = getString(R.string.pause_after) + " · " + seconds(prefs.afterMs)
    }

    private fun showOff() {
        findViewById<TextView>(R.id.statusText).setText(R.string.status_off)
    }

    private fun requestDeviceAdmin() {
        if (DeviceAdmin.isActive(this)) return
        val request = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceAdmin.component(this@MainActivity))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
        }
        startActivity(request)
    }

    private fun openBatterySettings() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(request)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val component = ComponentName(this, AutopilAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }

    private fun seconds(millis: Long): String = String.format(Locale("ru"), "%.1f с", millis / 1000.0)
}
