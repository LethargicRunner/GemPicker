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
 * Détection : formes cibles (gemme bleue, jaune, rose-mauve au choix)
 * + cercle d'action vert. Tap UNIQUEMENT si la forme entière est
 * franchement dans le cercle (marge 20 %).
 *
 * CALIBRATION : l'histogramme des teintes saturées de l'écran est logué
 * toutes les ~10 s (ligne Diag) — il révèle les teintes HSV RÉELLES du jeu,
 * qui servent ensuite à régler les gammes ci-dessous.
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

    private val ABSOLUTE_NO_TAP_BOTTOM_FRACTION = 0.88f
    private val ABSOLUTE_NO_TAP_TOP_FRACTION    = 0.12f

    // ── Gammes HSV à calibrer via l'histogramme du journal ──
    // (H OpenCV ∈ [0,180] ; 180° = 2 × teinte en degrés)
    private val blueLow  = Scalar(85.0, 90.0, 100.0)
    private val blueHigh = Scalar(115.0, 255.0, 255.0)
    private val greenLow  = Scalar(40.0, 60.0, 80.0)
    private val greenHigh = Scalar(85.0, 255.0, 255.0)
    private val yellowLow  = Scalar(18.0, 80.0, 80.0)
    private val yellowHigh = Scalar(38.0, 255.0, 255.0)
    private val pinkLow  = Scalar(130.0, 60.0, 80.0)
    private val pinkHigh = Scalar(175.0, 255.0, 255.0)

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
        for (c in greenContours) {
            val area = Imgproc.contourArea(c)
            val perim = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
            if (perim <= 0.0) continue
            val circularity = 4 * Math.PI * area / (perim * perim)
            val encCenter = Point()
            val encRadius = floatArrayOf(0f)
            Imgproc.minEnclosingCircle(MatOfPoint2f(*c.toArray()), encCenter, encRadius)
            if (encRadius[0] > circleMinRadius && circularity > 0.6) {
                val m = Imgproc.moments(c)
                if (m.m00 == 0.0) continue
                val cx = m.m10 / m.m00
                val cy = m.m01 / m00
                var radius = 0.0
                for (p in c.toArray()) {
                    val d = hypot((p.x - cx), (p.y - cy))
                    if (d > radius) radius = d
                }
                if (circle == null || area > circle.area) circle = Circle(cx, cy, radius, area)
            }
        }

        // ── 2. Masques CIBLES (bleu / jaune / rose selon options) ──
        val targetMask = Mat.zeros(mat.size(), CvType.CV_8U)
        if (ScreenCaptureService.targetBlue) addRange(mat, targetMask, blueLow, blueHigh)
        if (ScreenCaptureService.targetYellow) addRange(mat, targetMask, yellowLow, yellowHigh)
        if (ScreenCaptureService.targetPink) addRange(mat, targetMask, pinkLow, pinkHigh)
        if (ScreenCaptureService.targetBlue || ScreenCaptureService.targetYellow || ScreenCaptureService.targetPink) {
            val m2 = Mat()
            Core.inRange(mat, blueLow, blueHigh, m2)
            Core.bitwise_or(targetMask, m2, targetMask)
            m2.release()
        }
        mat.release()
        Imgproc.erode(targetMask, targetMask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Imgproc.dilate(targetMask, targetMask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))

        if (ScreenCaptureService.debugEnabled) buildDebugBitmap(targetMask, greenMask, roi)
        greenMask.release()

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(targetMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        targetMask.release()

        var best: ScreenCaptureService.Detection? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestTargetArea = 0.0
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area > bestTargetArea) bestTargetArea = area
            if (area < minGemArea || area > maxGemArea) continue
            if (circle == null) continue

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

        maybeLogDiagnostics(greenCount = greenContours.size, targetCount = contours.size,
            bestTargetArea = bestTargetArea, circle = circle, hsvMat = null)

        if (best == null) return null
        if (SystemClock.uptimeMillis() - lastTapAt < cooldownMs) return null
        lastTapAt = SystemClock.uptimeMillis()
        GemLog.i("GemDetector", "TAP déclenché en (${best.x.toInt()}, ${best.y.toInt()})")
        return best
    }

    private fun addRange(mat: Mat, target: Mat, low: Scalar, high: Scalar) {
        val m = Mat()
        Core.inRange(mat, low, high, m)
        Core.bitwise_or(target, m, target)
        m.release()
    }

    // Garde le dernier mat HSV pour l'histogramme du diagnostic
    private var lastHsv: Mat? = null

    /**
     * Toutes les ~10 s : observations chiffrées + HISTOGRAMME des teintes
     * saturées (S>80, V>80) de l'écran. C'est la valeur de H dominante qui
     * dit exactement quelle gamme HSV utiliser pour chaque forme du jeu.
     */
    private var lastDiagAt = 0L
    private fun maybeLogDiagnostics(greenCount: Int, targetCount: Int,
                                    bestTargetArea: Double, circle: Circle?, hsvMat: Mat?) {
        if (++frameCount % 100 != 0) return
        if (SystemClock.uptimeMillis() - lastDiagAt < 9000) return
        lastDiagAt = SystemClock.uptimeMillis()

        val c = if (circle != null)
            "cercle: r=${"%.0f".format(circle.radius)} (min $circleMinRadius) à (${"%.0f".format(circle.cx)}, ${"%.0f".format(circle.cy)})"
        else "cercle: AUCUN"
        GemLog.i("Diag", "verts=$greenCount $c | cibles=$targetCount " +
            "aireCibleMax=${"%.0f".format(bestTargetArea)} (attendu ${"%.0f".format(minGemArea)}-${"%.0f".format(maxGemArea)})")

        // Histogramme des teintes saturées : 6 bins les plus peuplés
        try {
            val hsv = lastHsv ?: return
            val channels = ArrayList<Mat>()
            Core.split(hsv, channels)
            val h = channels[0]; val s = channels[1]; val v = channels[2]
            val satMask = Mat()
            val sMask = Mat(); val vMask = Mat()
            Core.inRange(s, Scalar(80.0), Scalar(255.0), sMask)
            Core.inRange(v, Scalar(80.0), Scalar(255.0), vMask)
            Core.bitwise_and(sMask, vMask, satMask)
            val hist = Mat()
            Imgproc.calcHist(listOf(h), Mat(), satMask, hist, MatOfInt(180), MatOfFloat(0f, 180f))
            var total = 0.0
            val bins = ArrayList<Pair<Int, Double>>()
            for (i in 0 until 180) {
                val n = hist.get(i, 0)[0]
                total += n
                bins.add(i to n)
            }
            hist.release()
            val top = bins.sortedByDescending { it.second }.take(8)
                .filter { it.second > total * 0.005 }
                .joinToString(", ") { (hue, n) ->
                    "H$hue=${"%.1f".format(100 * n / total)}%" }
            GemLog.i("Diag", "Teintes saturées: $top")
            sMask.release(); vMask.release(); satMask.release()
            channels.forEach { it.release() }
        } catch (e: Exception) {
            GemLog.e("Diag", "Erreur histogramme", e)
        }
    }

    /** Masque composite BGRA : canal B = cibles, canal G = cercle, affiché par DebugOverlay */
    private fun buildDebugBitmap(targetMask: Mat, greenMask: Mat, roi: Bitmap) {
        try {
            val zeros = Mat.zeros(targetMask.size(), CvType.CV_8U)
            val alpha = Mat(targetMask.size(), CvType.CV_8U, Scalar.all(255.0))
            val bgra = Mat()
            Core.merge(ArrayList(listOf(targetMask, greenMask, zeros, alpha)), bgra)
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
