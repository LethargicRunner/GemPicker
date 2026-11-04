package com.lethargicrunner.gempicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class AutoTapAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: AutoTapAccessibilityService? = null

        fun requestTap(x: Float, y: Float) {
            instance?.dispatchTapInternal(x, y)
        }
    }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* on ne lit pas l'UI du jeu */ }
    override fun onInterrupt() {}

    private fun dispatchTapInternal(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
}
