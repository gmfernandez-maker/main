package com.gabby.studiowebwrapper.inference

import android.content.Context
import com.gabby.studiowebwrapper.model.GradeResponse
import com.gabby.studiowebwrapper.model.GradeRequest
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.gabby.studiowebwrapper.model.SimilarProduct
import com.gabby.studiowebwrapper.util.ImageUtils
import com.gabby.studiowebwrapper.util.StampOcr
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

/**
 * Lightweight on-device grader. Fast path: uses local heuristics and OCR.
 * This is a client-side-only implementation intended as a drop-in replacement
 * for the previous `NativeRepository.grade()` fallback while we later wire
 * TorchScript inference in-place.
 */
object LocalGrader {
    fun grade(context: Context, request: GradeRequest): GradeResponse? {
        return try {
            val bitmap = runCatching { ImageUtils.decodeDataUri(request.fileDataUri) }.getOrNull()
            val quality = bitmap?.let { ImageUtils.analyzeImageQuality(it) }

            val brightnessScore = quality?.brightnessMean?.let { mean ->
                val distanceFromIdeal = kotlin.math.abs(mean - 128.0)
                (100.0 - (distanceFromIdeal / 1.28)).roundToInt().coerceIn(0, 100)
            } ?: 0
            val contrastScore = quality?.contrastStdDev?.let { stdDev ->
                (stdDev * 2.0).roundToInt().coerceIn(0, 100)
            } ?: 0
            val sharpnessScore = quality?.sharpnessVariance?.let { variance ->
                (variance * 1.5).roundToInt().coerceIn(0, 100)
            } ?: 0

            var yoloScore = ((contrastScore * 0.35f) + (sharpnessScore * 0.65f)).roundToInt().coerceIn(0, 100)
            val lbpScore = ((contrastScore * 0.55f) + (brightnessScore * 0.45f)).roundToInt().coerceIn(0, 100)
            val orbScore = ((sharpnessScore * 0.75f) + (brightnessScore * 0.25f)).roundToInt().coerceIn(0, 100)
            val qualityScore = ((yoloScore + lbpScore + orbScore) / 3).coerceIn(0, 100)

            // Try to run the packaged TorchScript model for a better YOLO proxy
            val detections = try {
                if (com.gabby.studiowebwrapper.util.ModelPreferenceManager.useOnDeviceModel(context) && ModelRunner.loadIfNeeded(context) && bitmap != null) {
                    ModelRunner.runModel(bitmap)
                } else null
            } catch (_: Throwable) { null }

            if (!detections.isNullOrEmpty()) {
                val best = detections.maxByOrNull { it.score }
                if (best != null) {
                    val detScore = (best.score * 100f).toInt().coerceIn(0, 100)
                    yoloScore = (yoloScore + detScore) / 2
                }
            }

            // Try OCR on-device (suspend) using runBlocking for compatibility with sync callsite.
            val stamp = if (bitmap != null) runCatching {
                runBlocking { StampOcr.detectStamp(bitmap) }
            }.getOrNull() else null

            val stampText = stamp?.normalizedStamp
            val stampConfidence = stamp?.confidence ?: 0
            val stampDetected = stamp?.detected ?: false

            val captureSummary = if (quality != null) {
                "On-device grading (brightness=${"%.1f".format(quality.brightnessMean)}, contrast=${"%.1f".format(quality.contrastStdDev)}, sharpness=${"%.1f".format(quality.sharpnessVariance)})"
            } else {
                "On-device grading used; image could not be decoded for quality analysis."
            }

            val similarProducts = listOf(
                SimilarProduct(
                    name = "${if (stampDetected) stampText ?: "Gold" else "Gold-like"} Ring",
                    url = "",
                    price = "N/A",
                    imageUrl = ""
                )
            )

            GradeResponse(
                data = SuggestMetadataOutput(
                    material = "Yellow Gold",
                    purity = if (stampDetected) stampText else "Unknown",
                    stampText = stampText,
                    stampConfidence = stampConfidence,
                    stampDetected = stampDetected,
                    gemstones = null,
                    qualityScore = qualityScore,
                    analysis = captureSummary,
                    similarProducts = similarProducts,
                    yoloScore = yoloScore,
                    lbpScore = lbpScore,
                    orbScore = orbScore,
                    explainability = listOf(
                        "YOLO detection proxy: $yoloScore/100",
                        "LBP texture proxy: $lbpScore/100",
                        "ORB keypoint proxy: $orbScore/100",
                        "Final score uses equal weights: 33.3% YOLO + 33.3% LBP + 33.3% ORB"
                    )
                )
            )
        } catch (t: Throwable) {
            null
        }
    }
}
