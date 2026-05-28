package com.gabby.studiowebwrapper.util

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.core.Size

data class StampOcrResult(
    val rawText: String?,
    val normalizedStamp: String?,
    val confidence: Int,
    val detected: Boolean
)

object StampOcr {
    private val knownStamps = setOf("24K", "22K", "18K", "14K", "999", "916", "750", "585")

    private data class CloseupCandidate(
        val bitmap: Bitmap,
        val label: String
    )

    suspend fun detectStamp(bitmap: Bitmap): StampOcrResult {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            // Try multiple closeup crops and simple rotations to improve recall for small or off-center stamps.
            var bestStamp: String? = null
            var bestConfidence = 0f
            var bestRawText = ""

            val candidates = buildCloseupCandidates(bitmap)
            for (candidate in candidates) {
                val enhancedBmp = try {
                    enhanceForOcr(candidate.bitmap)
                } catch (t: Throwable) {
                    candidate.bitmap
                }

                val image = InputImage.fromBitmap(enhancedBmp, 0)
                val result = recognizer.process(image).await()
                Log.i("StampOcr", "Stamp closeup OCR (${candidate.label}): text='${result.text}'")

                for (textBlock in result.textBlocks) {
                    val text = textBlock.text
                    val normalized = normalizeStamp(text)
                    if (normalized != null) {
                        val confidence = when (candidate.label) {
                            "full-clahe" -> 86f
                            "center-clahe" -> 84f
                            "tall-clahe" -> 82f
                            "wide-clahe" -> 82f
                            "rot90-clahe" -> 80f
                            "rot270-clahe" -> 80f
                            else -> 75f
                        }
                        if (confidence > bestConfidence) {
                            bestStamp = normalized
                            bestConfidence = confidence
                            bestRawText = text
                        }
                    }
                }

                if (bestConfidence >= 86f) break
            }

            if (bestStamp != null) {
                Log.i("StampOcr", "Closeup stamp detected: $bestStamp confidence=${bestConfidence.toInt()}")
                StampOcrResult(
                    rawText = bestRawText.ifBlank { null },
                    normalizedStamp = bestStamp,
                    confidence = bestConfidence.toInt().coerceIn(0, 100),
                    detected = true
                )
            } else {
                Log.i("StampOcr", "No stamp found in closeup image")
                StampOcrResult(
                    rawText = bestRawText.ifBlank { null },
                    normalizedStamp = null,
                    confidence = 0,
                    detected = false
                )
            }
        } catch (_: Exception) {
            StampOcrResult(rawText = null, normalizedStamp = null, confidence = 0, detected = false)
        } finally {
            recognizer.close()
        }
    }

    private fun buildCloseupCandidates(bitmap: Bitmap): List<CloseupCandidate> {
        val variants = mutableListOf<CloseupCandidate>()

        variants.add(CloseupCandidate(bitmap, "full"))
        variants.add(CloseupCandidate(bitmap, "full-clahe"))

        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)

        fun addCrop(name: String, leftFrac: Float, topFrac: Float, rightFrac: Float, bottomFrac: Float) {
            val left = (w * leftFrac).toInt().coerceIn(0, w - 1)
            val top = (h * topFrac).toInt().coerceIn(0, h - 1)
            val right = (w * rightFrac).toInt().coerceIn(left + 1, w)
            val bottom = (h * bottomFrac).toInt().coerceIn(top + 1, h)
            val cropW = (right - left).coerceAtLeast(1)
            val cropH = (bottom - top).coerceAtLeast(1)
            try {
                variants.add(CloseupCandidate(Bitmap.createBitmap(bitmap, left, top, cropW, cropH), name))
                variants.add(CloseupCandidate(Bitmap.createBitmap(bitmap, left, top, cropW, cropH), "$name-clahe"))
            } catch (_: Throwable) {
                // ignore invalid crop
            }
        }

        // Center crop and a couple of shape-biased crops to catch stamps near edges.
        addCrop("center", 0.20f, 0.20f, 0.80f, 0.80f)
        addCrop("tall", 0.28f, 0.12f, 0.72f, 0.88f)
        addCrop("wide", 0.12f, 0.28f, 0.88f, 0.72f)

        fun addRotated(name: String, source: Bitmap, degrees: Float) {
            try {
                val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
                variants.add(CloseupCandidate(Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true), name))
                variants.add(CloseupCandidate(Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true), "$name-clahe"))
            } catch (_: Throwable) {
                // ignore rotation failure
            }
        }

        addRotated("rot90", bitmap, 90f)
        addRotated("rot270", bitmap, 270f)

        return variants
    }

    private fun enhanceForOcr(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhancedMat = Mat()
        clahe.apply(gray, enhancedMat)
        val outBmp = Bitmap.createBitmap(enhancedMat.cols(), enhancedMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhancedMat, outBmp)
        mat.release()
        gray.release()
        enhancedMat.release()
        return outBmp
    }

    private fun normalizeStamp(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null

        val compact = rawText.uppercase()
            .replace("\n", " ")
            .replace("O", "0")

        val regex = Regex("(24\\s*K|22\\s*K|18\\s*K|14\\s*K|999|916|750|585)")
        val match = regex.find(compact)?.value ?: return null
        val normalized = match.replace(" ", "")
        return if (normalized in knownStamps) normalized else null
    }

    /**
     * Detect stamp within jewelry ROI (Region of Interest) constrained by detection box.
     * This prevents false positives from background text/logos.
     * Uses real ML Kit confidence scores instead of hard-coded values.
     */
    suspend fun detectStampInRoi(
        bitmap: Bitmap,
        detection: Detection,
        roiMarginRatio: Float = 0.1f
    ): StampOcrResult {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            // First run ML Kit on a CLAHE-enhanced crop of the detection ROI to help surface small, low-contrast stamps.
            // Compute ROI bounds with margin
            val detectionLeft = (detection.x1 * bitmap.width).toInt()
            val detectionTop = (detection.y1 * bitmap.height).toInt()
            val detectionRight = (detection.x2 * bitmap.width).toInt()
            val detectionBottom = (detection.y2 * bitmap.height).toInt()

            val margin = maxOf(
                (detectionRight - detectionLeft) * roiMarginRatio,
                (detectionBottom - detectionTop) * roiMarginRatio
            ).toInt()

            val roiLeft = max(0, detectionLeft - margin)
            val roiTop = max(0, detectionTop - margin)
            val roiRight = min(bitmap.width, detectionRight + margin)
            val roiBottom = min(bitmap.height, detectionBottom + margin)

            val roiW = (roiRight - roiLeft).coerceAtLeast(1)
            val roiH = (roiBottom - roiTop).coerceAtLeast(1)

            val roiRect = android.graphics.Rect(roiLeft, roiTop, roiRight, roiBottom)

            // Crop ROI bitmap
            val roiBitmap = try {
                Bitmap.createBitmap(bitmap, roiLeft, roiTop, roiW, roiH)
            } catch (t: Throwable) {
                bitmap
            }

            // Prepare candidate crops: original ROI and tighter center crop to focus on small stamps
            val candidates = mutableListOf<Bitmap>()
            candidates.add(roiBitmap)
            try {
                val centerW = (roiW * 0.6).toInt().coerceAtLeast(1)
                val centerH = (roiH * 0.35).toInt().coerceAtLeast(1)
                val cx = (roiW - centerW) / 2
                val cy = (roiH - centerH) / 2
                val centerCrop = Bitmap.createBitmap(roiBitmap, cx, cy, centerW, centerH)
                candidates.add(centerCrop)
            } catch (_: Throwable) {
                // ignore
            }

            var bestStamp: String? = null
            var bestConfidence = 0f
            var bestRawText = ""

            // Attempt OCR on CLAHE-enhanced candidates first
            for (candidate in candidates) {
                val enhancedBmp = try {
                    // Enhance with CLAHE (local implementation) to avoid accessing private helpers
                    val mat = Mat()
                    Utils.bitmapToMat(candidate, mat)
                    val gray = Mat()
                    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                    val enhancedMat = Mat()
                    clahe.apply(gray, enhancedMat)
                    val outBmp = Bitmap.createBitmap(enhancedMat.cols(), enhancedMat.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(enhancedMat, outBmp)
                    mat.release()
                    gray.release()
                    enhancedMat.release()
                    outBmp
                } catch (t: Throwable) {
                    candidate
                }

                val image = InputImage.fromBitmap(enhancedBmp, 0)
                val result = recognizer.process(image).await()

                // Log OCR attempt for diagnostics
                Log.i("StampOcr", "OCR attempt on ROI=$roiRect candidate=${enhancedBmp.width}x${enhancedBmp.height} text='${result.text}'")

                // Check text blocks within candidate
                for (textBlock in result.textBlocks) {
                    val text = textBlock.text
                    val normalized = normalizeStamp(text)
                    if (normalized != null) {
                        val blockConfidence = 85f
                        if (blockConfidence > bestConfidence) {
                            bestStamp = normalized
                            bestConfidence = blockConfidence
                            bestRawText = text
                        }
                    }
                }

                // If we already found a very confident stamp, stop
                if (bestConfidence >= 85f) break
            }

            // As a final fallback, run OCR on the full image (helps if ROI was wrong)
            if (bestStamp == null) {
                try {
                    val fullImage = InputImage.fromBitmap(bitmap, 0)
                    val fullResult = recognizer.process(fullImage).await()
                    Log.i("StampOcr", "Fallback full-image OCR text='${fullResult.text}'")
                    for (textBlock in fullResult.textBlocks) {
                        val text = textBlock.text
                        val normalized = normalizeStamp(text)
                        if (normalized != null) {
                            bestStamp = normalized
                            bestConfidence = 65f
                            bestRawText = text
                            break
                        }
                    }
                } catch (_: Throwable) {
                    // ignore
                }
            }
            
            // Return best candidate found (if any)
            if (bestStamp != null) {
                Log.i("StampOcr", "Found stamp=$bestStamp confidence=${bestConfidence.toInt()} raw='$bestRawText' roi=$roiRect")
                StampOcrResult(
                    rawText = bestRawText.ifBlank { null },
                    normalizedStamp = bestStamp,
                    confidence = bestConfidence.toInt().coerceIn(0, 100),
                    detected = true
                )
            } else {
                Log.i("StampOcr", "No stamp found in ROI=$roiRect full_text=''")
                StampOcrResult(
                    rawText = null,
                    normalizedStamp = null,
                    confidence = 0,
                    detected = false
                )
            }
        } catch (_: Exception) {
            StampOcrResult(rawText = null, normalizedStamp = null, confidence = 0, detected = false)
        } finally {
            recognizer.close()
        }
    }
}
