package com.lethargicrunner.gempicker

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/**
 * Détection : gemme bleue (losange) + cercle d'action vert.
 * Tap UNIQUEMENT si la gemme entière est franchement dans le cercle
 * (marge 20 %) — pour ne jamais déclencher la popup d'erreur du jeu.
 *
 * PERFORMANCE : analyse à résolution réduite (ANALYSIS_SCALE) : ÷2 →
 * 4× moins de RAM/CPU. Seuils relatifs à la largeur analysée.
 *
 * DIAGNOSTIC : toutes les 100 frames (~10 s), log détaillé des
 * observations (contours verts/bleus, rayons, aires) pour calibrer
 * les seuils HSV à distance via le journal.
 */
class GemDetector(private val screenW: Int, private val screenH: Int) {

    companion object {
        const val ANALYSIS_SCALE = 0.5f
    }

    init {
        if (OpenCVLoader.initLocal()) {
            GemLog.i("GemDetector", "OpenCV natif chargé (${Core.VERSION})")
        } else {
            GemLog.e("GemDetector", "ÉCHEC du chargement natif d'OpenCV")
        }
    }

    private val aw = (screenW * ANALYSIS_SCALE).toInt()
    private val ah = (screenH * ANALYSIS_SCALE).toInt()

    // ── ZONES INTERDITES AU TAP ──
    private val ABSOLUTE_NO_TAP_BOTTOM_FRACTION = 0.88f
    private val ABSOLUTE_NO_TAP_TOP_FRACTION    = 0.12f

    // Teintes à calibrer
    private val blueLow  = Scalar(85.0, 90.0, 100.0)
    private val blueHigh = Scalar(115.0, 255.0, 255.0)
    private val greenLow  = Scalar(40.0, 60.0, 80.0)
    private val greenHigh = Scalar(85.0, 255.0, 255.0)

    private val minGemArea = 0.5 * (0.04 * aw) * (0.04 * aw)
    private val maxGemArea = 0.5 * (0.10 * aw) * (0.10 * aw)
    private val circleMinRadius = 0.12 * aw

    private val inclusionMargin = 0.80f
    private var lastTapAt = 0L
    private val cooldownMs = 800L
    private var frameCount = 0

    data class Circle(val cx: Double, val cy: Double, val radius: Double, val area: Double)

    fun detect(image: Image): ScreenCaptureService.Detection? {
        val full = image.toBitmapCompat() ?: return null

        val roiFull = Bitmap.createBitmap(full, (screenW * 0.10f).toInt(), (screenH * 0.15f).toInt(),
            (screenW * 0.80f).toInt(), (screenH * 0.70f).toInt())
        val roi = Bitmap.createScaledBitmap(roiFull,
            (roiFull.width * ANALYSIS_SCALE).toInt(), (roiFull.height * ANALYSIS_SCALE).toInt(), true)

        val mat = Mat(roi.height, roi.width, CvType.CV_8UC4)
        Utils.bitmapToMat(roi, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2HSV)
        Imgproc.GaussianBlur(mat, mat, Size(5.0, 5.0), 0.0)

        // ── 1. Masque VERT → cercle d'action ──
        val greenMask = Mat()
        Core.inRange(mat, greenLow, greenHigh, greenMask)
        Imgproc.dilate(greenMask, greenMask,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(13.0, 13.0)))

        val greenContours = ArrayList<MatOfPoint>()
        Imgproc.findContours(greenMask, greenContours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        var circle: Circle? = null
        var bestCircleRadius = 0.0
        var bestCircleCircularity = 0.0
        for (c in greenContours) {
            val area = Imgproc.contourArea(c)
            val perim = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
            if (perim <= 0.0) continue
            val circularity = 4 * Math.PI * area / (perim * perim)
            val encCenter = Point()
            val encRadius = floatArrayOf(0f)
            Imgproc.minEnclosingCircle(MatOfPoint2f(*c.toArray()), encCenter, encRadius)
            if (encRadius[0] > bestCircleRadius) {
                bestCircleRadius = encRadius[0].toDouble()
                bestCircleCircularity = circularity
            }
            if (encRadius[0] > circleMinRadius && circularity > 0.6) {
                val m = Imgproc.moments(c)
                if (m.m00 == 0.0) continue
                val cx = m.m10 / m.m00
                val cy = m.m01 / m.m00
                var radius = 0.0
                for (p in c.toArray()) {
                    val d = hypot((p.x - cx), (p.y - cy))
                    if (d > radius) radius = d
                }
                if (circle == null || area > circle.area) circle = Circle(cx, cy, radius, area)
            }
        }
        if (circle == null) {
            greenMask.release(); mat.release()
            maybeLogDiagnostics(greenCount = greenContours.size, blueCount = -1,
                bestBlueArea = -1.0, circle = null)
            return null
        }

        // ── 2. Masque BLEU → gemme ──
        val blueMask = Mat()
        Core.inRange(mat, blueLow, blueHigh, blueMask)
        mat.release()
        Imgproc.erode(blueMask, blueMask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Imgproc.dilate(blueMask, blueMask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))

        if (ScreenCaptureService.debugEnabled) buildDebugBitmap(blueMask, greenMask, roi)
        greenMask.release()

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(blueMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        blueMask.release()

        var best: ScreenCaptureService.Detection? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestBlueArea = 0.0
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area > bestBlueArea) bestBlueArea = area
            if (area < minGemArea || area > maxGemArea) continue

            val moments = Imgproc.moments(c)
            if (moments.m00 == 0.0) continue
            val cx = (moments.m10 / moments.m00).toFloat()
            val cy = (moments.m01 / moments.m00).toFloat()

            val absY = cy / ah
            if (absY < ABSOLUTE_NO_TAP_TOP_FRACTION ||
                absY > ABSOLUTE_NO_TAP_BOTTOM_FRACTION) continue

            var maxDist = 0.0
            for (p in c.toArray()) {
                val d = hypot((p.x - circle.cx), (p.y - circle.cy))
                if (d > maxDist) maxDist = d
            }
            if (maxDist > circle.radius * inclusionMargin) continue

            val dist = hypot((cx - circle.cx), (cy - circle.cy))
            val score = area * 1000 - dist
            if (score > bestScore) {
                bestScore = score
                val roiLeftScreen = (screenW * 0.10f)
                val roiTopScreen = (screenH * 0.15f)
                best = ScreenCaptureService.Detection(
                    (cx / ANALYSIS_SCALE) + roiLeftScreen,
                    (cy / ANALYSIS_SCALE) + roiTopScreen,
                    area / maxGemArea, System.currentTimeMillis()
                )
            }
        }

        maybeLogDiagnostics(greenCount = greenContours.size, blueCount = contours.size,
            bestBlueArea = bestBlueArea, circle = circle)

        if (best == null) return null
        if (SystemClock.uptimeMillis() - lastTapAt < cooldownMs) return null
        lastTapAt = SystemClock.uptimeMillis()
        GemLog.i("GemDetector", "TAP déclenché en (${best.x.toInt()}, ${best.y.toInt()})")
        return best
    }

    /**
     * Toutes les 100 frames : observation chiffrée pour calibrer les seuils.
     * cercle=null → pourquoi (aucun contour vert, ou rayon/circularité insuffisants).
     */
    private var lastDiagAt = 0L
    private fun maybeLogDiagnostics(greenCount: Int, blueCount: Int, bestBlueArea: Double, circle: Circle?) {
        if (++frameCount % 100 != 0) return
        if (SystemClock.uptimeMillis() - lastDiagAt < 9000) return
        lastDiagAt = SystemClock.uptimeMillis()
        val c = if (circle != null)
            "cercle: r=${"%.0f".format(circle.radius)} (min $circleMinRadius) à (${"%.0f".format(circle.cx)}, ${"%.0f".format(circle.cy)})"
        else
            "cercle: AUCUN"
        GemLog.i("Diag", "verts=$greenCount $c | bleus=$blueCount " +
            "aireBleuMax=${"%.0f".format(bestBlueArea)} (attendu ${"%.0f".format(minGemArea)}-${"%.0f".format(maxGemArea)})")
    }

    /** Masque composite BGRA : canal B = gemme, canal G = cercle, affiché par DebugOverlay */
    private fun buildDebugBitmap(blueMask: Mat, greenMask: Mat, roi: Bitmap) {
        try {
            val zeros = Mat.zeros(blueMask.size(), CvType.CV_8U)
            val alpha = Mat(blueMask.size(), CvType.CV_8U, Scalar.all(255.0))
            val bgra = Mat()
            Core.merge(ArrayList(listOf(blueMask, greenMask, zeros, alpha)), bgra)
            val bmp = Bitmap.createBitmap(roi.width, roi.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(bgra, bmp)
            bgra.release(); zeros.release(); alpha.release()
            ScreenCaptureService.debugBitmap = bmp
        } catch (e: Exception) {
            GemLog.e("GemDetector", "Erreur aperçu debug", e)
        }
    }

    private fun Image.toBitmapCompat(): Bitmap? = try {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bmp = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        Bitmap.createBitmap(bmp, 0, 0, width, height)
    } catch (e: Exception) {
        GemLog.e("GemDetector", "Conversion Image → Bitmap échouée", e)
        null
    }
}
