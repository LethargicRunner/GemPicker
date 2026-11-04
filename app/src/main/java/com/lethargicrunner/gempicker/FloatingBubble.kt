package com.lethargicrunner.gempicker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

/**
 * Bulle flottante draggable (56dp), toujours au-dessus du jeu.
 * Tap = ON/OFF. Appui LONG = mode debug (aperçu des masques de détection).
 * Glisser = déplacer. Anneau vert = actif.
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
        var moved = false
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX.toInt(); downY = ev.rawY.toInt()
                    startX = params.x; startY = params.y; moved = false
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
                MotionEvent.ACTION_UP -> { v.performClick() }
                else -> false
            }
        }

        view.setOnClickListener {
            if (moved) return@setOnClickListener
            if (ScreenCaptureService.running) {
                ScreenCaptureService.running = false
                context.stopService(Intent(context, ScreenCaptureService::class.java))
                setOn(view, false)
            } else if (MediaProjectionHolder.hasSession()) {
                ScreenCaptureService.running = true
                context.startForegroundService(Intent(context, ScreenCaptureService::class.java))
                setOn(view, true)
            }
        }

        // Appui long = bascule du mode debug (calibration des bornes HSV)
        view.isLongClickable = true
        view.setOnLongClickListener {
            if (moved) return@setOnLongClickListener true
            ScreenCaptureService.debugEnabled = !ScreenCaptureService.debugEnabled
            if (debugOverlay == null) debugOverlay = DebugOverlay(context)
            if (ScreenCaptureService.debugEnabled) {
                debugOverlay?.show()
                Toast.makeText(context, "Debug ON : bleu = gemme, vert = cercle", Toast.LENGTH_SHORT).show()
            } else {
                debugOverlay?.hide()
                Toast.makeText(context, "Debug OFF", Toast.LENGTH_SHORT).show()
            }
            true
        }

        windowManager.addView(view, params)
        return view
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
