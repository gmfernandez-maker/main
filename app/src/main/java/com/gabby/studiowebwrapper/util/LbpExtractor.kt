package com.gabby.studiowebwrapper.util

import android.graphics.Bitmap

/**
 * LBP (Local Binary Pattern) histogram extraction.
 * Used for texture-based similarity matching.
 */
object LbpExtractor {

    /**
     * Extract LBP histogram from a bitmap image.
     * Returns a normalized 256-bin histogram as FloatArray.
     */
    fun extractLbpHistogram(bitmap: Bitmap): FloatArray {
        // For simplicity, use a basic feature extraction approach
        // In production, use OpenCV's xfeatures2d::LBP if available
        
        // Simplified LBP: compute directly from bitmap pixels
        val histogram = FloatArray(256)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Compute LBP codes
        for (i in 0 until bitmap.height) {
            for (j in 0 until bitmap.width) {
                // Skip borders (need 3x3 neighborhood)
                if (i == 0 || i == bitmap.height - 1 || j == 0 || j == bitmap.width - 1) continue
                
                val centerPixel = pixels[i * bitmap.width + j]
                val centerGray = computeGray(centerPixel)
                
                // Extract 8 neighbors
                var lbpCode = 0
                val neighbors = intArrayOf(
                    pixels[(i - 1) * bitmap.width + (j - 1)], // TL
                    pixels[(i - 1) * bitmap.width + j],       // T
                    pixels[(i - 1) * bitmap.width + (j + 1)], // TR
                    pixels[i * bitmap.width + (j + 1)],       // R
                    pixels[(i + 1) * bitmap.width + (j + 1)], // BR
                    pixels[(i + 1) * bitmap.width + j],       // B
                    pixels[(i + 1) * bitmap.width + (j - 1)], // BL
                    pixels[i * bitmap.width + (j - 1)]        // L
                )
                
                for (k in neighbors.indices) {
                    val neighborGray = computeGray(neighbors[k])
                    if (neighborGray >= centerGray) {
                        lbpCode = lbpCode or (1 shl k)
                    }
                }
                
                val idx = lbpCode
                histogram[idx] = histogram[idx] + 1.0f
            }
        }
        
        // Normalize histogram
        val totalPixels = (bitmap.width - 2) * (bitmap.height - 2)
        val divisor = totalPixels.toFloat()
        for (i in histogram.indices) {
            histogram[i] = histogram[i] / divisor
        }
        
        return histogram
    }

    private fun computeGray(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b).toInt()
    }
}
