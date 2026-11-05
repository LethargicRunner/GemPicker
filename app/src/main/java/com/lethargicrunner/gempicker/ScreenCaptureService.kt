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
import androidx.core.app.ServiceCompat

class ScreenCaptureService : Service() {

    // Imbriquée directement dans la classe (PAS dans le companion) :
    // ainsi ScreenCaptureService.Detection est résolvable depuis GemDetector.
    data class Detection(val x: Float, val y: Float, val confidence: Double, val ts: Long)

    companion object {
        const val TAG = "GemPicker"
        const val ACTION_STOP = "com.lethargicrunner.gempicker.STOP"
        const val ANALYSIS_INTERVAL_MS = 100L   // ~10 fps
        const val NOTIFICATION_ID = 1

        @Volatile var lastDetection: Detection? = null
        @Volatile var running = false

        // Mode debug : aperçu des masques (appui long sur la bulle)
        @Volatile var debugEnabled = false
        @Volatile var debugBitmap: Bitmap? = null
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var detector: GemDetector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val notification = buildNotification()
        // Android 14+ (API 34) : startForeground DOIT préciser le type,
        // sinon crash MissingForegroundServiceTypeException.
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
        if (data == null) { Log.w(TAG, "Pas de session de projection"); stopSelf(); return START_NOT_STICKY }
        projection = mgr.getMediaProjection(MediaProjectionHolder.resultCode, data)
        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "gempicker_capture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        detector = GemDetector(width, height)
        handlerThread = HandlerThread("analyzer").apply { start() }
        val handler = Handler(handlerThread!!.looper)
        var lastAnalysis = 0L

        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                val now = SystemClock.uptimeMillis()
                val image = imageReader?.acquireLatestImage()
                if (image != null && now - lastAnalysis >= ANALYSIS_INTERVAL_MS) {
                    lastAnalysis = now
                    try {
                        val detection = detector?.detect(image)
                        lastDetection = detection
                        if (detection != null) {
                            AutoTapAccessibilityService.requestTap(detection.x, detection.y)
                        }
                    } finally { image.close() }
                } else image?.close()
                handler.postDelayed(this, ANALYSIS_INTERVAL_MS / 2)
            }
        })
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
        running = false
        debugBitmap?.recycle()
        debugBitmap = null
        virtualDisplay?.release()
        projection?.stop()
        imageReader?.close()
        handlerThread?.quitSafely()
        super.onDestroy()
    }
}
