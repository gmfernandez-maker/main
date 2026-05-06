package com.gabby.studiowebwrapper.util

import android.content.Context
import android.graphics.Bitmap
import com.gabby.studiowebwrapper.util.ModelVariant
import kotlin.math.max
import kotlin.math.min

data class VanillaPipelineResult(
    val detections: List<Detection>,
    val topScore: ScoreResult?,
    val queryOrbDescriptorRows: Int,
    val queryLbpBins: Int,
    val yoloAvailable: Boolean,
    val yoloStatus: String,
    val graderAvailable: Boolean,
    val graderStatus: String,
    val hasReferences: Boolean,
    val laidDownRefinementApplied: Boolean,
    val notes: List<String>,
    val materialScore: MaterialClassifier.MaterialScore? = null,
    val colorPreFilterPassed: Boolean = true
)

/**
 * Baseline pipeline that can run before references exist.
 *
 * Flow:
 * 1) YOLO detection (if model available)
 * 2) Crop first/top ROI when possible
 * 3) ORB + LBP extraction on ROI
 * 4) Optional grading against references
 */
class VanillaVisionPipeline(
    private val context: Context,
    private val yoloAssetName: String = ""  // Will be set from ModelPreferenceManager if empty
) {
    private val useSpatialLbp = true
    private val spatialGridX = 4
    private val spatialGridY = 4
    private val useClahe = true

    private var yoloAttempted = false
    private var yolo: YoloInference? = null
    private var yoloStatus: String = "Not initialized"
    private var resolvedYoloAssetName: String = ""

    private var graderAttempted = false
    private var grader: Grader? = null
    private var graderStatus: String = "Not initialized"

    private fun ensureYolo(): YoloInference? {
        if (!yoloAttempted) {
            yoloAttempted = true
            
            // Resolve model filename: use preference if not explicitly provided
            val modelName = if (yoloAssetName.isNotEmpty()) {
                yoloAssetName
            } else {
                ModelPreferenceManager.getModelFilename(context)
            }
            resolvedYoloAssetName = modelName
            
            val modelExists = try {
                context.assets.open(modelName).close()
                true
            } catch (_: Throwable) {
                false
            }

            if (!modelExists) {
                yoloStatus = "Model asset missing: $modelName"
                yolo = null
                return yolo
            }

            yolo = try {
                val inference = YoloInference(context, modelName)
                yoloStatus = if (inference.usedFallback) {
                    "Loaded fallback model: ${inference.resolvedAssetName} (requested: $modelName)"
                } else {
                    "Loaded model: ${inference.resolvedAssetName} (${ModelPreferenceManager.getSelectedModel(context).label})"
                }
                inference
            } catch (t: Throwable) {
                yoloStatus = "Model load failed: ${t.javaClass.simpleName}"
                null
            }
        }
        return yolo
    }

    private fun ensureGrader(): Grader? {
        if (!graderAttempted) {
            graderAttempted = true
            grader = try {
                val g = Grader(
                    context = context,
                    useSpatialLbp = useSpatialLbp,
                    spatialGridX = spatialGridX,
                    spatialGridY = spatialGridY,
                    useClahe = useClahe
                )
                graderStatus = "Grader initialized (LBP bins=${g.activeLbpBins()})"
                g
            } catch (t: Throwable) {
                graderStatus = "Grader init failed: ${t.javaClass.simpleName}"
                null
            }
        }
        return grader
    }

    fun analyze(bitmap: Bitmap): VanillaPipelineResult {
        val notes = mutableListOf<String>()
        val detections = detect(bitmap, notes)
        val roi = pickRoi(bitmap, detections)
        val laidDownLikely = isLikelyLaidDown(bitmap, detections)
        val refinedRoi = if (laidDownLikely) {
            notes.add("Scene appears laid-down; applying background-tight refinement for LBP/ORB scoring.")
            tightenCenterCrop(roi, 0.86f)
        } else {
            roi
        }

        // Material classification (color pre-filter + texture-based gold/silver classification)
        val colorPreFilterPassed = MaterialClassifier.colorPreFilter(bitmap)
        var materialScore: MaterialClassifier.MaterialScore? = null
        if (colorPreFilterPassed) {
            try {
                val g = ensureGrader()
                if (g?.hasReferences() == true) {
                    val refDataList = ReferenceManager.loadAll(context)
                    if (refDataList.isNotEmpty()) {
                        materialScore = MaterialClassifier.classify(refinedRoi, refDataList)
                        notes.add("Material classification: ${materialScore.predicted} (gold: ${materialScore.goldScore.toInt()}%, silver: ${materialScore.silverScore.toInt()}%, confidence: ${materialScore.confidence.toInt()}%)")
                    }
                }
            } catch (t: Throwable) {
                notes.add("Material classification unavailable: ${t.javaClass.simpleName}")
            }
        } else {
            notes.add("Color pre-filter: image doesn't look like metallic jewelry.")
        }

        var queryLbpBins = 0
        try {
            queryLbpBins = if (useSpatialLbp) {
                DescriptorUtils.computeSpatialLBPHist(refinedRoi, spatialGridX, spatialGridY, useClahe).size
            } else {
                DescriptorUtils.computeLBPHist(refinedRoi, useClahe).size
            }
        } catch (_: Throwable) {
            notes.add("LBP extraction unavailable on this device/build.")
        }

        var queryOrbRows = 0
        try {
            val queryOrb = DescriptorUtils.orbDescriptorsFromBitmap(refinedRoi, useClahe = useClahe)
            queryOrbRows = if (queryOrb.empty()) 0 else queryOrb.rows()
        } catch (_: Throwable) {
            notes.add("ORB extraction unavailable on this device/build.")
        }

        val g = ensureGrader()
        val graderAvailable = g != null
        val hasReferences = g?.hasReferences() == true
        if (graderAvailable && !hasReferences) {
            graderStatus = "No reference files loaded"
        } else if (graderAvailable && hasReferences) {
            graderStatus = "Ready with references (LBP bins=${g?.activeLbpBins() ?: queryLbpBins})"
        }
        val topScore = if (hasReferences) {
            try {
                val primary = g?.gradeAgainstAll(refinedRoi)?.firstOrNull()
                if (laidDownLikely) {
                    val alt = runCatching {
                        val tighter = tightenCenterCrop(refinedRoi, 0.78f)
                        g?.gradeAgainstAll(tighter)?.firstOrNull()
                    }.getOrNull()
                    listOfNotNull(primary, alt).maxByOrNull { it.finalScore }
                } else {
                    primary
                }
            } catch (_: Throwable) {
                notes.add("Reference grading unavailable (native/OpenCV issue).")
                graderStatus = "Reference grading failed at runtime"
                null
            }
        } else {
            notes.add("References not loaded yet; running extraction-only mode.")
            null
        }

        return VanillaPipelineResult(
            detections = detections,
            topScore = topScore,
            queryOrbDescriptorRows = queryOrbRows,
            queryLbpBins = queryLbpBins,
            yoloAvailable = yolo != null,
            yoloStatus = yoloStatus,
            graderAvailable = graderAvailable,
            graderStatus = graderStatus,
            hasReferences = hasReferences,
            laidDownRefinementApplied = laidDownLikely,
            notes = notes,
            materialScore = materialScore,
            colorPreFilterPassed = colorPreFilterPassed
        )
    }

    private fun isLikelyLaidDown(bitmap: Bitmap, detections: List<Detection>): Boolean {
        val top = detections.maxByOrNull { it.score } ?: return false
        if (top.score < 0.55f || detections.size > 3) return false

        val rect = detectionRectPx(bitmap, top) ?: return false
        val areaRatio = ((rect.right - rect.left).toFloat() * (rect.bottom - rect.top).toFloat()) /
            (bitmap.width.toFloat() * bitmap.height.toFloat()).coerceAtLeast(1f)
        if (areaRatio !in 0.05f..0.75f) return false

        val skinRatio = estimateSkinRatioAroundBox(bitmap, rect.left, rect.top, rect.right, rect.bottom)
        return skinRatio < 0.12f
    }

    private fun tightenCenterCrop(bitmap: Bitmap, keepRatio: Float): Bitmap {
        val ratio = keepRatio.coerceIn(0.5f, 1.0f)
        val targetW = max(16, (bitmap.width * ratio).toInt())
        val targetH = max(16, (bitmap.height * ratio).toInt())
        if (targetW >= bitmap.width || targetH >= bitmap.height) return bitmap

        val left = ((bitmap.width - targetW) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - targetH) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, left, top, targetW, targetH)
    }

    private data class RectPx(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun detectionRectPx(bitmap: Bitmap, detection: Detection): RectPx? {
        val isNormalized = detection.x2 <= 1.5f && detection.y2 <= 1.5f
        val left = (if (isNormalized) detection.x1 * bitmap.width else detection.x1).toInt().coerceIn(0, bitmap.width - 1)
        val top = (if (isNormalized) detection.y1 * bitmap.height else detection.y1).toInt().coerceIn(0, bitmap.height - 1)
        val right = (if (isNormalized) detection.x2 * bitmap.width else detection.x2).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (if (isNormalized) detection.y2 * bitmap.height else detection.y2).toInt().coerceIn(top + 1, bitmap.height)
        if (right <= left || bottom <= top) return null
        return RectPx(left, top, right, bottom)
    }

    private fun estimateSkinRatioAroundBox(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Float {
        val padX = ((right - left) * 0.25f).toInt().coerceAtLeast(2)
        val padY = ((bottom - top) * 0.25f).toInt().coerceAtLeast(2)

        val x0 = (left - padX).coerceAtLeast(0)
        val y0 = (top - padY).coerceAtLeast(0)
        val x1 = (right + padX).coerceAtMost(bitmap.width)
        val y1 = (bottom + padY).coerceAtMost(bitmap.height)

        val width = max(1, x1 - x0)
        val height = max(1, y1 - y0)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, x0, y0, width, height)

        val stepX = max(1, width / 96)
        val stepY = max(1, height / 96)
        var skin = 0
        var total = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val insideBox = x in (left - x0) until (right - x0) && y in (top - y0) until (bottom - y0)
                if (!insideBox) {
                    val p = pixels[y * width + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF

                    val cb = ((-0.168736 * r) - (0.331264 * g) + (0.5 * b) + 128.0)
                    val cr = ((0.5 * r) - (0.418688 * g) - (0.081312 * b) + 128.0)
                    val isSkin = cr in 133.0..173.0 && cb in 77.0..127.0 && r > 40 && g > 20 && b > 10
                    if (isSkin) skin++
                    total++
                }
                x += stepX
            }
            y += stepY
        }

        if (total <= 0) return 0f
        return skin.toFloat() / total.toFloat()
    }

    private fun detect(bitmap: Bitmap, notes: MutableList<String>): List<Detection> {
        val detector = ensureYolo()
        if (detector == null) {
            notes.add("YOLO model not available; detection skipped.")
            return emptyList()
        }
        return try {
            val output = detector.run(bitmap)
            val threshold = when (ModelPreferenceManager.getSelectedModel(context)) {
                // Lower confidence thresholds to be more permissive with detections
                ModelVariant.NANO -> 0.25f
                ModelVariant.SMALL -> 0.30f
            }
            val parsed = detector.parseDetections(output, threshold)
            // Accept all detections regardless of position (center filter removed for better flexibility)
            parsed
        } catch (_: Throwable) {
            notes.add("YOLO inference failed for this frame.")
            emptyList()
        }
    }

    private fun isDetectionCentered(bitmap: Bitmap, detection: Detection, keepRatio: Float): Boolean {
        val rect = detectionRectPx(bitmap, detection) ?: return false
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f

        val ratio = keepRatio.coerceIn(0.3f, 1.0f)
        val marginX = (bitmap.width * (1f - ratio) / 2f)
        val marginY = (bitmap.height * (1f - ratio) / 2f)
        val zoneLeft = marginX
        val zoneTop = marginY
        val zoneRight = bitmap.width - marginX
        val zoneBottom = bitmap.height - marginY

        return cx in zoneLeft..zoneRight && cy in zoneTop..zoneBottom
    }

    private fun pickRoi(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        if (detections.isEmpty()) return bitmap
        val top = detections.maxByOrNull { it.score } ?: return bitmap
        val minRoiSide = 32
        val isNormalized = top.x2 <= 1.5f && top.y2 <= 1.5f

        val left = if (isNormalized) {
            (top.x1 * bitmap.width).toInt()
        } else {
            top.x1.toInt()
        }.coerceIn(0, bitmap.width - 1)

        val topPx = if (isNormalized) {
            (top.y1 * bitmap.height).toInt()
        } else {
            top.y1.toInt()
        }.coerceIn(0, bitmap.height - 1)

        val right = if (isNormalized) {
            (top.x2 * bitmap.width).toInt()
        } else {
            top.x2.toInt()
        }.coerceIn(left + 1, bitmap.width)

        val bottom = if (isNormalized) {
            (top.y2 * bitmap.height).toInt()
        } else {
            top.y2.toInt()
        }.coerceIn(topPx + 1, bitmap.height)

        val roiW = right - left
        val roiH = bottom - topPx
        return if (roiW >= minRoiSide && roiH >= minRoiSide) {
            Bitmap.createBitmap(bitmap, left, topPx, roiW, roiH)
        } else {
            bitmap
        }
    }
}