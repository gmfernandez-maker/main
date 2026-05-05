package com.gabby.studiowebwrapper.util

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.core.Size

object DescriptorUtils {

    private const val TAG = "DescriptorUtils"
    private const val ORB_MIN_DIM = 32

    fun computeLBPHist(bitmap: Bitmap, useClahe: Boolean = true): FloatArray {
        val gray = toGrayArray(bitmap, useClahe)
        val w = bitmap.width
        val h = bitmap.height

        val hist = IntArray(256)
        if (w < 3 || h < 3) {
            for (v in gray) {
                hist[v] = hist[v] + 1
            }
            return normalizeHist(hist)
        }

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = gray[y * w + x]
                var pattern = 0

                if (gray[(y - 1) * w + (x - 1)] >= c) pattern = pattern or (1 shl 7)
                if (gray[(y - 1) * w + x] >= c) pattern = pattern or (1 shl 6)
                if (gray[(y - 1) * w + (x + 1)] >= c) pattern = pattern or (1 shl 5)
                if (gray[y * w + (x + 1)] >= c) pattern = pattern or (1 shl 4)
                if (gray[(y + 1) * w + (x + 1)] >= c) pattern = pattern or (1 shl 3)
                if (gray[(y + 1) * w + x] >= c) pattern = pattern or (1 shl 2)
                if (gray[(y + 1) * w + (x - 1)] >= c) pattern = pattern or (1 shl 1)
                if (gray[y * w + (x - 1)] >= c) pattern = pattern or (1 shl 0)

                hist[pattern] = hist[pattern] + 1
            }
        }

        return normalizeHist(hist)
    }

    // Optional spatial LBP for future tuning while preserving current 256-bin API.
    fun computeSpatialLBPHist(bitmap: Bitmap, gridX: Int = 4, gridY: Int = 4, useClahe: Boolean = true): FloatArray {
        val gx = gridX.coerceAtLeast(1)
        val gy = gridY.coerceAtLeast(1)
        val gray = toGrayArray(bitmap, useClahe)
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return computeLBPHist(bitmap, useClahe)

        val cellW = (w / gx).coerceAtLeast(1)
        val cellH = (h / gy).coerceAtLeast(1)
        val out = FloatArray(256 * gx * gy)

        for (cy in 0 until gy) {
            for (cx in 0 until gx) {
                val xStart = (cx * cellW).coerceAtLeast(1)
                val yStart = (cy * cellH).coerceAtLeast(1)
                val xEnd = if (cx == gx - 1) w - 1 else ((cx + 1) * cellW).coerceAtMost(w - 1)
                val yEnd = if (cy == gy - 1) h - 1 else ((cy + 1) * cellH).coerceAtMost(h - 1)

                val local = IntArray(256)
                for (y in yStart until yEnd) {
                    for (x in xStart until xEnd) {
                        val c = gray[y * w + x]
                        var pattern = 0
                        if (gray[(y - 1) * w + (x - 1)] >= c) pattern = pattern or (1 shl 7)
                        if (gray[(y - 1) * w + x] >= c) pattern = pattern or (1 shl 6)
                        if (gray[(y - 1) * w + (x + 1)] >= c) pattern = pattern or (1 shl 5)
                        if (gray[y * w + (x + 1)] >= c) pattern = pattern or (1 shl 4)
                        if (gray[(y + 1) * w + (x + 1)] >= c) pattern = pattern or (1 shl 3)
                        if (gray[(y + 1) * w + x] >= c) pattern = pattern or (1 shl 2)
                        if (gray[(y + 1) * w + (x - 1)] >= c) pattern = pattern or (1 shl 1)
                        if (gray[y * w + (x - 1)] >= c) pattern = pattern or (1 shl 0)
                        local[pattern] = local[pattern] + 1
                    }
                }

                val localNorm = normalizeHist(local)
                val base = (cy * gx + cx) * 256
                for (i in 0 until 256) out[base + i] = localNorm[i]
            }
        }

        return out
    }

    private fun toGrayArray(bitmap: Bitmap, useClahe: Boolean): IntArray {
        if (useClahe) {
            return try {
                val enhanced = enhanceGrayWithClahe(bitmap)
                val out = IntArray(enhanced.cols() * enhanced.rows())
                val bytes = ByteArray(out.size)
                enhanced.get(0, 0, bytes)
                for (i in out.indices) {
                    out[i] = bytes[i].toInt() and 0xFF
                }
                enhanced.release()
                out
            } catch (_: Throwable) {
                // Fall back to pure Kotlin grayscale if OpenCV/CLAHE is unavailable.
                toGrayArrayFallback(bitmap)
            }
        }
        return toGrayArrayFallback(bitmap)
    }

    private fun toGrayArrayFallback(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = ((77 * r + 150 * g + 29 * b) shr 8)
        }
        return gray
    }

    private fun enhanceGrayWithClahe(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)
        rgba.release()
        gray.release()
        return enhanced
    }

    private fun normalizeHist(hist: IntArray): FloatArray {
        val total = hist.sum().coerceAtLeast(1)
        val out = FloatArray(hist.size)
        for (i in hist.indices) out[i] = hist[i].toFloat() / total.toFloat()
        return out
    }

    fun orbDescriptorsFromBitmap(bitmap: Bitmap, nfeatures: Int = 500, useClahe: Boolean = true): Mat {
        if (bitmap.isRecycled || bitmap.width <= 1 || bitmap.height <= 1) {
            return Mat()
        }

        var gray: Mat? = null
        var keypoints: MatOfKeyPoint? = null
        return try {
            gray = toGrayMat(bitmap, useClahe)
            if (gray.empty() || gray.cols() < ORB_MIN_DIM || gray.rows() < ORB_MIN_DIM) {
                val targetW = gray.cols().coerceAtLeast(ORB_MIN_DIM)
                val targetH = gray.rows().coerceAtLeast(ORB_MIN_DIM)
                val upscaled = Mat()
                Imgproc.resize(gray, upscaled, Size(targetW.toDouble(), targetH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                gray.release()
                gray = upscaled
            }

            val orb = ORB.create(nfeatures)
            keypoints = MatOfKeyPoint()
            val descriptors = Mat()
            orb.detectAndCompute(gray, Mat(), keypoints, descriptors)
            descriptors
        } catch (t: Throwable) {
            Log.w(TAG, "ORB detectAndCompute failed: ${t.javaClass.simpleName} ${t.message}")
            Mat()
        } finally {
            keypoints?.release()
            gray?.release()
        }
    }

    private fun toGrayMat(bitmap: Bitmap, useClahe: Boolean): Mat {
        if (useClahe) {
            try {
                return enhanceGrayWithClahe(bitmap)
            } catch (_: Throwable) {
                // Fall through to plain grayscale conversion.
            }
        }

        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val out = Mat()
        Imgproc.cvtColor(mat, out, Imgproc.COLOR_RGBA2GRAY)
        mat.release()
        return out
    }

    fun orbMatchScore(des1: Mat?, des2: Mat?): Float {
        if (des1 == null || des2 == null) return 0f
        if (des1.empty() || des2.empty()) return 0f
        val matcher = BFMatcher.create(Core.NORM_HAMMING, true)
        val matches = MatOfDMatch()
        matcher.match(des1, des2, matches)
        val count = matches.toArray().size
        val denom = kotlin.math.max(1f, kotlin.math.min(des1.rows().toFloat(), des2.rows().toFloat()))
        return count.toFloat() / denom
    }

    fun lbpChi2Similarity(h1: FloatArray, h2: FloatArray): Float {
        val eps = 1e-10f
        var chi2 = 0.0f
        val n = kotlin.math.min(h1.size, h2.size)
        for (i in 0 until n) {
            val a = h1[i]
            val b = h2[i]
            val num = (a - b) * (a - b)
            val den = a + b + eps
            chi2 += 0.5f * (num / den)
        }
        return 1.0f / (1.0f + chi2)
    }
}
