package com.gabby.studiowebwrapper.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import coil.load
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.databinding.FragmentAdvancedMetricsBinding
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.gabby.studiowebwrapper.model.YoloDetectionOutput
import com.gabby.studiowebwrapper.util.ImageUtils
import com.gabby.studiowebwrapper.util.YoloLabels
import com.google.gson.Gson

class AdvancedMetricsFragment : Fragment() {
    interface Callbacks {
        fun navigateBack()
    }

    private var callbacks: Callbacks? = null
    private var binding: FragmentAdvancedMetricsBinding? = null
    private val gson = Gson()

    private var originalPreviewBitmap: Bitmap? = null
    private var boxedPreviewBitmap: Bitmap? = null
    private var hasDetectionOverlay: Boolean = false
    private var showingOverlay: Boolean = true

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAdvancedMetricsBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val resultJson = arguments?.getString(ARG_RESULT_JSON) ?: ""
        val previewDataUri = arguments?.getString(ARG_PREVIEW_DATA_URI) ?: ""

        val result = try {
            if (resultJson.isNotEmpty()) gson.fromJson(resultJson, SuggestMetadataOutput::class.java)
            else null
        } catch (e: Exception) {
            null
        } ?: SuggestMetadataOutput(
            material = "Sample Item",
            purity = "Unknown",
            gemstones = null,
            qualityScore = 90,
            analysis = "Sample analysis (frontend)",
            similarProducts = null,
            yoloScore = 90,
            lbpScore = 88,
            orbScore = 85,
            expectedWeightGrams = 3.2f,
            sourceHash = "sample",
            yoloModelUsed = "YOLOv8n"
        )

        binding?.apply {
            renderPreviewWithDetections(previewDataUri, result)
            toggleDetectionsButton.setOnClickListener { togglePreviewMode() }
            updateToggleButtonState()

            try {
                gradingCriteriaText.text = gradingCriteriaText()

                val yolo = clampScore(result.yoloScore)
                val lbp = clampScore(result.lbpScore)
                val orb = clampScore(result.orbScore)
                val total = computedTotalScore(result)
                val hasComponents = hasComponentScores(result)

                yoloScoreText.text = "Object Match (YOLO): $yolo/100"
                lbpScoreText.text = "Texture Similarity (LBP): $lbp/100"
                orbScoreText.text = "Pattern Match (ORB): $orb/100"
                totalScoreText.text = if (hasComponents) {
                    "Overall Likelihood: $total%"
                } else {
                    "Overall Likelihood: $total% (estimated)"
                }

                overallScoreBar.progress = total
                yoloScoreBar.progress = yolo
                lbpScoreBar.progress = lbp
                orbScoreBar.progress = orb

                val topSignal = listOf(
                    "YOLO" to yolo,
                    "LBP" to lbp,
                    "ORB" to orb
                ).maxByOrNull { it.second }

                quickInterpretationText.text = if (hasComponents) {
                    "How to read this:\n" +
                        "- Overall Likelihood combines YOLO, LBP, and ORB equally.\n" +
                        "- Strongest signal right now: ${topSignal?.first ?: "N/A"} (${topSignal?.second ?: 0}/100).\n" +
                        "- This is a likelihood estimate, not an authenticity verdict."
                } else {
                    "How to read this:\n" +
                        "- Component signals are incomplete for this image.\n" +
                        "- Overall value is an estimated likelihood from available data.\n" +
                        "- This is a likelihood estimate, not an authenticity verdict."
                }

                val detectionCount = result.yoloDetections.orEmpty().size
                val topDetection = result.yoloDetections.orEmpty().maxByOrNull { it.score }
                val topDetectionLabel = topDetection?.let { "${YoloLabels.labelForClassId(it.classId)} ${clampDetectionScore(it.score)}%" } ?: "N/A"
                debugInfoText.text = buildString {
                    append("Debug:\n")
                    append("- Detections: $detectionCount\n")
                    append("- Top detection: $topDetectionLabel\n")
                    append("- YOLO score: $yolo/100 | LBP: $lbp/100 | ORB: $orb/100\n")
                    append("- Karat stamp OCR: ")
                    if (result.stampDetected && !result.stampText.isNullOrBlank()) {
                        append("${result.stampText} (${result.stampConfidence}%)\n")
                    } else {
                        append("none\n")
                    }
                    append("- Overlay: ")
                    append(if (result.yoloDetections.isNullOrEmpty()) "off" else "on")
                }

                explainabilityText.text = buildExplainabilityText(result, total)
                rescanSuggestionsText.text = buildRescanSuggestionsText(result, total)

                val band = likelihoodBandForScore(total)
                tierBadgeText.text = "Match level: $band"
                tierBadgeText.setBackgroundResource(badgeBackgroundForBand(band))
                expectedWeightText.text = result.expectedWeightGrams?.let { String.format("≈ %.2f g", it) } ?: "N/A"

                materialText.text = resolvedStampLabel(result)
                analysisText.text = result.analysis

                val products = result.similarProducts
                similarProductsText.text = if (products.isNullOrEmpty()) {
                    "No similar products returned"
                } else {
                    products.joinToString(separator = "\n\n") { product ->
                        "${product.name}\n${product.price}\n${product.url}"
                    }
                }

                similarProductsText.setOnClickListener {
                    val firstUrl = products?.firstOrNull()?.url ?: return@setOnClickListener
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(firstUrl)))
                }

                tierBadgeText.setOnClickListener {
                    gradingCriteriaText.visibility =
                        if (gradingCriteriaText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }

                backButton.setOnClickListener { callbacks?.navigateBack() }
            } catch (e: Exception) {
                Log.e("AdvancedMetricsFragment", "Error binding result data", e)
                try {
                    analysisText.text = "Error displaying result: ${e.message}"
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun clampScore(score: Int): Int = score.coerceIn(0, 100)

    private fun clampDetectionScore(score: Float): Int {
        if (!score.isFinite()) return 0
        return (score.coerceIn(0f, 1f) * 100f).toInt()
    }

    private fun hasComponentScores(result: SuggestMetadataOutput): Boolean {
        return clampScore(result.yoloScore) > 0 || clampScore(result.lbpScore) > 0 || clampScore(result.orbScore) > 0
    }

    private fun computedTotalScore(result: SuggestMetadataOutput): Int {
        val yolo = clampScore(result.yoloScore)
        val lbp = clampScore(result.lbpScore)
        val orb = clampScore(result.orbScore)
        val hasComponentScores = yolo > 0 || lbp > 0 || orb > 0
        return if (hasComponentScores) {
            (yolo + lbp + orb) / 3
        } else {
            clampScore(result.qualityScore)
        }
    }

    private fun likelihoodBandForScore(score: Int): String {
        return when {
            score >= 85 -> "Very Close Match"
            score >= 70 -> "Close Match"
            score >= 55 -> "Some Similarity"
            else -> "Low Match"
        }
    }

    private fun badgeBackgroundForBand(band: String): Int {
        return when (band) {
            "Very Close Match" -> R.drawable.bg_badge_tier_a
            "Close Match" -> R.drawable.bg_badge_tier_b
            "Some Similarity" -> R.drawable.bg_badge_tier_c
            else -> R.drawable.bg_badge_tier_d
        }
    }

    private fun gradingCriteriaText(): String {
        return "Quick guide\n" +
            "Very Close Match: overall score >= 85%\n" +
            "Close Match: overall score >= 70%\n" +
            "Some Similarity: overall score >= 55%\n" +
            "Low Match: overall score < 55%\n\n" +
            "This is a photo-based similarity estimate."
    }

    private fun resolvedStampLabel(result: SuggestMetadataOutput): String {
        val purity = result.purity?.ifBlank { "Unknown" } ?: "Unknown"
        return if (result.stampDetected && !result.stampText.isNullOrBlank()) {
            "$purity (karat stamp: ${result.stampText}, OCR ${result.stampConfidence}%)"
        } else {
            "$purity (no clear karat stamp)"
        }
    }

    private fun renderPreviewWithDetections(previewDataUri: String, result: SuggestMetadataOutput) {
        val imageView = binding?.previewImage ?: return
        val detections = result.yoloDetections.orEmpty()
        originalPreviewBitmap = null
        boxedPreviewBitmap = null
        hasDetectionOverlay = detections.isNotEmpty()
        showingOverlay = true

        if (detections.isEmpty()) {
            try {
                imageView.load(previewDataUri)
            } catch (e: Exception) {
                Log.e("AdvancedMetricsFragment", "Failed to load preview image: $previewDataUri", e)
                imageView.setImageDrawable(null)
            }
            updateToggleButtonState()
            return
        }

        val sourceBitmap = decodePreviewBitmap(previewDataUri)
        if (sourceBitmap == null) {
            try {
                imageView.load(previewDataUri)
            } catch (e: Exception) {
                Log.e("AdvancedMetricsFragment", "Failed to load preview image: $previewDataUri", e)
                imageView.setImageDrawable(null)
            }
            hasDetectionOverlay = false
            updateToggleButtonState()
            return
        }

        originalPreviewBitmap = sourceBitmap
        boxedPreviewBitmap = drawYoloBoxes(sourceBitmap, detections)
        imageView.load(boxedPreviewBitmap)
        updateToggleButtonState()
    }

    private fun decodePreviewBitmap(previewDataUri: String): Bitmap? {
        if (previewDataUri.isBlank()) return null

        if (previewDataUri.startsWith("data:", ignoreCase = true)) {
            return runCatching { ImageUtils.decodeDataUri(previewDataUri) }.getOrNull()
        }

        if (!previewDataUri.contains("://")) {
            return BitmapFactory.decodeFile(previewDataUri)
        }

        return runCatching {
            val uri = Uri.parse(previewDataUri)
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }

    private fun togglePreviewMode() {
        if (!hasDetectionOverlay) return
        showingOverlay = !showingOverlay
        val imageView = binding?.previewImage ?: return
        if (showingOverlay) {
            imageView.load(boxedPreviewBitmap)
        } else {
            imageView.load(originalPreviewBitmap)
        }
        updateToggleButtonState()
    }

    private fun updateToggleButtonState() {
        val btn = binding?.toggleDetectionsButton ?: return
        if (!hasDetectionOverlay) {
            btn.visibility = View.VISIBLE
            btn.isEnabled = false
            btn.alpha = 0.65f
            btn.text = "No detections available"
            return
        }
        btn.visibility = View.VISIBLE
        btn.isEnabled = true
        btn.alpha = 1f
        btn.text = if (showingOverlay) "Show Original" else "Show Detections"
    }

    private fun drawYoloBoxes(bitmap: Bitmap, detections: List<YoloDetectionOutput>): Bitmap {
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val stroke = (bitmap.width / 180f).coerceAtLeast(3f)
        val textSize = (bitmap.width / 24f).coerceAtLeast(24f)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#00C853")
            strokeWidth = stroke
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            this.textSize = textSize
        }
        val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#CC000000")
        }

        detections.forEach { d ->
            val left: Float
            val top: Float
            val right: Float
            val bottom: Float

            val appearsNormalized = d.x2 <= 1.5f && d.y2 <= 1.5f
            if (appearsNormalized) {
                left = (d.x1 * bitmap.width).coerceIn(0f, bitmap.width.toFloat() - 1f)
                top = (d.y1 * bitmap.height).coerceIn(0f, bitmap.height.toFloat() - 1f)
                right = (d.x2 * bitmap.width).coerceIn(left + 1f, bitmap.width.toFloat())
                bottom = (d.y2 * bitmap.height).coerceIn(top + 1f, bitmap.height.toFloat())
            } else {
                left = (d.x1 / 640f * bitmap.width).coerceIn(0f, bitmap.width.toFloat() - 1f)
                top = (d.y1 / 640f * bitmap.height).coerceIn(0f, bitmap.height.toFloat() - 1f)
                right = (d.x2 / 640f * bitmap.width).coerceIn(left + 1f, bitmap.width.toFloat())
                bottom = (d.y2 / 640f * bitmap.height).coerceIn(top + 1f, bitmap.height.toFloat())
            }

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${YoloLabels.labelForClassId(d.classId)} ${clampDetectionScore(d.score)}%"
            val textW = textPaint.measureText(label)
            val textH = textPaint.fontMetrics.run { bottom - top }
            val labelPad = stroke
            val bgLeft = left
            val bgTop = (top - textH - (labelPad * 2f)).coerceAtLeast(0f)
            val bgRight = (left + textW + (labelPad * 2f)).coerceAtMost(bitmap.width.toFloat())
            val bgBottom = (bgTop + textH + (labelPad * 2f)).coerceAtMost(bitmap.height.toFloat())
            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, labelBgPaint)
            val textX = bgLeft + labelPad
            val textY = bgBottom - labelPad - textPaint.fontMetrics.bottom
            canvas.drawText(label, textX, textY, textPaint)
        }

        return mutable
    }

    private fun buildExplainabilityText(result: SuggestMetadataOutput, total: Int): String {
        val provided = result.explainability.orEmpty().filter { it.isNotBlank() }
        if (provided.isNotEmpty()) {
            return provided.joinToString(separator = "\n", prefix = "- ")
        }

        val yolo = clampScore(result.yoloScore)
        val lbp = clampScore(result.lbpScore)
        val orb = clampScore(result.orbScore)
        val fallbackLines = if (hasComponentScores(result)) {
            listOf(
                "YOLO detection confidence: $yolo/100",
                "LBP texture similarity: $lbp/100",
                "ORB keypoint matching: $orb/100",
                "Overall likelihood formula: avg(YOLO, LBP, ORB) = $total%",
                "This score estimates visual similarity from the photo only."
            )
        } else {
            listOf(
                "Algorithm component scores are unavailable for this result.",
                "Overall likelihood uses quality estimate = $total%.",
                "This score estimates visual similarity from the photo only."
            )
        }
        return fallbackLines.joinToString(separator = "\n", prefix = "- ")
    }

    private fun buildRescanSuggestionsText(result: SuggestMetadataOutput, total: Int): String {
        val suggestions = result.rescanSuggestions.orEmpty().filter { it.isNotBlank() }.toMutableList()
        if (suggestions.isEmpty()) {
            val yolo = clampScore(result.yoloScore)
            val lbp = clampScore(result.lbpScore)
            val orb = clampScore(result.orbScore)
            if (yolo in 1..59) suggestions += "Reframe the jewelry so the whole item is centered and visible."
            if (lbp in 1..59) suggestions += "Improve lighting to reveal more surface texture details."
            if (orb in 1..59) suggestions += "Reduce blur by holding steady and focusing before capture."
            if (total < 70) suggestions += "Retake with a clean, plain background and a straight angle."
        }

        val warnings = result.captureWarnings.orEmpty().filter { it.isNotBlank() }
        if (warnings.isNotEmpty()) {
            suggestions.addAll(0, warnings)
        }

        if (suggestions.isEmpty()) {
            return "No rescan needed. Capture quality and score look stable."
        }
        return suggestions.distinct().joinToString(separator = "\n", prefix = "- ")
    }

    override fun onDestroyView() {
        originalPreviewBitmap = null
        boxedPreviewBitmap = null
        binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        callbacks = null
        super.onDetach()
    }

    companion object {
        private const val ARG_RESULT_JSON = "arg_result_json"
        private const val ARG_PREVIEW_DATA_URI = "arg_preview_data_uri"

        fun newInstance(resultJson: String, previewDataUri: String): AdvancedMetricsFragment {
            val fragment = AdvancedMetricsFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_RESULT_JSON, resultJson)
                putString(ARG_PREVIEW_DATA_URI, previewDataUri)
            }
            return fragment
        }
    }
}
