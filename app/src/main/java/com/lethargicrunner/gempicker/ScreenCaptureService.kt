package com.lethargicrunner.gempicker

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat

class ScreenCaptureService : Service() {

    data class Detection(val x: Float, val y: Float, val confidence: Double, val ts: Long)

    companion object {
        const val TAG = "GemPicker"
        const val ACTION_STOP = "com.lethargicrunner.gempicker.STOP"
        const val ANALYSIS_INTERVAL_MS = 100L
        const val NOTIFICATION_ID = 1

        @Volatile var lastDetection: Detection? = null
        @Volatile var running = false

        @Volatile var debugEnabled = false
        @Volatile var debugBitmap: Bitmap? = null

        @Volatile var lastError: String? = null

        // ── Cibles sélectionnables (options du jeu) ──
        @Volatile var targetBlue = true    // gemme bleue
        @Volatile var targetYellow = false // formes jaunes
        @Volatile var targetPink = false   // formes rose-mauve
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var detector: GemDetector? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            GemLog.w(TAG, "Projection arrêtée par le système")
            running = false
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val mgr = getSystemService(MediaProjectionManager::class.java)
            val data = MediaProjectionHolder.data
            if (data == null) {
                fail("Pas de session de capture — relancez l'app GemPicker")
                return START_NOT_STICKY
            }
            projection = mgr.getMediaProjection(MediaProjectionHolder.resultCode, data)
            startCapture()
            GemLog.i(TAG, "Service de capture démarré")
        } catch (e: Exception) {
            Log.e(TAG, "Démarrage du service échoué", e)
            fail("Erreur : " + (e.message ?: e.javaClass.simpleName), e)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        projection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        virtualDisplay = projection?.createVirtualDisplay(
            "gempicker_capture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        GemLog.i(TAG, "Écran virtuel ${width}x$height créé")

        detector = GemDetector(width, height)
        handlerThread = HandlerThread("analyzer").apply { start() }
        val handler = Handler(handlerThread!!.looper)

        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    try {
                        val detection = detector?.detect(image)
                        lastDetection = detection
                        if (detection != null) {
                            AutoTapAccessibilityService.requestTap(detection.x, detection.y)
                        }
                    } catch (e: Exception) {
                        GemLog.e(TAG, "Erreur d'analyse de frame", e)
                    } finally { image.close() }
                } else {
                    handler.postDelayed(this, ANALYSIS_INTERVAL_MS / 2)
                    return
                }
                handler.postDelayed(this, ANALYSIS_INTERVAL_MS / 2)
            }
        })
    }

    private fun fail(message: String, e: Exception? = null) {
        lastError = message
        if (e != null) GemLog.e(TAG, "Échec : $message", e) else GemLog.e(TAG, "Échec : $message")
        running = false
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val channelId = "gempicker_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "GemPicker", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("GemPicker actif")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        GemLog.i(TAG, "Service arrêté")
        running = false
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        debugBitmap?.recycle()
        debugBitmap = null
        virtualDisplay?.release()
        projection?.stop()
        imageReader?.close()
        handlerThread?.quitSafely()
        super.onDestroy()
    }
}
