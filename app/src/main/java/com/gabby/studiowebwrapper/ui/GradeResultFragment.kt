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
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.animation.ObjectAnimator
import com.google.android.material.snackbar.Snackbar
import androidx.fragment.app.Fragment
import coil.load
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.AppDatabase
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.databinding.FragmentGradeResultBinding
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.gabby.studiowebwrapper.model.YoloDetectionOutput
import com.gabby.studiowebwrapper.util.ImageUtils
import com.gabby.studiowebwrapper.util.YoloLabels
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Spinner

class GradeResultFragment : Fragment() {
    private var originalPreviewBitmap: Bitmap? = null
    private var boxedPreviewBitmap: Bitmap? = null
    private var hasDetectionOverlay: Boolean = false
    private var showingOverlay: Boolean = true
    private var currentResult: SuggestMetadataOutput? = null
    private lateinit var pickStampLauncher: ActivityResultLauncher<String>

    private fun clampScore(score: Int): Int = score.coerceIn(0, 100)

    private fun clampDetectionScore(score: Float): Int {
        if (!score.isFinite()) return 0
        return (score.coerceIn(0f, 1f) * 100f).toInt()
    }

    private fun computedTotalScore(result: SuggestMetadataOutput): Int {
        val persistedTotal = clampScore(result.totalComputedScore)
        if (persistedTotal > 0) return persistedTotal

        val yolo = clampScore(result.yoloScore)
        val lbp = clampScore(result.lbpScore)
        val orb = clampScore(result.orbScore)
        val hasComponents = yolo > 0 || lbp > 0 || orb > 0
        return if (hasComponents) (yolo + lbp + orb) / 3 else clampScore(result.qualityScore)
    }

    private fun likelihoodBandForScore(score: Int): String {
        return when {
            score >= 85 -> "Very Close Match"
            score >= 70 -> "Close Match"
            score >= 55 -> "Some Similarity"
            else -> "Low Match"
        }
    }

    private fun calculateConfidenceRange(result: SuggestMetadataOutput): Pair<Int, Int> {
        val yolo = result.yoloScore.coerceIn(0, 100)
        val lbp = result.lbpScore.coerceIn(0, 100)
        val orb = result.orbScore.coerceIn(0, 100)
        
        // If no component scores, use wider range
        if (yolo == 0 && lbp == 0 && orb == 0) {
            return Pair(0, 15)
        }
        
        val scores = listOf(yolo, lbp, orb).filter { it > 0 }
        if (scores.isEmpty()) return Pair(0, 15)
        
        val avg = scores.average().toInt()
        // Range based on how much components agree (tight agreement = narrow range)
        val variance = if (scores.size > 1) {
            scores.map { (it - avg) * (it - avg) }.average().toInt()
        } else {
            10
        }
        val range = (variance / 10).coerceIn(3, 15)
        
        val lower = (avg - range).coerceIn(0, 100)
        val upper = (avg + range).coerceIn(0, 100)
        return Pair(lower, upper)
    }

    private fun badgeBackgroundForBand(band: String): Int {
        return when (band) {
            "Very Close Match" -> com.gabby.studiowebwrapper.R.drawable.bg_badge_tier_a
            "Close Match" -> com.gabby.studiowebwrapper.R.drawable.bg_badge_tier_b
            "Some Similarity" -> com.gabby.studiowebwrapper.R.drawable.bg_badge_tier_c
            else -> com.gabby.studiowebwrapper.R.drawable.bg_badge_tier_d
        }
    }

    private fun gradingCriteriaText(): String {
        return "Simple guide\n" +
            "Very Close Match: 85%+\n" +
            "Close Match: 70-84%\n" +
            "Some Similarity: 55-69%\n" +
            "Low Match: below 55%\n\n" +
            "This is a photo-based estimate, not a certified appraisal."
    }

    interface Callbacks {
        fun navigateToUploadForm()
        fun openAdvancedMetrics(resultJson: String, previewDataUri: String)
        fun navigateBack()
    }

    private var callbacks: Callbacks? = null
    private var binding: FragmentGradeResultBinding? = null
    private val gson = Gson()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentGradeResultBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickStampLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val bmp = decodePreviewBitmap(uri.toString()) ?: runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (bmp != null) {
                    val stampResult = runCatching { com.gabby.studiowebwrapper.util.StampOcr.detectStamp(bmp) }.getOrNull()
                    if (stampResult != null) applyStampCloseupResult(stampResult)
                    else Toast.makeText(requireContext(), "Couldn't read stamp from the selected image.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to load selected image.", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
            sourceHash = "sample"
        )

        currentResult = result

        binding?.apply {
            renderPreviewWithDetections(previewDataUri, result)
            toggleDetectionsButton.setOnClickListener { togglePreviewMode() }
            updateToggleButtonState()

            try {
                val total = computedTotalScore(result)
                val band = likelihoodBandForScore(total)
                val (rangeMin, rangeMax) = calculateConfidenceRange(result)
                
                // Main score display - show confidence range only if stamp detected
                val scoreDisplay = if (result.stampDetected) {
                    "Visual Match: $total% (±${(rangeMax - rangeMin) / 2}%)"
                } else {
                    "Visual Match: $total%"
                }
                totalScoreText.text = scoreDisplay
                overallScoreBar.progress = total

                // Assessment level badge
                tierBadgeText.text = "Likelihood Level: $band"
                tierBadgeText.setBackgroundResource(badgeBackgroundForBand(band))

                val topDetection = result.yoloDetections.orEmpty().maxByOrNull { it.score }
                val itemType = topDetection?.let { YoloLabels.labelForClassId(it.classId) } ?: "Unknown"
                itemTypeText.text = "Detected item: $itemType"
                val rawMode = result.yoloModelUsed.orEmpty()
                val consumerMode = if (rawMode.contains("fallback", ignoreCase = true)) {
                    "Compatibility mode"
                } else if (rawMode.isBlank()) {
                    "Standard mode"
                } else {
                    "Standard mode"
                }
                modelUsedText.text = "Scan mode: $consumerMode"

                materialText.text = resolvedStampLabel(result)
                karatBasisText.text = resolvedStampBasis(result)
                analysisText.text = buildUserFacingAnalysis(result)
                
                advancedMetricsButton.setOnClickListener {
                    callbacks?.openAdvancedMetrics(Gson().toJson(result), previewDataUri)
                }

                addStampButton.setOnClickListener {
                    pickStampLauncher.launch("image/*")
                }

                rescanSuggestionsText.text = buildRescanSuggestionsText(result, total)

                gradeAnotherButton.setOnClickListener { callbacks?.navigateToUploadForm() }
                backButton.setOnClickListener { callbacks?.navigateBack() }

                // Feedback send handler
                feedbackSendButton.setOnClickListener {
                    val selectedId = feedbackRadioGroup.checkedRadioButtonId
                    val selection = when (selectedId) {
                        R.id.feedbackCorrect -> "Correct"
                        R.id.feedbackIncorrect -> "Incorrect"
                        else -> null
                    }

                    if (selection == null) {
                        Toast.makeText(requireContext(), "Please select an option before sending feedback.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val comment = feedbackComment.text?.toString()?.takeIf { it.isNotBlank() }
                    val modelConf = computedTotalScore(result)
                    val routed = determineRouting(selection, modelConf)

                    val resultJsonArg = try { Gson().toJson(result) } catch (_: Exception) { arguments?.getString("arg_result_json") ?: "" }
                    val previewArg = arguments?.getString(ARG_PREVIEW_DATA_URI).orEmpty()

                    // Persist feedback to Room
                    viewLifecycleOwner.lifecycleScope.launch {
                        var savedEntry: com.gabby.studiowebwrapper.data.FeedbackEntry? = null
                        withContext(Dispatchers.IO) {
                            try {
                                val entry = com.gabby.studiowebwrapper.data.FeedbackEntry(
                                    userId = NativeRepository.getCurrentUser(requireContext())?.id.orEmpty(),
                                    resultJson = resultJsonArg,
                                    previewUri = previewArg,
                                    selection = selection,
                                    comment = comment,
                                    modelConfidence = modelConf,
                                    routedTo = routed
                                )
                                val id = AppDatabase.getInstance(requireContext()).feedbackDao().insert(entry)
                                savedEntry = entry.copy(id = id)
                            } catch (e: Exception) {
                                // ignore DB failures for now
                            }
                        }

                        // If user opted-in and Supabase enabled, sync anonymized feedback in background
                        savedEntry?.let { entry ->
                            if (NativeRepository.isFeedbackSharingEnabled(requireContext())) {
                                NativeRepository.syncFeedbackEntryToSupabase(requireContext(), entry)
                            }
                        }

                        Snackbar.make(binding?.root ?: view, "Feedback sent — thanks!", Snackbar.LENGTH_LONG).show()
                        // Optionally clear comment and selection
                        feedbackRadioGroup.clearCheck()
                        feedbackComment.text?.clear()
                    }
                }
            } catch (e: Exception) {
                Log.e("GradeResultFragment", "Error binding result data", e)
                try {
                    analysisText.text = "Error displaying result: ${e.message}"
                } catch (_: Exception) {}
            }
        }
    }



    private fun renderPreviewWithDetections(previewDataUri: String, result: SuggestMetadataOutput) {
        val imageView = binding?.previewImage ?: return
        val detections = result.yoloDetections.orEmpty()
        originalPreviewBitmap = null
        boxedPreviewBitmap = null
        hasDetectionOverlay = detections.isNotEmpty()
        showingOverlay = false

        if (detections.isEmpty()) {
            try {
                imageView.load(previewDataUri)
            } catch (e: Exception) {
                Log.e("GradeResultFragment", "Failed to load preview image: $previewDataUri", e)
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
                Log.e("GradeResultFragment", "Failed to load preview image: $previewDataUri", e)
                imageView.setImageDrawable(null)
            }
            hasDetectionOverlay = false
            updateToggleButtonState()
            return
        }

        originalPreviewBitmap = sourceBitmap
        boxedPreviewBitmap = drawYoloBoxes(sourceBitmap, detections)
        imageView.load(originalPreviewBitmap)
        updateToggleButtonState()
    }

    private fun decodePreviewBitmap(previewDataUri: String): Bitmap? {
        if (previewDataUri.isBlank()) return null

        if (previewDataUri.startsWith("data:", ignoreCase = true)) {
            return runCatching { ImageUtils.decodeDataUri(previewDataUri) }.getOrNull()
        }

        // File path generated by MainActivity thumbnail flow.
        if (!previewDataUri.contains("://")) {
            return BitmapFactory.decodeFile(previewDataUri)
        }

        // content:// or file:// URI sources.
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

    private fun applyStampCloseupResult(stamp: com.gabby.studiowebwrapper.util.StampOcrResult) {
        val prev = currentResult ?: return
        val prevTotal = computedTotalScore(prev)
        val stampDetected = stamp.detected && (stamp.confidence >= 50f)
        var updated = prev.copy()
        if (stampDetected) {
            updated = updated.copy(
                stampText = stamp.normalizedStamp ?: updated.stampText,
                stampConfidence = stamp.confidence,
                stampDetected = true
            )
        }
        val newTotal = if (stampDetected) (prevTotal + 10).coerceAtMost(100) else prevTotal
        updated = updated.copy(qualityScore = newTotal, totalComputedScore = newTotal)
        currentResult = updated

        updateUiWithResult(updated, previousScore = prevTotal)

        viewLifecycleOwner.lifecycleScope.launch {
            persistUpdatedHistoryEntry(updated)
        }

        // Show transient confirmation snackbar with details
        val snackMsg = if (stampDetected) {
            "Stamp recognized: ${stamp.normalizedStamp ?: "unknown"} — score $prevTotal% → ${updated.totalComputedScore}%"
        } else {
            "Stamp not confidently recognized. No score change."
        }
        val root = binding?.root ?: view ?: requireActivity().window.decorView.rootView
        Snackbar.make(root, snackMsg, Snackbar.LENGTH_LONG).show()
    }

    private suspend fun persistUpdatedHistoryEntry(updated: SuggestMetadataOutput) {
        val context = context ?: return
        val userId = NativeRepository.getCurrentUser(context)?.id.orEmpty()
        if (userId.isBlank()) return

        val previewRef = arguments?.getString(ARG_PREVIEW_DATA_URI).orEmpty()
        if (previewRef.isBlank() && updated.sourceHash.isNullOrBlank()) return

        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).historyDao()
            val existing = if (!updated.sourceHash.isNullOrBlank()) {
                dao.findLatestByUserAndSourceHash(userId, updated.sourceHash)
            } else {
                dao.findLatestByUserAndPreviewUri(userId, previewRef)
            } ?: return@withContext

            val merged = existing.copy(
                sourceHash = updated.sourceHash ?: existing.sourceHash,
                resultJson = Gson().toJson(updated)
            )
            dao.insert(merged)
        }
    }

    private fun updateUiWithResult(result: SuggestMetadataOutput, previousScore: Int? = null) {
        binding?.apply {
            val total = computedTotalScore(result)
            val band = likelihoodBandForScore(total)
            tierBadgeText.text = "Match level: $band"
            tierBadgeText.setBackgroundResource(badgeBackgroundForBand(band))

            if (previousScore != null) {
                ObjectAnimator.ofInt(overallScoreBar, "progress", previousScore, total).setDuration(600).start()
                val delta = total - previousScore
                // Only display the delta change when a karat stamp was detected by the user
                if (result.stampDetected) {
                    val deltaStr = if (delta > 0) "+$delta" else if (delta < 0) "$delta" else "+0"
                    totalScoreText.text = "Visual Match: $total% ($deltaStr)"
                } else {
                    totalScoreText.text = "Visual Match: $total%"
                }
            } else {
                overallScoreBar.progress = total
                totalScoreText.text = "Visual Match: $total%"
            }

            materialText.text = resolvedStampLabel(result)
            karatBasisText.text = resolvedStampBasis(result)
            analysisText.text = buildUserFacingAnalysis(result)
            rescanSuggestionsText.text = buildRescanSuggestionsText(result, total)
        }
    }

    private fun buildUserFacingAnalysis(result: SuggestMetadataOutput): String {
        val noStampsDetected = result.yoloDetections.orEmpty().isEmpty() &&
            clampScore(result.yoloScore) == 0 &&
            clampScore(result.lbpScore) == 0 &&
            clampScore(result.orbScore) == 0
        if (noStampsDetected) {
            return "No jewelry detected. We can't estimate the karat without a clear item. Please retake with better lighting and a centered view."
        }

        val purity = result.purity?.ifBlank { "Unknown" } ?: "Unknown"
        val stampBasis = if (result.stampDetected && !result.stampText.isNullOrBlank()) {
            "Based on the karat stamp: ${result.stampText}"
        } else {
            "Based on visual appearance alone (no stamp detected)"
        }
        return "Karat estimate: $purity — $stampBasis\n\nThis is a probability estimate, not a professional appraisal. Have a certified jeweler verify before relying on this."
    }

    private fun resolvedStampLabel(result: SuggestMetadataOutput): String {
        val purity = result.purity?.ifBlank { "Unknown" } ?: "Unknown"
        return if (result.stampDetected && !result.stampText.isNullOrBlank()) {
            "$purity (karat stamp: ${result.stampText})"
        } else {
            "$purity (no clear karat stamp detected)"
        }
    }

    private fun resolvedStampBasis(result: SuggestMetadataOutput): String {
        return if (result.stampDetected && !result.stampText.isNullOrBlank()) {
            "Stamp detected: ${result.stampText} (${result.stampConfidence}% confidence)"
        } else {
            "No stamp detected — visual analysis only"
        }
    }

    private fun buildRescanSuggestionsText(result: SuggestMetadataOutput, total: Int): String {
        val suggestions = result.rescanSuggestions.orEmpty().filter { it.isNotBlank() }.toMutableList()
        if (suggestions.isEmpty()) {
            val yolo = clampScore(result.yoloScore)
            val lbp = clampScore(result.lbpScore)
            val orb = clampScore(result.orbScore)
            if (yolo in 1..59) suggestions += "Frame the item better - make sure the entire jewelry piece is visible and centered."
            if (lbp in 1..59) suggestions += "Improve lighting - use natural light or even artificial lighting to show the surface details."
            if (orb in 1..59) suggestions += "Reduce camera shake - hold your phone steady and tap to focus before capturing."
            if (total < 70) suggestions += "Retake from a top-down angle with a clean, plain background."
        }

        val warnings = result.captureWarnings.orEmpty().filter { it.isNotBlank() }
        if (warnings.isNotEmpty()) {
            suggestions.addAll(0, warnings)
        }

        if (suggestions.isEmpty()) {
            return "Photo quality looks good. Ready to grade!"
        }
        return suggestions.distinct().joinToString(separator = "\n", prefix = "• ")
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

    private fun determineRouting(selection: String, modelConf: Int): String? {
        // Simple routing rules:
        // - "Incorrect" => data curation (mislabeled / needs relabel)
        // - "Correct" with very low confidence => product backlog (improve thresholds)
        return when {
            selection == "Incorrect" -> "data-curation"
            selection == "Correct" && modelConf < 50 -> "product-backlog"
            else -> null
        }
    }

    companion object {
        private const val ARG_RESULT_JSON = "arg_result_json"
        private const val ARG_PREVIEW_DATA_URI = "arg_preview_data_uri"

        fun newInstance(result: SuggestMetadataOutput, previewDataUri: String): GradeResultFragment {
            val fragment = GradeResultFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_RESULT_JSON, Gson().toJson(result))
                putString(ARG_PREVIEW_DATA_URI, previewDataUri)
            }
            return fragment
        }

        fun newInstance(resultJson: String, previewDataUri: String): GradeResultFragment {
            val fragment = GradeResultFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_RESULT_JSON, resultJson)
                putString(ARG_PREVIEW_DATA_URI, previewDataUri)
            }
            return fragment
        }
    }
}
