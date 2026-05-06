package com.gabby.studiowebwrapper.util

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Classifies jewelry material (gold vs silver) using texture + color analysis.
 * Combines LBP histogram matching with HSV hue-based color filtering.
 */
object MaterialClassifier {

    data class MaterialScore(
        val goldScore: Float,      // 0-100, similarity to gold references
        val silverScore: Float,    // 0-100, similarity to silver references
        val colorBias: Float,      // -100 (silver) to +100 (gold) based on hue
        val predicted: String,     // "gold" or "silver"
        val confidence: Float      // 0-100, how confident in the prediction
    )

    /**
     * Material labels for references. Key = reference name (e.g., "IMG_1796"), Value = "gold" or "silver"
     * Inferred from image metadata. Update based on your actual gold/silver reference images.
     */
    private val materialLabels = mapOf(
        // Even-numbered references (assumed gold)
        "IMG_1796" to "gold", "IMG_1798" to "gold", "IMG_1800" to "gold", "IMG_1802" to "gold",
        "IMG_1806" to "gold", "IMG_1810" to "gold", "IMG_1812" to "gold", "IMG_1814" to "gold",
        "IMG_1816" to "gold",
        // Odd-numbered references (assumed silver)
        "IMG_1797" to "silver", "IMG_1799" to "silver", "IMG_1801" to "silver", "IMG_1803" to "silver",
        "IMG_1805" to "silver", "IMG_1807" to "silver", "IMG_1809" to "silver", "IMG_1811" to "silver",
        "IMG_1813" to "silver", "IMG_1815" to "silver", "IMG_1817" to "silver"
    )

    /**
     * Classify a test image as gold or silver based on texture + color.
     * @param testBitmap The jewelry image to classify
     * @param referenceData Pre-loaded reference data from ReferenceManager
     * @return MaterialScore with gold/silver scores and prediction
     */
    fun classify(testBitmap: Bitmap, referenceData: List<ReferenceData>): MaterialScore {
        // 1. Extract LBP histogram from test image
        val testLbpHist = LbpExtractor.extractLbpHistogram(testBitmap)

        // 2. Extract color hue from test image
        val colorBias = extractColorBias(testBitmap)

        // 3. Compute texture similarity to gold vs silver references
        var goldTextureSum = 0f
        var goldCount = 0
        var silverTextureSum = 0f
        var silverCount = 0

        referenceData.forEach { ref ->
            val material = materialLabels[ref.name] ?: return@forEach
            val refLbpHist = ref.lbpHist

            val similarity = computeHistogramSimilarity(testLbpHist, refLbpHist)

            when (material) {
                "gold" -> {
                    goldTextureSum += similarity
                    goldCount++
                }
                "silver" -> {
                    silverTextureSum += similarity
                    silverCount++
                }
            }
        }

        val goldTextureScore = if (goldCount > 0) (goldTextureSum / goldCount * 100f) else 0f
        val silverTextureScore = if (silverCount > 0) (silverTextureSum / silverCount * 100f) else 0f

        // 4. Fuse texture scores with color bias
        // Color bias: positive = gold-like, negative = silver-like
        val fusedGoldScore = (goldTextureScore * 0.7f) + (max(0f, colorBias) * 0.3f)
        val fusedSilverScore = (silverTextureScore * 0.7f) + (max(0f, -colorBias) * 0.3f)

        // 5. Normalize and clamp scores
        val maxScore = maxOf(fusedGoldScore, fusedSilverScore)
        val goldNormalized = if (maxScore > 0) (fusedGoldScore / maxScore * 100f).coerceIn(0f, 100f) else 50f
        val silverNormalized = if (maxScore > 0) (fusedSilverScore / maxScore * 100f).coerceIn(0f, 100f) else 50f

        // 6. Make prediction
        val predicted = if (goldNormalized > silverNormalized) "gold" else "silver"
        val confidence = abs(goldNormalized - silverNormalized)

        return MaterialScore(
            goldScore = goldNormalized,
            silverScore = silverNormalized,
            colorBias = colorBias,
            predicted = predicted,
            confidence = confidence
        )
    }

    /**
     * Extract color bias from test image HSV hue values.
     * Returns: positive for gold-like (warm hues), negative for silver-like (cool hues)
     */
    private fun extractColorBias(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val hsvValues = FloatArray(3)
        var goldHueScore = 0f
        var silverHueScore = 0f
        var validPixels = 0

        for (pixel in pixels) {
            // Extract RGB
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Skip very dark/light pixels (noise)
            val brightness = (r + g + b) / 3f
            if (brightness < 20f || brightness > 235f) continue

            // Convert to HSV
            android.graphics.Color.RGBToHSV(r, g, b, hsvValues)
            val hue = hsvValues[0] // 0-360 degrees

            // Gold-like: warm hues (yellow/orange) ~20-60 degrees
            // Silver-like: cool hues (blue/gray) ~180-240 degrees or achromatic (saturation < 10%)
            val saturation = hsvValues[1]

            if (saturation < 0.1f) {
                // Achromatic (grayish) = slightly silver-like
                silverHueScore += 20f
            } else if (hue in 20f..60f) {
                // Warm yellow/orange = gold-like
                goldHueScore += 50f
            } else if (hue in 180f..240f || hue in 280f..320f) {
                // Cool blue/purple = silver-like
                silverHueScore += 40f
            }

            validPixels++
        }

        if (validPixels == 0) return 0f

        val avgGoldHue = goldHueScore / validPixels
        val avgSilverHue = silverHueScore / validPixels
        val maxVal = maxOf(avgGoldHue, avgSilverHue, 1f)

        // Return normalized bias: +100 (very gold-like) to -100 (very silver-like)
        return ((avgGoldHue - avgSilverHue) / maxVal * 100f).coerceIn(-100f, 100f)
    }

    /**
     * Compute similarity between two LBP histograms using Chi-Square distance.
     * Returns value in [0, 1] where 1 = identical, 0 = completely different.
     */
    private fun computeHistogramSimilarity(hist1: FloatArray, hist2: FloatArray): Float {
        if (hist1.size != hist2.size) return 0f

        var chiSquare = 0f
        for (i in hist1.indices) {
            val bin1 = hist1[i]
            val bin2 = hist2[i]
            val sum = bin1 + bin2
            if (sum > 0f) {
                val diff = bin1 - bin2
                chiSquare += (diff * diff) / sum
            }
        }

        // Convert chi-square distance to similarity (0-1)
        // chiSquare=0 -> similarity=1, chiSquare=2 -> similarity~0
        return (1f / (1f + chiSquare / 2f)).coerceIn(0f, 1f)
    }

    private fun max(a: Float, b: Float): Float = if (a > b) a else b

    /**
     * Color-based pre-filter: flags images that are clearly not gold/silver jewelry.
     * Returns: true if image passes (looks like jewelry), false if it's obviously wrong material
     */
    fun colorPreFilter(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var validJewelryPixels = 0

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val brightness = (r + g + b) / 3f

            // Check if pixel looks like metal (gold, silver, etc)
            val maxRgb = maxOf(r.toFloat(), g.toFloat(), b.toFloat())
            val minRgb = minOf(r.toFloat(), g.toFloat(), b.toFloat())
            val isMetallic = when {
                brightness < 30f -> false // Too dark
                brightness > 245f -> false // Over-exposed
                // High brightness + relatively balanced RGB = metallic
                brightness > 100f && (maxRgb - minRgb) < 50 -> true
                // Warm saturated colors (gold)
                r > g && r > b && brightness > 80f -> true
                // Gray/cool colors (silver)
                abs(r - g) < 20 && abs(g - b) < 20 && brightness > 80f -> true
                else -> false
            }

            if (isMetallic) validJewelryPixels++
        }

        // At least ~10% of image should look metallic (further relaxed)
        return validJewelryPixels > (pixels.size * 0.10f)
    }
}
