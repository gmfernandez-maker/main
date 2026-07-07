package com.gabby.studiowebwrapper.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.gabby.studiowebwrapper.model.YoloDetectionOutput
import com.gabby.studiowebwrapper.util.YoloLabels
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import kotlin.math.max
import kotlin.math.min
import java.io.File
import java.io.FileOutputStream

object ModelRunner {
    private const val TAG = "ModelRunner"
    private const val MODEL_ASSET_NAME = "weights.torchscript"
    private const val INPUT_SIZE = 640
    private const val CONF_THRESHOLD = 0.25f
    private var module: Module? = null

    fun isLoaded(): Boolean = module != null

    fun loadIfNeeded(context: Context): Boolean {
        if (module != null) return true
        return try {
            val assetName = if (com.gabby.studiowebwrapper.util.ModelPreferenceManager.useQuantizedModel(context) &&
                assetExists(context, "weights_quant.torchscript")
            ) "weights_quant.torchscript" else MODEL_ASSET_NAME

            val file = File(context.filesDir, assetName)
            val t0 = System.nanoTime()
            context.assets.open(assetName).use { input ->
                FileOutputStream(file, false).use { out -> input.copyTo(out) }
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
     * - YOLOv8 TorchScript detect head: [1, 4+C, N] or [4+C, N].
     * - Row-major fallback: [N, 4+C] or legacy [N, 5+C].
     * Returns list of YoloDetectionOutput in normalized coordinates [0,1].
     */
    private fun parseModelOutput(shape: LongArray, data: FloatArray): List<YoloDetectionOutput> {
        if (shape.isEmpty()) {
            Log.w(TAG, "Empty output shape")
            return emptyList()
        }

        if (shape.size == 3 && shape[0] == 1L) {
            val dim1 = shape[1].toInt()
            val dim2 = shape[2].toInt()
            return when {
                dim1 >= 5 && dim2 > dim1 -> parseYoloChannelMajor(dim1, dim2, data)
                dim2 >= 5 -> parseYoloRows(dim1, dim2, data)
                else -> emptyList()
            }
        }

        if (shape.size == 2) {
            val rows = shape[0].toInt()
            val cols = shape[1].toInt()
            if (rows == 0 || cols < 5) {
                Log.w(TAG, "Invalid shape: rows=$rows, cols=$cols (need rows>0, cols>=5)")
                return emptyList()
            }
            return if (rows >= 5 && rows <= 128 && cols > rows) {
                parseYoloChannelMajor(rows, cols, data)
            } else {
                parseYoloRows(rows, cols, data)
            }
        }

        if (shape.size == 1) {
            val channels = 4 + YoloLabels.classNames.size
            if (data.size % channels == 0) {
                return parseYoloChannelMajor(channels, data.size / channels, data)
            }
            if (data.size % 6 == 0) {
                return parseYoloRows(data.size / 6, 6, data)
            }
        }

        Log.w(TAG, "Output shape ${shape.contentToString()} is unsupported")
        return emptyList()
    }

    private fun parseYoloChannelMajor(channels: Int, predictions: Int, data: FloatArray): List<YoloDetectionOutput> {
        val detections = mutableListOf<YoloDetectionOutput>()
        val classCount = min(YoloLabels.classNames.size, channels - 4)
        if (classCount <= 0 || data.size < channels * predictions) return emptyList()

        for (index in 0 until predictions) {
            val cx = data[index]
            val cy = data[predictions + index]
            val width = data[(2 * predictions) + index]
            val height = data[(3 * predictions) + index]

            var bestClass = -1
            var bestScore = 0f
            for (classIndex in 0 until classCount) {
                val score = data[((4 + classIndex) * predictions) + index]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
            }

            addDetection(detections, cx, cy, width, height, bestScore, bestClass)
        }
        return detections
    }

    private fun parseYoloRows(rows: Int, attrs: Int, data: FloatArray): List<YoloDetectionOutput> {
        val detections = mutableListOf<YoloDetectionOutput>()
        val yoloV8ClassCount = min(YoloLabels.classNames.size, attrs - 4)
        val legacyClassCount = min(YoloLabels.classNames.size, attrs - 5)
        if (data.size < rows * attrs) return emptyList()

        for (row in 0 until rows) {
            val base = row * attrs
            val cx = data[base]
            val cy = data[base + 1]
            val width = data[base + 2]
            val height = data[base + 3]

            var bestClass = -1
            var bestScore = 0f
            if (attrs == 4 + YoloLabels.classNames.size && yoloV8ClassCount > 0) {
                for (classIndex in 0 until yoloV8ClassCount) {
                    val score = data[base + 4 + classIndex]
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = classIndex
                    }
                }
            } else if (legacyClassCount > 0) {
                val objectness = data[base + 4].coerceIn(0f, 1f)
                for (classIndex in 0 until legacyClassCount) {
                    val score = objectness * data[base + 5 + classIndex]
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = classIndex
                    }
                }
            }

            addDetection(detections, cx, cy, width, height, bestScore, bestClass)
        }
        return detections
    }

    private fun addDetection(
        detections: MutableList<YoloDetectionOutput>,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        score: Float,
        classId: Int
    ) {
        if (score < CONF_THRESHOLD || score > 1.2f || classId !in YoloLabels.classNames.indices) return
        if (width <= 0f || height <= 0f) return

        val normalized = cx <= 1.5f && cy <= 1.5f && width <= 1.5f && height <= 1.5f
        val scale = if (normalized) 1f else INPUT_SIZE.toFloat()
        val x1 = ((cx - width / 2f) / scale).coerceIn(0f, 1f)
        val y1 = ((cy - height / 2f) / scale).coerceIn(0f, 1f)
        val x2 = ((cx + width / 2f) / scale).coerceIn(0f, 1f)
        val y2 = ((cy + height / 2f) / scale).coerceIn(0f, 1f)
        if (x2 <= x1 || y2 <= y1) return

        detections.add(
            YoloDetectionOutput(
                classId = classId,
                score = score.coerceIn(0f, 1f),
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2
            )
        )
    }

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
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val values = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        for (index in pixels.indices) {
            val px = pixels[index]
            values[index] = ((px shr 16) and 0xFF) / 255.0f
            values[plane + index] = ((px shr 8) and 0xFF) / 255.0f
            values[(2 * plane) + index] = (px and 0xFF) / 255.0f
        }

        return Tensor.fromBlob(values, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
    }

    private fun assetExists(context: Context, assetName: String): Boolean {
        return try {
            context.assets.open(assetName).use { }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
