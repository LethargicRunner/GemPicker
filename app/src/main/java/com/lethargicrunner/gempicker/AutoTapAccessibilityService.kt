package com.lethargicrunner.gempicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class AutoTapAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: AutoTapAccessibilityService? = null

        /** True si le service d'accessibilité est connecté. */
        val connected: Boolean get() = instance != null

        @Volatile private var lastWarn = 0L
        @Volatile private var warnCount = 0

        fun requestTap(x: Float, y: Float) {
            val svc = instance
            if (svc == null) {
                // Diagnostique clé : sans service connecté, AUCUN tap ne part.
                val now = SystemClock.uptimeMillis()
                if (now - lastWarn > 5000) {
                    lastWarn = now
                    warnCount++
                    GemLog.e("Tap", "SERVICE D'ACCESSIBILITÉ NON CONNECTÉ — " +
                        "activez GemPicker dans Paramètres > Accessibilité (n°$warnCount)")
                }
                return
            }
            svc.dispatchTapInternal(x, y)
        }
    }

    override fun onServiceConnected() {
        instance = this
        GemLog.i("Tap", "Service d'accessibilité CONNECTÉ — les taps partiront vraiment")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* on ne lit pas l'UI du jeu */ }
    override fun onInterrupt() {}

    @Volatile private var lastResultLog = 0L

    private fun dispatchTapInternal(x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y); lineTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    // Tap réellement transmis au système : log 1 fois / 10 s max
                    val now = SystemClock.uptimeMillis()
                    if (now - lastResultLog > 10000) {
                        lastResultLog = now
                        GemLog.i("Tap", "Geste transmis au système (OK, les taps aboutissent)")
                    }
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    GemLog.e("Tap", "Geste ANNULÉ par le système en ($x, $y)")
                }
            }, null)
        } catch (e: Exception) {
            GemLog.e("Tap", "dispatchGesture a échoué en ($x, $y)", e)
        }
    }

    override fun onDestroy() {
        GemLog.w("Tap", "Service d'accessibilité DÉCONNECTÉ")
        instance = null
        super.onDestroy()
    }
}
