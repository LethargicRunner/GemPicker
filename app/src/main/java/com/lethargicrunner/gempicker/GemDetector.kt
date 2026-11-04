package com.lethargicrunner.gempicker

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Détection : gemme bleue (losange) + cercle d'action vert.
 * Tap UNIQUEMENT si la gemme entière est franchement dans le cercle
 * (marge 20 %) — pour ne jamais déclencher la popup d'erreur du jeu.
 */
class GemDetector(private val screenW: Int, private val screenH: Int) {

    // ── ZONES INTERDITES AU TAP ──
    // ROI : HUD haut (faux diamant bleu) et barre du bas
    // (BOUTIQUE, GAGNER, CARTE, ÉVÉNEMENTS, COMMUNAUTÉ) exclus.
    private val roiLeft   = (screenW * 0.10f).toInt()
    private val roiTop    = (screenH * 0.15f).toInt()
    private val roiRight  = (screenW * 0.90f).toInt()
    private val roiBottom = (screenH * 0.85f).toInt()

    // Barrières absolues indépendantes de la ROI
    private val ABSOLUTE_NO_TAP_BOTTOM_FRACTION = 0.88f
    private val ABSOLUTE_NO_TAP_TOP_FRACTION    = 0.12f

    // À calibrer avec le guide (captures calib-*.jpg)
    private val blueLow  = Scalar(100.0, 80.0, 60.0)
    private val blueHigh = Scalar(125.0, 255.0, 255.0)
    private val greenLow  = Scalar(45.0, 90.0, 60.0)
    private val greenHigh = Scalar(75.0, 255.0, 255.0)

    private val inclusionMargin = 0.80f
    private var lastTapAt = 0L
    private val cooldownMs = 800L

    data class Circle(val cx: Double, val cy: Double, val radius: Double, val area: Double)

    fun detect(image: Image): ScreenCaptureService.Detection? {
        val bmp = image.toBitmapCompat() ?: return null
        val roi = Bitmap.createBitmap(bmp, roiLeft, roiTop, roiRight - roiLeft, roiBottom - roiTop)

        val mat = Mat(roi.height, roi.width, CvType.CV_8UC4)
        Utils.bitmapToMat(roi, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2HSV)
        Imgproc.GaussianBlur(mat, mat, Size(5.0, 5.0), 0.0)

        // ── 1. Masque VERT → cercle d'action ──
        val greenMask = Mat()
        Core.inRange(mat, greenLow, greenHigh, greenMask)
        Imgproc.dilate(greenMask, greenMask,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)))

        val greenContours = ArrayList<MatOfPoint>()
        Imgproc.findContours(greenMask, greenContours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        greenMask.release()

        var circle: Circle? = null
        for (c in greenContours) {
            val area = Imgproc.contourArea(c)
            val perim = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
            if (perim <= 0.0) continue
            val circularity = 4 * Math.PI * area / (perim * perim)
            if (area > CIRCLE_MIN_AREA && circularity > 0.6) {
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
        mat.release()
        if (circle == null) return null   // pas de cercle → aucun tap (sécurité)

        // ── 2. Masque BLEU → gemme ──
        val blueMask = Mat()
        // réutilise l'image HSV déjà libérée : recalcule sur la ROI
        // (simplification : relance une conversion légère)
        val mat2 = Mat()
        Utils.bitmapToMat(roi, mat2)
        Imgproc.cvtColor(mat2, mat2, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(mat2, mat2, Imgproc.COLOR_BGR2HSV)
        Core.inRange(mat2, blueLow, blueHigh, blueMask)
        mat2.release()
        Imgproc.erode(blueMask, blueMask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Imgproc.dilate(blueMask, blueMask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(blueMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        blueMask.release()

        var best: ScreenCaptureService.Detection? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < MIN_GEM_AREA || area > MAX_GEM_AREA) continue

            val moments = Imgproc.moments(c)
            if (moments.m00 == 0.0) continue
            val cx = (moments.m10 / moments.m00).toFloat()
            val cy = (moments.m01 / moments.m00).toFloat()

            // Barrière absolue : jamais dans le HUD ni la barre du bas
            val absY = cy + roiTop
            if (absY < screenH * ABSOLUTE_NO_TAP_TOP_FRACTION ||
                absY > screenH * ABSOLUTE_NO_TAP_BOTTOM_FRACTION) continue

            // Inclusion stricte : la gemme ENTIÈRE dans le cercle réduit de 20 %
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
                best = ScreenCaptureService.Detection(
                    cx + roiLeft, cy + roiTop,
                    area / MAX_GEM_AREA, System.currentTimeMillis()
                )
            }
        }

        if (best == null) return null
        if (SystemClock.uptimeMillis() - lastTapAt < cooldownMs) return null
        lastTapAt = SystemClock.uptimeMillis()
        return best
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
    } catch (e: Exception) { null }

    companion object {
        // À calibrer : voir le guide de calibration (captures calib-*.jpg)
        var MIN_GEM_AREA = 200.0
        var MAX_GEM_AREA = 6000.0
        var CIRCLE_MIN_AREA = 8000.0
    }
}
