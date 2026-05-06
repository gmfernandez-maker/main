package com.gabby.studiowebwrapper.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ImageQualityReport(
    val width: Int,
    val height: Int,
    val brightnessMean: Double,
    val contrastStdDev: Double,
    val sharpnessVariance: Double,
    val blockingIssues: List<String>,
    val warnings: List<String>
)

object ImageUtils {
    fun toDataUri(context: Context, uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Unable to read selected image")
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mimeType;base64,$encoded"
    }

    fun bitmapToDataUri(bitmap: Bitmap, mimeType: String = "image/jpeg", quality: Int = 90): String {
        val output = ByteArrayOutputStream()
        val format = if (mimeType.contains("png", ignoreCase = true)) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        bitmap.compress(format, quality, output)
        val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        return "data:$mimeType;base64,$encoded"
    }

    fun decodeBitmap(context: Context, uri: Uri): Bitmap {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Unable to decode selected image")
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Unable to decode selected image")
    }

    fun decodeDataUri(dataUri: String): Bitmap {
        val parts = dataUri.split(",", limit = 2)
        if (parts.size != 2) throw IOException("Invalid data URI")
        val bytes = Base64.decode(parts[1], Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Unable to decode data URI image")
    }

    fun analyzeImageQuality(bitmap: Bitmap): ImageQualityReport {
        val sampled = downscaleForAnalysis(bitmap)
        val w = sampled.width
        val h = sampled.height
        val px = IntArray(w * h)
        sampled.getPixels(px, 0, w, 0, 0, w, h)

        val gray = DoubleArray(px.size)
        var sum = 0.0
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            gray[i] = luma
            sum += luma
        }

        val mean = if (gray.isNotEmpty()) sum / gray.size else 0.0
        var variance = 0.0
        for (value in gray) {
            val delta = value - mean
            variance += delta * delta
        }
        val stdDev = if (gray.isNotEmpty()) sqrt(variance / gray.size) else 0.0

        var lapSum = 0.0
        var lapSqSum = 0.0
        var lapCount = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = gray[y * w + x]
                val up = gray[(y - 1) * w + x]
                val down = gray[(y + 1) * w + x]
                val left = gray[y * w + (x - 1)]
                val right = gray[y * w + (x + 1)]
                val lap = up + down + left + right - (4.0 * center)
                lapSum += lap
                lapSqSum += lap * lap
                lapCount += 1
            }
        }
        val lapMean = if (lapCount > 0) lapSum / lapCount else 0.0
        val sharpnessVar = if (lapCount > 0) (lapSqSum / lapCount) - (lapMean * lapMean) else 0.0

        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val minEdge = min(bitmap.width, bitmap.height)
        if (minEdge < 80) {
            blocking += "Resolution is too low. Use an image at least 80px on the shortest side."
        } else if (minEdge < 360) {
            warnings += "Image resolution is modest. A larger photo will improve grading accuracy."
        }

        if (sharpnessVar < 1.0) {
            blocking += "Image is too blurry for reliable grading."
        } else if (sharpnessVar < 40.0) {
            warnings += "Slight blur detected. Hold the phone steady and tap to focus."
        }

        if (mean < 5.0) {
            blocking += "Image is too dark. Add more light before grading."
        } else if (mean < 45.0) {
            warnings += "Lighting is dim. Better lighting will improve accuracy."
        }

        if (mean > 220.0) {
            warnings += "Image is very bright. Reduce glare for better detail."
        }

        if (stdDev < 20.0) {
            warnings += "Low contrast detected. Try a darker background to improve edge detail."
        }

        val ratio = max(bitmap.width, bitmap.height).toDouble() / minEdge.toDouble().coerceAtLeast(1.0)
        if (ratio > 2.4) {
            warnings += "Framing looks narrow. Keep the full item centered in view."
        }

        return ImageQualityReport(
            width = bitmap.width,
            height = bitmap.height,
            brightnessMean = mean,
            contrastStdDev = stdDev,
            sharpnessVariance = sharpnessVar,
            blockingIssues = blocking,
            warnings = warnings
        )
    }

    private fun downscaleForAnalysis(bitmap: Bitmap): Bitmap {
        val maxDimension = 512
        if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap

        val scale = min(
            maxDimension.toFloat() / bitmap.width.toFloat(),
            maxDimension.toFloat() / bitmap.height.toFloat()
        )
        val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
}
