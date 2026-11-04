package com.lethargicrunner.gempicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView

/**
 * Petit aperçu flottant (~140dp) des masques de détection :
 * pixels BLEUS = zone détectée comme gemme, VERTS = cercle d'action.
 * Sert à caler les bornes HSV en 2 minutes sur le vrai écran du jeu.
 * Activable par appui long sur la bulle.
 */
class DebugOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var imageView: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    private val updater = object : Runnable {
        override fun run() {
            if (imageView == null) return
            val bmp: Bitmap? = ScreenCaptureService.debugBitmap
            if (bmp != null && !bmp.isRecycled) imageView?.setImageBitmap(bmp)
            handler.postDelayed(this, 500)
        }
    }

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        if (imageView != null) return
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        params = WindowManager.LayoutParams(
            dp(140), dp(220),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(400) }

        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(0xAA000000.toInt())
        }
        windowManager.addView(imageView, params)
        handler.post(updater)
    }

    fun hide() {
        handler.removeCallbacks(updater)
        imageView?.let { windowManager.removeView(it) }
        imageView = null
    }
}
