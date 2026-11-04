package com.lethargicrunner.gempicker

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/**
 * Détection : gemme bleue (losange) + cercle d'action vert.
 * Tap UNIQUEMENT si la gemme entière est franchement dans le cercle
 * (marge 20 %) — pour ne jamais déclencher la popup d'erreur du jeu.
 *
 * Calibration (4 nov. 2026, d'après 3 captures réelles du téléphone) :
 * - gemme : bleu clair cyan, losange ~1/20 de la largeur d'écran, liseré blanc fin
 * - cercle : anneau vert clair NON rempli, fin (2-3 px), diamètre ~1/3 de la largeur
 * - tous les seuils sont RELATIFS à la largeur d'écran (indépendant de la résolution)
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

    // Teinte cyan de la gemme (OpenCV H ∈ [0,180] : 85-115 ≈ 170-230°)
    private val blueLow  = Scalar(85.0, 90.0, 100.0)
    private val blueHigh = Scalar(115.0, 255.0, 255.0)
    // Vert clair de l'anneau d'action
    private val greenLow  = Scalar(40.0, 60.0, 80.0)
    private val greenHigh = Scalar(85.0, 255.0, 255.0)

    // Seuils relatifs à la largeur d'écran (captures : gemme ≈ 1/20 largeur,
    // cercle ≈ 1/3 largeur de diamètre, anneau fin)
    private val minGemArea = 0.5 * (0.04 * screenW) * (0.04 * screenW)   // losange ≈ d²/2
    private val maxGemArea = 0.5 * (0.10 * screenW) * (0.10 * screenW)
    private val circleMinRadius = 0.12 * screenW

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
        // Anneau fin (2-3 px) : dilatation plus large pour le refermer
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
            // Anneau fin : l'aire du contour est faible → filtre par RAYON
            // du cercle englobant, pas par aire de contour.
            // Signature : minEnclosingCurve(points, center: Point, radius: float[])
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
        if (circle == null) { greenMask.release(); mat.release(); return null }

        // ── 2. Masque BLEU → gemme ──
        val blueMask = Mat()
        Core.inRange(mat, blueLow, blueHigh, blueMask)
        mat.release()
        Imgproc.erode(blueMask, blueMask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Imgproc.dilate(blueMask, blueMask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))

        // ── DEBUG : aperçu des masques (B = gemme, G = cercle) ──
        if (ScreenCaptureService.debugEnabled) buildDebugBitmap(blueMask, greenMask, roi)
        greenMask.release()

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(blueMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        blueMask.release()

        var best: ScreenCaptureService.Detection? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < minGemArea || area > maxGemArea) continue

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
                    area / maxGemArea, System.currentTimeMillis()
                )
            }
        }

        if (best == null) return null
        if (SystemClock.uptimeMillis() - lastTapAt < cooldownMs) return null
        lastTapAt = SystemClock.uptimeMillis()
        return best
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
        } catch (_: Exception) { }
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
}
