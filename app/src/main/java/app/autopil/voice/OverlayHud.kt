package app.autopil.voice

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.autopil.core.model.Bounds

internal class OverlayHud(
    private val service: AccessibilityService,
    private val onMic: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val themed = ContextThemeWrapper(service, R.style.Theme_AutoPil)
    private val hud = LayoutInflater.from(themed).inflate(R.layout.overlay_hud, null)
    private val hudText = hud.findViewById<TextView>(R.id.hudText)
    private val highlight = View(themed).apply { setBackgroundResource(R.drawable.bg_highlight) }
    private var hudAdded = false
    private var highlightAdded = false

    fun show() {
        if (hudAdded) return
        hud.setOnClickListener { onMic() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }
        windowManager.addView(hud, params)
        hudAdded = true
    }

    fun setStatus(text: String) {
        hudText.text = text
    }

    fun highlight(bounds: Bounds?) {
        if (bounds == null || bounds.width < 2 || bounds.height < 2) {
            if (highlightAdded) {
                windowManager.removeView(highlight)
                highlightAdded = false
            }
            return
        }
        val params = WindowManager.LayoutParams(
            bounds.width.coerceAtLeast(1),
            bounds.height.coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }
        if (highlightAdded) {
            windowManager.updateViewLayout(highlight, params)
        } else {
            windowManager.addView(highlight, params)
            highlightAdded = true
        }
    }

    fun hide() {
        if (hudAdded) {
            windowManager.removeView(hud)
            hudAdded = false
        }
        if (highlightAdded) {
            windowManager.removeView(highlight)
            highlightAdded = false
        }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}
