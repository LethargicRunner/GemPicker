package com.lethargicrunner.gempicker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

/**
 * Bulle flottante draggable (56dp), toujours au-dessus du jeu.
 * Tap court = ON/OFF. Appui LONG (> 500 ms) = mode debug.
 * Glisser = déplacer. Anneau vert = actif.
 * FLAG_NOT_TOUCHABLE sur l'aperçu debug ; la bulle, elle, DOIT rester
 * tactile (c'est son unique raison d'être), mais elle ne couvre que 56dp.
 */
class FloatingBubble(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var bubble: View? = null
    private var debugOverlay: DebugOverlay? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble(): View {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
        val size = dp(56)

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(150) }

        val view = ImageView(context).apply {
            setImageResource(android.R.drawable.star_big_on)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        var downX = 0; var downY = 0; var startX = 0; var startY = 0
        var downTime = 0L
        var moved = false
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX.toInt(); downY = ev.rawY.toInt()
                    startX = params.x; startY = params.y; moved = false
                    downTime = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX.toInt() - downX
                    val dy = ev.rawY.toInt() - downY
                    if (abs(dx) + abs(dy) > dp(8)) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    windowManager.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val pressDuration = SystemClock.uptimeMillis() - downTime
                    if (!moved) {
                        if (pressDuration >= 500L) toggleDebug()
                        else handleOnOff()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
        GemLog.i("Bubble", "Bulle affichée")
        return view
    }

    private fun handleOnOff() {
        val v = bubble ?: return
        if (ScreenCaptureService.running) {
            GemLog.i("Bubble", "Tap : ARRÊT de la capture")
            ScreenCaptureService.running = false
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            setOn(v, false)
        } else {
            if (!MediaProjectionHolder.hasSession()) {
                GemLog.w("Bubble", "Tap sans session de capture (popup non validée)")
                Toast.makeText(context,
                    "Pas de session de capture : relancez l'app GemPicker et validez la popup",
                    Toast.LENGTH_LONG).show()
                return
            }
            try {
                GemLog.i("Bubble", "Tap : DÉMARRAGE de la capture")
                ScreenCaptureService.running = true
                context.startForegroundService(Intent(context, ScreenCaptureService::class.java))
                setOn(v, true)
            } catch (e: Exception) {
                GemLog.e("Bubble", "Démarrage du service impossible", e)
                ScreenCaptureService.running = false
                setOn(v, false)
                Toast.makeText(context,
                    "Démarrage impossible : " + (e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleDebug() {
        val v = bubble ?: return
        ScreenCaptureService.debugEnabled = !ScreenCaptureService.debugEnabled
        GemLog.i("Bubble", "Appui long : debug = ${ScreenCaptureService.debugEnabled}")
        if (debugOverlay == null) debugOverlay = DebugOverlay(context)
        if (ScreenCaptureService.debugEnabled) {
            debugOverlay?.show()
            setOn(v, ScreenCaptureService.running)
            Toast.makeText(context, "Debug ON : bleu = cibles, vert = cercle", Toast.LENGTH_SHORT).show()
        } else {
            debugOverlay?.hide()
            Toast.makeText(context, "Debug OFF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setOn(view: View, on: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xCC00C853.toInt() else 0x99000000.toInt())
            if (on) setStroke(4, Color.WHITE)
        }
    }

    fun show() { if (bubble == null && Settings.canDrawOverlays(context)) bubble = buildBubble() }
    fun hide() { bubble?.let { windowManager.removeView(it) }; bubble = null }
}
