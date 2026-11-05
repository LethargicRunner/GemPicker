package com.lethargicrunner.gempicker

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Deux mécaniques distinctes :
 *
 * 1. GEMME BLEUE : losange dans le cercle d'action vert -> tap uniquement
 *    si la gemme ENTIÈRE est franchement dans le cercle (marge 20 %).
 *
 * 2. POISSONS JAUNES / ROSE-MAUVE : minijeu immobile, SANS cercle ->
 *    tap direct où qu'ils apparaissent dans la zone de jeu
 *    (hors HUD haut et barre du bas, barrières absolues).
 *
 * ANTI-BLOCAGE : une position tapée 3 fois sans effet (élément inerte du
 * décor) est ignorée 10 s pour libérer les vraies cibles.
 *
 * CALIBRATION (5 nov. 2026, capture réelle du minijeu) :
 * - poisson rose #FF00AA -> H OpenCV ~160, fourchette 150-175
 * - poissons jaunes/orange -> H OpenCV 20-40
 * - losange bleu-cyan #00F0FF en haut à droite = HUD, ne JAMAIS le taper
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

    // ── Gammes HSV calibrées sur capture réelle (5 nov. 2026) ──
    private val blueLow  = Scalar(85.0, 90.0, 100.0)
    private val blueHigh = Scalar(115.0, 255.0, 255.0)
    private val greenLow  = Scalar(40.0, 60.0, 80.0)
    private val greenHigh = Scalar(85.0, 255.0, 255.0)
    private val yellowLow  = Scalar(20.0, 100.0, 120.0)
    private val yellowHigh = Scalar(40.0, 255.0, 255.0)
    private val pinkLow  = Scalar(150.0, 100.0, 120.0)
    private val pinkHigh = Scalar(175.0, 255.0, 255.0)

    // Tailles attendues (relatives à la largeur analysée)
    private val minGemArea = 0.5 * (0.04 * aw) * (0.04 * aw)
    private val maxGemArea = 0.5 * (0.10 * aw) * (0.10 * aw)
    private val minShapeArea = 0.5 * (0.03 * aw) * (0.03 * aw)
    private val maxShapeArea = 0.5 * (0.18 * aw) * (0.18 * aw)

    private val circleMinRadius = 0.12 * aw
    private val inclusionMargin = 0.80f
    private var lastTapAt = 0L
    private val cooldownMs = 250L
    private var frameCount = 0

    data class Circle(val cx: Double, val cy: Double, val radius: Double, val area: Double)

    // ── ANTI-BLOCAGE ──
    private class Stuck(val x: Float, val y: Float) {
        var count = 0
        var blackUntil = 0L
        var lastTapAt = 0L
    }
    private val stuckList = ArrayList<Stuck>()

    private fun isBlacklisted(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        stuckList.removeAll { it.blackUntil < now && now - it.lastTapAt > 30_000 }
        return stuckList.any { s ->
            s.blackUntil > now && abs(x - s.x) < 20f && abs(y - s.y) < 20f
        }
    }

    private fun recordTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val s = stuckList.firstOrNull { abs(x - it.x) < 20f && abs(y - it.y) < 20f }
            ?: Stuck(x, y).also { stuckList.add(it) }
        s.count++
        s.lastTapAt = now
        if (s.count >= 3) {
            s.blackUntil = now + 10_000
            s.count = 0
            GemLog.i("AntiBlocage", "Position (${x.toInt()}, ${y.toInt()}) ignorée 10 s (élément inerte tapé 3 fois)")
        }
    }

    /** Candidat de tap trouvé, avant le cooldown. */
    data class Hit(val x: Float, val y: Float, val area: Double, val score: Double)

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

        // ── 1. Cercle d'action vert (pour la gemme uniquement) ──
        var circle: Circle? = null
        var greenCount = 0
        if (ScreenCaptureService.targetBlue) {
            val greenMask = Mat()
            Core.inRange(mat, greenLow, greenHigh, greenMask)
            Imgproc.dilate(greenMask, greenMask,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(13.0, 13.0)))

            val greenContours = ArrayList<MatOfPoint>()
            Imgproc.findContours(greenMask, greenContours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            greenCount = greenContours.size

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
                    val cy = m.m01 / m.m00
                    var radius = 0.0
                    for (p in c.toArray()) {
                        val d = hypot((p.x - cx), (p.y - cy))
                        if (d > radius) radius = d
                    }
                    if (circle == null || area > circle.area) circle = Circle(cx, cy, radius, area)
                }
            }
            if (ScreenCaptureService.debugEnabled) keepDebugGreen(greenMask)
            else greenMask.release()
        }

        // ── 2. Cibles : la plus grosse forme au score le plus élevé ──
        val allMask = Mat.zeros(mat.size(), CvType.CV_8U)
        var bestHit: Hit? = null
        var bestHitArea = 0.0
        var targetCount = 0

        fun scanTargets(low: Scalar, high: Scalar, requiresCircle: Boolean, minArea: Double, maxArea: Double) {
            val mask = Mat()
            Core.inRange(mat, low, high, mask)
            Core.bitwise_or(allMask, mask, allMask)
            Imgproc.erode(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            targetCount += contours.size
            mask.release()

            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area > bestHitArea) bestHitArea = area
                if (area < minArea || area > maxArea) continue
                if (requiresCircle && circle == null) continue

                val moments = Imgproc.moments(c)
                if (moments.m00 == 0.0) continue
                val cx = (moments.m10 / moments.m00).toFloat()
                val cy = (moments.m01 / moments.m00).toFloat()

                val absY = cy / ah
                if (absY < ABSOLUTE_NO_TAP_TOP_FRACTION ||
                    absY > ABSOLUTE_NO_TAP_BOTTOM_FRACTION) continue

                val circ = circle
                if (requiresCircle) {
                    if (circ == null) continue
                    var maxDist = 0.0
                    for (p in c.toArray()) {
                        val d = hypot((p.x - circ.cx), (p.y - circ.cy))
                        if (d > maxDist) maxDist = d
                    }
                    if (maxDist > circ.radius * inclusionMargin) continue
                }

                val dist = if (requiresCircle && circ != null)
                    hypot((cx - circ.cx), (cy - circ.cy)) else 0.0
                val score = area * 1000 - dist
                if (bestHit == null || score > bestHit!!.score) {
                    val hx = (cx / ANALYSIS_SCALE) + (screenW * 0.10f)
                    val hy = (cy / ANALYSIS_SCALE) + (screenH * 0.15f)
                    if (isBlacklisted(hx, hy)) continue
                    bestHit = Hit(hx, hy, area, score)
                }
            }
        }

        if (ScreenCaptureService.targetBlue) scanTargets(blueLow, blueHigh, true, minGemArea, maxGemArea)
        if (ScreenCaptureService.targetYellow) scanTargets(yellowLow, yellowHigh, false, minShapeArea, maxShapeArea)
        if (ScreenCaptureService.targetPink) scanTargets(pinkLow, pinkHigh, false, minShapeArea, maxShapeArea)

        mat.release()
        if (ScreenCaptureService.debugEnabled) buildDebugBitmap(allMask)
        allMask.release()

        maybeLogDiagnostics(greenCount, targetCount, bestHitArea, circle, roi)

        val hit = bestHit ?: return null
        if (SystemClock.uptimeMillis() - lastTapAt < cooldownMs) return null
        lastTapAt = SystemClock.uptimeMillis()
        recordTap(hit.x, hit.y)
        GemLog.i("GemDetector", "TAP déclenché en (${hit.x.toInt()}, ${hit.y.toInt()}) aire=${"%.0f".format(hit.area)}")
        return ScreenCaptureService.Detection(hit.x, hit.y, hit.area / maxShapeArea, System.currentTimeMillis())
    }

    private var debugGreenMask: Mat? = null
    private fun keepDebugGreen(m: Mat) {
        debugGreenMask?.release()
        debugGreenMask = m.clone()
        m.release()
    }

    private var lastDiagAt = 0L
    private fun maybeLogDiagnostics(greenCount: Int, targetCount: Int,
                                    bestArea: Double, circle: Circle?, roi: Bitmap) {
        if (++frameCount % 100 != 0) return
        if (SystemClock.uptimeMillis() - lastDiagAt < 9000) return
        lastDiagAt = SystemClock.uptimeMillis()

        val c = if (circle != null)
            "cercle: r=${"%.0f".format(circle.radius)} (min $circleMinRadius) à (${"%.0f".format(circle.cx)}, ${"%.0f".format(circle.cy)})"
        else "cercle: AUCUN"
        GemLog.i("Diag", "verts=$greenCount $c | cibles=$targetCount " +
            "aireCibleMax=${"%.0f".format(bestArea)} (gemme attendue ${"%.0f".format(minGemArea)}-${"%.0f".format(maxGemArea)}, " +
            "formes ${"%.0f".format(minShapeArea)}-${"%.0f".format(maxShapeArea)})")

        try {
            val m = Mat(roi.height, roi.width, CvType.CV_8UC4)
            Utils.bitmapToMat(roi, m)
            Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(m, m, Imgproc.COLOR_BGR2HSV)
            val channels = ArrayList<Mat>()
            Core.split(m, channels)
            val h = channels[0]; val s = channels[1]; val v = channels[2]
            val sMask = Mat(); val vMask = Mat(); val satMask = Mat()
            Core.inRange(s, Scalar(80.0), Scalar(255.0), sMask)
            Core.inRange(v, Scalar(80.0), Scalar(255.0), vMask)
            Core.bitwise_and(sMask, vMask, satMask)
            val hist = Mat()
            Imgproc.calcHist(listOf(h), MatOfInt(0), satMask, hist, MatOfInt(180), MatOfFloat(0f, 180f))
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
                .joinToString(", ") { (hue, n) -> "H$hue=${"%.1f".format(100 * n / total)}%" }
            GemLog.i("Diag", "Teintes saturées: $top")
            sMask.release(); vMask.release(); satMask.release()
            channels.forEach { it.release() }
            m.release()
        } catch (e: Exception) {
            GemLog.e("Diag", "Erreur histogramme", e)
        }
    }

    private fun buildDebugBitmap(targetMask: Mat) {
        try {
            val green = debugGreenMask ?: Mat.zeros(targetMask.size(), CvType.CV_8U)
            val zeros = Mat.zeros(targetMask.size(), CvType.CV_8U)
            val alpha = Mat(targetMask.size(), CvType.CV_8U, Scalar.all(255.0))
            val bgra = Mat()
            Core.merge(ArrayList(listOf(targetMask, green, zeros, alpha)), bgra)
            val bmp = Bitmap.createBitmap(targetMask.width(), targetMask.height(), Bitmap.Config.ARGB_8888)
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
