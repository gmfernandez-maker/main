package com.gabby.studiowebwrapper.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import com.gabby.studiowebwrapper.model.YoloDetectionOutput
import kotlin.math.max
import kotlin.math.min
import java.io.File
import java.io.FileOutputStream

object ModelRunner {
    private const val TAG = "ModelRunner"
    private const val MODEL_ASSET_NAME = "weights.torchscript"
    private var module: Module? = null

    fun isLoaded(): Boolean = module != null

    fun loadIfNeeded(context: Context): Boolean {
        if (module != null) return true
        return try {
            val assetName = if (com.gabby.studiowebwrapper.util.ModelPreferenceManager.useQuantizedModel(context) &&
                try { context.assets.open("weights_quant.torchscript"); true } catch (_: Throwable) { false }
            ) "weights_quant.torchscript" else MODEL_ASSET_NAME

            val file = File(context.filesDir, assetName)
            val t0 = System.nanoTime()
            if (!file.exists()) {
                context.assets.open(assetName).use { input ->
                    FileOutputStream(file).use { out -> input.copyTo(out) }
                }
            }
            module = Module.load(file.absolutePath)
            // Configure runtime threads
            val threads = com.gabby.studiowebwrapper.util.ModelPreferenceManager.getNumThreads(context)
            try { org.pytorch.PyTorchAndroid.setNumThreads(threads) } catch (_: Throwable) {}
            lastLoadMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "Model loaded from ${file.absolutePath} (load=${lastLoadMs}ms) threads=$threads")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load model: ${t.message}", t)
            module = null
            false
        }
    }

    fun runModel(bitmap: Bitmap): List<YoloDetectionOutput>? {
        val mod = module ?: return null
        return try {
            val input = preprocess(bitmap)
            val t0 = System.nanoTime()
            val output = mod.forward(IValue.from(input)).toTensor()
            lastInferenceMs = (System.nanoTime() - t0) / 1_000_000
            val shape = output.shape()
            val data = output.dataAsFloatArray

            Log.d(TAG, "Raw output shape: ${shape.contentToString()}, size: ${data.size}")

            // Try to parse output in various formats
            val detections = parseModelOutput(shape, data)
            if (detections.isEmpty()) {
                Log.w(TAG, "No detections parsed from model output")
                return emptyList()
            }

            // Apply NMS
            val nms = nonMaxSuppression(detections, 0.45f, 0.25f)
            Log.d(TAG, "Detections after NMS: ${nms.size}")
            return nms
        } catch (t: Throwable) {
            Log.w(TAG, "Model inference failed: ${t.message}", t)
            null
        }
    }

    /**
     * Parse model output in multiple possible formats:
     * - Format 1 (YOLOv8 standard): [N, 6+C] where [cx,cy,w,h,obj_conf,cls_scores...]
     * - Format 2 (Corner coords): [N, 6+C] where [x1,y1,x2,y2,obj_conf,cls_scores...]
     * - Format 3 (Multi-head): [N, 6] + [N, C] (detections + classes)
     * Returns list of YoloDetectionOutput in normalized coordinates [0,1].
     */
    private fun parseModelOutput(shape: LongArray, data: FloatArray): List<YoloDetectionOutput> {
        if (shape.isEmpty()) {
            Log.w(TAG, "Empty output shape")
            return emptyList()
        }

        // Common case: 2D output [N, attrs]
        if (shape.size == 2) {
            val rows = shape[0].toInt()
            val cols = shape[1].toInt()
            if (rows == 0 || cols < 5) {
                Log.w(TAG, "Invalid shape: rows=$rows, cols=$cols (need rows>0, cols>=5)")
                return emptyList()
            }
            return parseDetections2D(rows, cols, data)
        }

        // Fallback: if shape is 1D or 3D, attempt reshape or log warning
        Log.w(TAG, "Output shape ${shape.contentToString()} not standard 2D; attempting fallback")
        if (shape.size == 1) {
            // Try to reshape as [N, 6] with N = data.size / 6
            val cols = 6
            val rows = (data.size + cols - 1) / cols
            if (rows > 0 && data.size >= cols) {
                return parseDetections2D(rows, cols, data)
            }
        }
        return emptyList()
    }

    private fun parseDetections2D(rows: Int, cols: Int, data: FloatArray): List<YoloDetectionOutput> {
        val detections = mutableListOf<YoloDetectionOutput>()
        val modelSize = 640f

        for (r in 0 until rows) {
            val base = r * cols
            if (base + 4 >= data.size) break

            // Try to infer coordinate format by heuristics:
            // If first two values are small (< 100), likely normalized or center coords
            // If values are large (> 100), likely absolute pixel coords
            val v0 = data[base]
            val v1 = data[base + 1]
            val v2 = data[base + 2]
            val v3 = data[base + 3]
            val obj = data[base + 4]

            if (obj <= 0f) continue // Skip low-confidence

            val (x1, y1, x2, y2) = try {
                when {
                    // Format 1: Center coords [cx, cy, w, h, ...]
                    // Assume center coords if all values are within reasonable range
                    v0 > 0 && v0 < modelSize && v1 > 0 && v1 < modelSize &&
                    v2 > 0 && v2 < modelSize && v3 > 0 && v3 < modelSize -> {
                        val cx = v0
                        val cy = v1
                        val w = v2
                        val h = v3
                        val x1 = (cx - w / 2f) / modelSize
                        val y1 = (cy - h / 2f) / modelSize
                        val x2 = (cx + w / 2f) / modelSize
                        val y2 = (cy + h / 2f) / modelSize
                        Tuple4(x1, y1, x2, y2)
                    }
                    // Format 2: Corner coords [x1, y1, x2, y2, ...]
                    // If first two < third/fourth, likely corner format
                    v0 < v2 && v1 < v3 -> {
                        val x1 = (v0 / modelSize).coerceIn(0f, 1f)
                        val y1 = (v1 / modelSize).coerceIn(0f, 1f)
                        val x2 = (v2 / modelSize).coerceIn(0f, 1f)
                        val y2 = (v3 / modelSize).coerceIn(0f, 1f)
                        Tuple4(x1, y1, x2, y2)
                    }
                    // Format 3: Normalized center coords [cx, cy, w, h] already in [0,1]
                    v0 <= 1f && v1 <= 1f && v2 <= 1f && v3 <= 1f -> {
                        val cx = v0
                        val cy = v1
                        val w = v2
                        val h = v3
                        val x1 = cx - w / 2f
                        val y1 = cy - h / 2f
                        val x2 = cx + w / 2f
                        val y2 = cy + h / 2f
                        Tuple4(x1, y1, x2, y2)
                    }
                    else -> {
                        // Default fallback: treat as center coords
                        Log.d(TAG, "Row $r: ambiguous coords [$v0,$v1,$v2,$v3]; assuming center format")
                        val cx = v0 / modelSize
                        val cy = v1 / modelSize
                        val w = v2 / modelSize
                        val h = v3 / modelSize
                        val x1 = cx - w / 2f
                        val y1 = cy - h / 2f
                        val x2 = cx + w / 2f
                        val y2 = cy + h / 2f
                        Tuple4(x1, y1, x2, y2)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing coords at row $r: ${e.message}")
                continue
            }

            // Extract class scores
            var bestCls = 0
            var bestClsScore = 0f
            if (cols > 5) {
                for (c in 5 until cols) {
                    val clsScore = data[base + c]
                    if (clsScore > bestClsScore) {
                        bestClsScore = clsScore
                        bestCls = c - 5
                    }
                }
            }

            val score = obj * bestClsScore
            if (score <= 0f) continue

            detections.add(
                YoloDetectionOutput(
                    classId = bestCls,
                    score = score,
                    x1 = x1.coerceIn(0f, 1f),
                    y1 = y1.coerceIn(0f, 1f),
                    x2 = x2.coerceIn(0f, 1f),
                    y2 = y2.coerceIn(0f, 1f)
                )
            )
        }
        return detections
    }

    // Simple data class for tuple return
    private data class Tuple4(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private var lastLoadMs: Long = 0
    private var lastInferenceMs: Long = 0

    fun getLastLoadMs(): Long = lastLoadMs
    fun getLastInferenceMs(): Long = lastInferenceMs

    private fun nonMaxSuppression(detections: List<YoloDetectionOutput>, iouThreshold: Float, confThreshold: Float): List<YoloDetectionOutput> {
        val byClass = detections.groupBy { it.classId }
        val out = mutableListOf<YoloDetectionOutput>()
        for ((_, dets) in byClass) {
            val sorted = dets.filter { it.score >= confThreshold }.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val current = sorted.removeAt(0)
                out.add(current)
                val it = sorted.iterator()
                while (it.hasNext()) {
                    val other = it.next()
                    val iou = boxIou(current, other)
                    if (iou > iouThreshold) it.remove()
                }
            }
        }
        return out
    }

    private fun boxIou(a: YoloDetectionOutput, b: YoloDetectionOutput): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)
        val interArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = max(0f, a.x2 - a.x1) * max(0f, a.y2 - a.y1)
        val areaB = max(0f, b.x2 - b.x1) * max(0f, b.y2 - b.y1)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    private fun preprocess(bitmap: Bitmap): Tensor {
        // Resize to 640x640 (or model expected size). Adjust if your model uses different dims.
        val size = 640
        val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        val sx = size.toFloat() / bitmap.width
        val sy = size.toFloat() / bitmap.height
        matrix.setScale(sx, sy)
        val canvas = android.graphics.Canvas(scaled)
        canvas.drawBitmap(bitmap, matrix, null)

        // Use torchvision helper to create tensor normalized to [0,1]
        return TensorImageUtils.bitmapToFloat32Tensor(scaled, TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB)
    }
}
