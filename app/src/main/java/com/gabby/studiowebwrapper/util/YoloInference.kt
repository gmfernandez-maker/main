package com.gabby.studiowebwrapper.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class Detection(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float, val classId: Int)

/**
 * Lightweight YOLOv8 inference helper for PyTorch Mobile.
 *
 * Notes:
 * - Assumes a TorchScript model is exported to `app/src/main/assets/` and copied to internal storage.
 * - The exported model should accept a FloatTensor [1,3,H,W] and output a 2D float tensor of detections.
 * - The parser below uses a heuristic (6 values per detection: x1,y1,x2,y2,score,class).
 */
class YoloInference(
    private val context: Context,
    assetFileName: String = "yolov8n_ts.pt",
    private val inputSize: Int = 640,
    private val confThreshold: Float = 0.25f
) {

    private val module: Module
    val resolvedAssetName: String
    val usedFallback: Boolean

    init {
        var fallbackUsed = false
        var resolvedName = assetFileName
        module = try {
            val modelPath = assetFilePath(context, assetFileName)
            Module.load(modelPath)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load $assetFileName, falling back to yolov8n_ts.pt", t)
            fallbackUsed = true
            resolvedName = "yolov8n_ts.pt"
            val fallbackPath = assetFilePath(context, "yolov8n_ts.pt")
            Module.load(fallbackPath)
        }
        resolvedAssetName = resolvedName
        usedFallback = fallbackUsed
    }

    companion object {
        private const val TAG = "YoloInference"

        fun assetFilePath(context: Context, assetName: String): String {
            val file = File(context.filesDir, assetName)
            try {
                context.assets.open(assetName).use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                throw RuntimeException("Error while copying asset $assetName to file", e)
            }
            return file.absolutePath
        }
    }

    private fun bitmapToFloat32Tensor(bitmap: Bitmap): Tensor {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val width = resized.width
        val height = resized.height
        val floatArray = FloatArray(1 * 3 * width * height)
        val pixels = IntArray(width * height)
        resized.getPixels(pixels, 0, width, 0, 0, width, height)
        val rOffset = 0
        val gOffset = width * height
        val bOffset = gOffset + width * height
        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = pixels[idx++]
                val r = ((px shr 16) and 0xFF) / 255.0f
                val g = ((px shr 8) and 0xFF) / 255.0f
                val b = (px and 0xFF) / 255.0f
                floatArray[rOffset + y * width + x] = r
                floatArray[gOffset + y * width + x] = g
                floatArray[bOffset + y * width + x] = b
            }
        }
        return Tensor.fromBlob(floatArray, longArrayOf(1, 3, height.toLong(), width.toLong()))
    }

    /**
     * Run the model on a Bitmap and return the raw output float array.
     */
    fun run(bitmap: Bitmap): FloatArray {
        val inputTensor = bitmapToFloat32Tensor(bitmap)
        val output = module.forward(IValue.from(inputTensor)).toTensor()
        return output.dataAsFloatArray
    }

    /**
    * Parse detections from supported TorchScript output formats.
    *
    * Supported:
    * - Ultralytics detect head output flattened from [1,84,N]: [x,y,w,h,cls0..cls79]
     */
    fun parseDetections(output: FloatArray, confThreshold: Float = this.confThreshold): List<Detection> {
        if (output.isEmpty()) return emptyList()

        // Log raw tensor data
        Log.d(TAG, "=== RAW TENSOR DEBUG ===")
        Log.d(TAG, "outputSize=${output.size}")
        Log.d(TAG, "First 20 values: ${output.take(20).joinToString(", ") { String.format("%.4f", it) }}")
        Log.d(TAG, "confThreshold=$confThreshold")

        // Ultralytics TorchScript detect export usually flattens [1,(4+nc),N].
        val classChannels = 4 + YoloLabels.classNames.size
        val channelCandidates = linkedSetOf(classChannels, 8, 84)
        var bestDetections: List<Detection> = emptyList()
        var bestScore = Float.NEGATIVE_INFINITY
        for (numChannels in channelCandidates) {
            if (numChannels <= 4 || output.size % numChannels != 0) continue
            
            Log.d(TAG, "\nTesting numChannels=$numChannels (numPredictions=${output.size / numChannels})")
            
            val channelMajor = parseYoloChannels(output, confThreshold, numChannels)
            val rowMajor = parseYoloRows(output, confThreshold, numChannels)

            val channelScore = scoreLayoutQuality(channelMajor)
            val rowScore = scoreLayoutQuality(rowMajor)
            
            Log.d(TAG, "Layout scores: channel-major=${String.format("%.1f", channelScore)} (${channelMajor.size} dets), row-major=${String.format("%.1f", rowScore)} (${rowMajor.size} dets)")

            val chosenScore = if (rowScore > channelScore) rowScore else channelScore
            val chosen = if (rowScore > channelScore) rowMajor else channelMajor
            val chosenLayout = if (chosen === rowMajor) "ROW-MAJOR" else "CHANNEL-MAJOR"
            
            // Only update if this layout has a better score AND is not hard-rejected.
            if (chosenScore > bestScore && chosenScore > 0) {
                bestDetections = chosen
                bestScore = chosenScore
                Log.d(TAG, "New best: $chosenLayout layout with ${chosen.size} detections (score=${String.format("%.1f", chosenScore)})")
            }

            if (chosen.isNotEmpty() && chosenScore > 0) {
                val topDet = chosen.maxByOrNull { it.score }
                Log.d(TAG, "Top detection: classId=${topDet?.classId} score=${String.format("%.4f", topDet?.score)} box=${topDet?.x1?.toInt()},${topDet?.y1?.toInt()},${topDet?.x2?.toInt()},${topDet?.y2?.toInt()}")
            }
        }

        if (bestDetections.isNotEmpty()) {
            val nmsResult = nonMaxSuppression(bestDetections, iouThreshold = 0.45f, maxDet = 100)
            Log.d(TAG, "After NMS: ${nmsResult.size} detections remain")
            if (!isReasonableDetectionSet(nmsResult)) {
                Log.w(TAG, "Rejecting suspicious detection set and returning no detections")
                Log.d(TAG, "=== END RAW TENSOR DEBUG ===\n")
                return emptyList()
            }
            if (nmsResult.isNotEmpty()) {
                nmsResult.forEachIndexed { idx, det ->
                    Log.d(TAG, "  Det$idx: class=${YoloLabels.labelForClassId(det.classId)} score=${String.format("%.4f", det.score)} box=(${det.x1.toInt()},${det.y1.toInt()},${det.x2.toInt()},${det.y2.toInt()})")
                }
            }
            Log.d(TAG, "=== END RAW TENSOR DEBUG ===\n")
            return nmsResult
        }

        Log.d(TAG, "No valid YOLO-style detections found")
        Log.d(TAG, "=== END RAW TENSOR DEBUG ===\n")
        return emptyList()
    }

    private fun parseYoloChannels(output: FloatArray, confThreshold: Float, numChannels: Int): List<Detection> {
        val numPred = output.size / numChannels
        val detections = ArrayList<Detection>(numPred.coerceAtMost(512))

        if (numPred <= 5) {
            // Log details for small predictions
            Log.d(TAG, "  [CHANNEL-MAJOR] Testing with numPred=$numPred, numChannels=$numChannels")
            for (i in 0 until numPred) {
                val cx = output[i]
                val cy = output[numPred + i]
                val w = output[(2 * numPred) + i]
                val h = output[(3 * numPred) + i]
                Log.d(TAG, "    Pred$i: cx=${String.format("%.4f", cx)} cy=${String.format("%.4f", cy)} w=${String.format("%.4f", w)} h=${String.format("%.4f", h)}")
            }
        }

        for (i in 0 until numPred) {
            val cx = output[i]
            val cy = output[numPred + i]
            val w = output[(2 * numPred) + i]
            val h = output[(3 * numPred) + i]

            var bestClass = -1
            var bestScore = 0f
            var c = 4
            while (c < numChannels) {
                val score = output[(c * numPred) + i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c - 4
                }
                c++
            }

            if (bestScore < confThreshold || bestScore > 1.2f || bestClass !in 0 until YoloLabels.classNames.size) continue

            val x1 = (cx - (w / 2f)).coerceAtLeast(0f)
            val y1 = (cy - (h / 2f)).coerceAtLeast(0f)
            val x2 = (cx + (w / 2f)).coerceAtMost(inputSize.toFloat())
            val y2 = (cy + (h / 2f)).coerceAtMost(inputSize.toFloat())
            if (x2 <= x1 || y2 <= y1) continue

            detections.add(Detection(x1, y1, x2, y2, bestScore, bestClass))
        }

        return detections
    }

    private fun scoreLayoutQuality(detections: List<Detection>): Float {
        if (detections.isEmpty()) return 0f
        
        // Hard rejection: if any detection has a wildly unreasonable score
        if (detections.any { it.score > 2f }) {
            Log.d(TAG, "  ⚠ Layout rejected: unreasonable scores detected (max=${detections.maxOf { it.score }})")
            return -10000f
        }
        
        // Count valid detections (reasonable score + valid classId)
        val validDetections = detections.filter { det ->
            det.score >= 0f && det.score <= 1.5f &&  // Reasonable confidence range
            det.classId >= 0 && det.classId < YoloLabels.classNames.size  // Valid class
        }
        
        // Hard rejection: if >50% of detections are invalid, this layout is bad
        val invalidRatio = 1f - (validDetections.size.toFloat() / detections.size)
        if (invalidRatio > 0.5f) {
            Log.d(TAG, "  ⚠ Layout rejected: too many invalid detections (${detections.size} total, ${validDetections.size} valid, ratio=${String.format("%.1f", invalidRatio * 100)}%)")
            return -10000f
        }
        
        if (validDetections.isEmpty()) return -10000f
        
        // Score only the valid detections
        var score = 0f
        for (d in validDetections) {
            if (d.score in 0f..1.2f) score += 2f
            if (d.x1 in 0f..inputSize.toFloat() && d.y1 in 0f..inputSize.toFloat()) score += 1f
            if (d.x2 in 0f..inputSize.toFloat() && d.y2 in 0f..inputSize.toFloat()) score += 1f
            if (d.x2 > d.x1 && d.y2 > d.y1) score += 1f
            score += 1f  // Valid classId already checked above
        }
        
        // Bonus for high validity ratio: prefer layouts with ALL detections valid
        score *= (validDetections.size.toFloat() / detections.size)
        
        // Bonus for finding valid detections (but keep it smaller than per-detection scores)
        if (validDetections.isNotEmpty()) score += 50f
        
        return score
    }

    private fun parseYoloRows(output: FloatArray, confThreshold: Float, numChannels: Int): List<Detection> {
        val numPred = output.size / numChannels
        val detections = ArrayList<Detection>(numPred.coerceAtMost(512))

        if (numPred <= 5) {
            // Log details for small predictions
            Log.d(TAG, "  [ROW-MAJOR] Testing with numPred=$numPred, numChannels=$numChannels")
            for (i in 0 until numPred) {
                val base = i * numChannels
                val cx = output[base]
                val cy = output[base + 1]
                val w = output[base + 2]
                val h = output[base + 3]
                Log.d(TAG, "    Pred$i: cx=${String.format("%.4f", cx)} cy=${String.format("%.4f", cy)} w=${String.format("%.4f", w)} h=${String.format("%.4f", h)}")
            }
        }

        for (i in 0 until numPred) {
            val base = i * numChannels
            val cx = output[base]
            val cy = output[base + 1]
            val w = output[base + 2]
            val h = output[base + 3]

            var bestClass = -1
            var bestScore = 0f
            var c = 4
            while (c < numChannels) {
                val score = output[base + c]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c - 4
                }
                c++
            }

            if (bestScore < confThreshold || bestScore > 1.2f || bestClass !in 0 until YoloLabels.classNames.size) continue

            val x1 = (cx - (w / 2f)).coerceAtLeast(0f)
            val y1 = (cy - (h / 2f)).coerceAtLeast(0f)
            val x2 = (cx + (w / 2f)).coerceAtMost(inputSize.toFloat())
            val y2 = (cy + (h / 2f)).coerceAtMost(inputSize.toFloat())
            if (x2 <= x1 || y2 <= y1) continue

            detections.add(Detection(x1, y1, x2, y2, bestScore, bestClass))
        }

        return detections
    }

    private fun nonMaxSuppression(detections: List<Detection>, iouThreshold: Float, maxDet: Int): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val byClass = detections.groupBy { it.classId }
        val kept = mutableListOf<Detection>()

        for ((_, clsDetections) in byClass) {
            val sorted = clsDetections.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty() && kept.size < maxDet) {
                val best = sorted.removeAt(0)
                kept.add(best)
                val iter = sorted.iterator()
                while (iter.hasNext()) {
                    val other = iter.next()
                    if (iou(best, other) > iouThreshold) {
                        iter.remove()
                    }
                }
            }
        }

        return kept.sortedByDescending { it.score }.take(maxDet)
    }

    private fun isReasonableDetectionSet(detections: List<Detection>): Boolean {
        if (detections.isEmpty()) return false
        // Allow up to 30 detections before rejecting (was 20)
        if (detections.size > 30) return false

        // Require at least one moderately confident detection (lowered from 0.30)
        val hasStrongDetection = detections.any { it.score >= 0.25f }
        if (!hasStrongDetection) return false

        return detections.all { det ->
            det.score in 0f..1.2f &&
                det.classId in 0 until YoloLabels.classNames.size &&
                det.x2 > det.x1 &&
                det.y2 > det.y1 &&
                det.x1 >= 0f && det.y1 >= 0f &&
                det.x2 <= inputSize.toFloat() * 1.05f &&
                det.y2 <= inputSize.toFloat() * 1.05f &&
                // Allow very small boxes (down to ~0.00005 of model area) to handle small centered rings
                (((det.x2 - det.x1) * (det.y2 - det.y1)) / (inputSize.toFloat() * inputSize.toFloat())) in 0.00005f..0.95f
        }
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = maxOf(a.x1, b.x1)
        val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2)
        val iy2 = minOf(a.y2, b.y2)

        val iw = (ix2 - ix1).coerceAtLeast(0f)
        val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f

        val areaA = (a.x2 - a.x1).coerceAtLeast(0f) * (a.y2 - a.y1).coerceAtLeast(0f)
        val areaB = (b.x2 - b.x1).coerceAtLeast(0f) * (b.y2 - b.y1).coerceAtLeast(0f)
        val union = areaA + areaB - inter
        if (union <= 0f) return 0f

        return inter / union
    }
}
