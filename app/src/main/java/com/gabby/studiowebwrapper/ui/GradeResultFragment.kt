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
import androidx.work.*
import java.util.concurrent.TimeUnit
import com.gabby.studiowebwrapper.data.PaxgPriceRepository
import android.animation.ObjectAnimator
import com.google.android.material.snackbar.Snackbar
import androidx.fragment.app.Fragment
import coil.load
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.AppDatabase
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.databinding.FragmentGradeResultBinding
import com.gabby.studiowebwrapper.model.GoldValueEstimate
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
import com.gabby.studiowebwrapper.data.PaxgPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.text.NumberFormat

class GradeResultFragment : Fragment() {
    private val DEFAULT_PAXG_PRICE_PHP = 200000.0
    private val TROY_OUNCE_GRAMS = 31.1034768
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

    private fun bandStringResForScore(score: Int): Int {
        return when {
            score >= 81 -> R.string.band_81_100_badge
            score >= 61 -> R.string.band_61_80_badge
            score >= 41 -> R.string.band_41_60_badge
            else -> R.string.band_0_40_badge
        }
    }

    private fun bandPrimaryCtaResForScore(score: Int): Int {
        return when {
            score >= 81 -> if (conservativeTone) R.string.band_81_100_cta_export_cons else R.string.band_81_100_cta_export
            score >= 61 -> if (conservativeTone) R.string.band_61_80_cta_quote_cons else R.string.band_61_80_cta_quote
            score >= 41 -> if (conservativeTone) R.string.band_41_60_cta_closeup_cons else R.string.band_41_60_cta_closeup
            else -> if (conservativeTone) R.string.band_0_40_cta_rescan_cons else R.string.band_0_40_cta_rescan
        }
    }

    private fun bandShortMessageResForScore(score: Int): Int {
        return when {
            score >= 81 -> if (conservativeTone) R.string.band_81_100_short_cons else R.string.band_81_100_short
            score >= 61 -> if (conservativeTone) R.string.band_61_80_short_cons else R.string.band_61_80_short
            score >= 41 -> if (conservativeTone) R.string.band_41_60_short_cons else R.string.band_41_60_short
            else -> if (conservativeTone) R.string.band_0_40_short_cons else R.string.band_0_40_short
        }
    }

    private fun evidenceSecondaryLine(result: SuggestMetadataOutput): String {
        val yolo = if (clampScore(result.yoloScore) > 0) "YOLO" else "YOLO"
        val texture = if (clampScore(result.lbpScore) > 0) "Texture" else "Texture"
        val stamp = if (result.stampDetected && !result.stampText.isNullOrBlank()) "Stamp (✓)" else "Stamp (—)"
        return getString(R.string.visual_match_secondary, yolo, texture, stamp)
    }

    private fun gradingCriteriaText(): String {
        return "Simple guide\n" +
            "Very Close Match: 85%+\n" +
            "Close Match: 70-84%\n" +
            "Some Similarity: 55-69%\n" +
            "Low Match: below 55%\n\n" +
            "This is a photo-based estimate, not a certified appraisal."
    }

    private fun isGoldItem(result: SuggestMetadataOutput): Boolean {
        val materialText = result.material.lowercase(Locale.ROOT)
        val purityText = result.purity.orEmpty().lowercase(Locale.ROOT)
        return materialText.contains("gold") || purityText.contains("k") || purityText.contains("karat")
    }

    private fun extractKaratFromStamp(stampText: String): Double? {
        val stamp = stampText.uppercase(Locale.ROOT)
        val match = Regex("(\\d{1,2}(?:\\.\\d+)?)\\s*K").find(stamp)
        if (match != null) {
            return match.groupValues.getOrNull(1)?.toDoubleOrNull()?.coerceIn(1.0, 24.0)
        }
        return when {
            stamp.contains("999") || stamp.contains("24K") -> 24.0
            stamp.contains("916") || stamp.contains("22K") -> 22.0
            stamp.contains("875") || stamp.contains("21K") -> 21.0
            stamp.contains("750") || stamp.contains("18K") -> 18.0
            stamp.contains("585") || stamp.contains("14K") -> 14.0
            stamp.contains("417") || stamp.contains("10K") -> 10.0
            else -> null
        }
    }

    private fun estimateGoldValue(
        result: SuggestMetadataOutput,
        weightGrams: Double,
        karat: Double
    ): GoldValueEstimate {
        val purityFraction = (karat / 24.0).coerceIn(0.1, 1.0)
        val pureGoldGrams = weightGrams * purityFraction
        val paxgPricePhp = PaxgPriceRepository.getCachedPricePhp(requireContext()) ?: DEFAULT_PAXG_PRICE_PHP
        val phpPerGram24k = paxgPricePhp / TROY_OUNCE_GRAMS
        val scrapMid = pureGoldGrams * phpPerGram24k

        val score = computedTotalScore(result)
        val uncertainty = when {
            score >= 81 -> 0.04
            score >= 61 -> 0.07
            score >= 41 -> 0.12
            else -> 0.18
        }
        val resaleUpliftPercent = when {
            score >= 81 -> 20
            score >= 61 -> 16
            score >= 41 -> 12
            else -> 8
        }

        val scrapLow = scrapMid * (1.0 - uncertainty)
        val scrapHigh = scrapMid * (1.0 + uncertainty)
        val resaleMultiplier = 1.0 + (resaleUpliftPercent / 100.0)
        val resaleLow = scrapLow * resaleMultiplier
        val resaleMid = scrapMid * resaleMultiplier
        val resaleHigh = scrapHigh * resaleMultiplier

        return GoldValueEstimate(
            paxgPricePhp = paxgPricePhp,
            phpPerGram24k = phpPerGram24k,
            purityFraction = purityFraction,
            weightGrams = weightGrams,
            pureGoldGrams = pureGoldGrams,
            scrapLowPhp = scrapLow,
            scrapMidPhp = scrapMid,
            scrapHighPhp = scrapHigh,
            resaleLowPhp = resaleLow,
            resaleMidPhp = resaleMid,
            resaleHighPhp = resaleHigh,
            resaleUpliftPercent = resaleUpliftPercent,
            computedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun formatPhp(amount: Double): String {
        val nf = NumberFormat.getNumberInstance(Locale.US)
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        return "₱${nf.format(amount)}"
    }

    private fun renderValueEstimate(result: SuggestMetadataOutput) {
        val estimate = result.goldValueEstimate
        val viewBinding = binding ?: return
        if (estimate == null) {
            viewBinding.textScrapValue.text = getString(R.string.value_estimate_placeholder)
            viewBinding.textResaleValue.text = ""
            viewBinding.valueInfoButton.visibility = View.GONE
            return
        }
        // Show only the mid scrap and mid resale values in the compact view
        viewBinding.textScrapValue.text = getString(R.string.value_label_scrap, formatPhp(estimate.scrapMidPhp))
        viewBinding.textResaleValue.text = getString(R.string.value_label_resale, formatPhp(estimate.resaleMidPhp))
        viewBinding.valueInfoButton.visibility = View.VISIBLE
        viewBinding.valueInfoButton.setOnClickListener {
            // Build a cleaner, step-by-step calculation breakdown
            val nf = java.text.NumberFormat.getNumberInstance(Locale.getDefault()).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }

            val karat = (estimate.purityFraction * 24.0)
            val purityPct = (estimate.purityFraction * 100.0)
            val pureGrams = estimate.pureGoldGrams
            val paxgPerTroy = estimate.paxgPricePhp
            val perGram = estimate.phpPerGram24k
            val scrapMid = estimate.scrapMidPhp
            val scrapLow = estimate.scrapLowPhp
            val scrapHigh = estimate.scrapHighPhp
            val resaleMid = estimate.resaleMidPhp
            val resaleLow = estimate.resaleLowPhp
            val resaleHigh = estimate.resaleHighPhp
            val uplift = estimate.resaleUpliftPercent

            val msg = StringBuilder()
            msg.append("Calculation steps:\n\n")
            msg.append("1) Pure gold mass:\n")
            msg.append(String.format(Locale.getDefault(), "   pure grams = weight × (karat / 24) = %.2fg × (%.1f / 24) = %.3fg\n", estimate.weightGrams, karat, pureGrams))
            msg.append("\n")
            msg.append("2) PAXG price:\n")
            msg.append(String.format(Locale.getDefault(), "   source: PAXG %s (per token ≈ 1 troy oz)\n", formatPhp(paxgPerTroy)))
            msg.append(String.format(Locale.getDefault(), "   price per gram (24K) = %s / %.4f g = %s per g\n", formatPhp(paxgPerTroy), TROY_OUNCE_GRAMS, formatPhp(perGram)))
            msg.append("\n")
            msg.append("3) Scrap (melt) value:\n")
            msg.append(String.format(Locale.getDefault(), "   scrap (mid) = pure grams × price_per_g = %s × %s = %s\n", nf.format(pureGrams), formatPhp(perGram), formatPhp(scrapMid)))
            msg.append(String.format(Locale.getDefault(), "   scrap range = %s – %s (uncertainty applied)\n", formatPhp(scrapLow), formatPhp(scrapHigh)))
            msg.append("\n")
            msg.append("4) Resale estimate:\n")
            msg.append(String.format(Locale.getDefault(), "   resale uplift = %d%% → resale (mid) = scrap_mid × (1 + uplift) = %s\n", uplift, formatPhp(resaleMid)))
            msg.append(String.format(Locale.getDefault(), "   resale range = %s – %s\n", formatPhp(resaleLow), formatPhp(resaleHigh)))
            msg.append("\n")
            msg.append(String.format(Locale.getDefault(), "Inputs: weight = %.2fg, karat = %.1fK (%.1f%% purity)\n", estimate.weightGrams, karat, purityPct))

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.value_estimate_summary_title)
                .setMessage(msg.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun onEstimateValueClicked() {
        val base = currentResult ?: return
        if (!isGoldItem(base)) {
            Toast.makeText(requireContext(), getString(R.string.value_only_for_gold), Toast.LENGTH_SHORT).show()
            return
        }

        val stampText = base.stampText
        if (!base.stampDetected || stampText.isNullOrBlank()) {
            Toast.makeText(requireContext(), getString(R.string.value_requires_stamp), Toast.LENGTH_SHORT).show()
            return
        }

        val karat = extractKaratFromStamp(stampText)
        if (karat == null) {
            Toast.makeText(requireContext(), getString(R.string.value_stamp_not_parseable), Toast.LENGTH_SHORT).show()
            return
        }

        val weight = binding?.weightInput?.text?.toString()?.trim()?.toDoubleOrNull()
        if (weight == null || weight <= 0.0) {
            Toast.makeText(requireContext(), getString(R.string.value_requires_weight), Toast.LENGTH_SHORT).show()
            return
        }

        val estimate = estimateGoldValue(base, weight, karat)
        val updated = base.copy(
            userWeightGrams = weight.toFloat(),
            goldValueEstimate = estimate
        )
        currentResult = updated
        updateUiWithResult(updated)

        viewLifecycleOwner.lifecycleScope.launch {
            persistUpdatedHistoryEntry(updated)
        }
    }

    interface Callbacks {
        fun navigateToUploadForm()
        fun openAdvancedMetrics(resultJson: String, previewDataUri: String)
        fun navigateBack()
    }

    private var callbacks: Callbacks? = null
    private var binding: FragmentGradeResultBinding? = null
    private val gson = Gson()
    private var conservativeTone: Boolean = false
    private val PREFS_NAME = "jg_prefs"
    private val PREF_KEY_CONSERVATIVE = "pref_tone_conservative"

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
                setStampPickerLoading(true)
                try {
                    val bmp = decodePreviewBitmap(uri.toString()) ?: runCatching {
                        requireContext().contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                    if (bmp != null) {
                        val stampResult = runCatching { com.gabby.studiowebwrapper.util.StampOcr.detectStamp(bmp) }.getOrNull()
                        if (stampResult != null) applyStampCloseupResult(stampResult)
                        else Toast.makeText(requireContext(), getString(R.string.couldnt_read_stamp), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.failed_load_selected_image), Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    setStampPickerLoading(false)
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

            // Load tone preference
            conservativeTone = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY_CONSERVATIVE, false)

            // Allow quick toggle of tone by tapping the badge (no extra UI required)
            tierBadgeText.setOnClickListener {
                conservativeTone = !conservativeTone
                requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_KEY_CONSERVATIVE, conservativeTone).apply()
                val toneMsg = if (conservativeTone) getString(R.string.tone_now_conservative) else getString(R.string.tone_now_positive)
                Toast.makeText(requireContext(), toneMsg, Toast.LENGTH_SHORT).show()
                updateUiWithResult(currentResult ?: result)
            }

            try {
                val total = computedTotalScore(result)
                val band = likelihoodBandForScore(total)
                val (rangeMin, rangeMax) = calculateConfidenceRange(result)
                
                // Main score display - use localized microcopy and show estimated range when stamp detected
                totalScoreText.text = getString(R.string.visual_match_primary, total)
                if (result.stampDetected) {
                    val halfRange = (rangeMax - rangeMin) / 2
                    totalScoreText.append(" • ${getString(R.string.estimated_range_label)}: ±${halfRange}%")
                }
                // Secondary evidence line
                modelUsedText.text = evidenceSecondaryLine(result)
                overallScoreBar.progress = total

                // Assessment level badge
                // Badge text and background
                val bandRes = bandStringResForScore(total)
                tierBadgeText.text = getString(bandRes)
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
                bandShortMessageText.text = getString(bandShortMessageResForScore(total))
                if (weightInput.text.isNullOrBlank()) {
                    val seedWeight = result.userWeightGrams ?: result.expectedWeightGrams
                    if (seedWeight != null && seedWeight > 0f) {
                        weightInput.setText(String.format(Locale.US, "%.2f", seedWeight))
                    }
                }
                analysisText.text = buildUserFacingAnalysis(result)
                renderValueEstimate(result)
                
                advancedMetricsButton.setOnClickListener {
                    callbacks?.openAdvancedMetrics(Gson().toJson(result), previewDataUri)
                }

                addStampButton.setOnClickListener {
                    showStampPickerInstructionDialog()
                }

                estimateValueButton.setOnClickListener {
                    onEstimateValueClicked()
                }

                // Save demo API key provided by user so worker can use it
                try {
                    PaxgPrefs.saveApiKey(requireContext(), "CG-XhbiPwxgu9txJofb1TcyzVQ6")
                } catch (_: Exception) {}

                // Price last-updated display and manual refresh
                        fun updatePriceUpdatedView() {
                            val last = PaxgPrefs.getLastUpdatedMs(requireContext())
                            val price = PaxgPriceRepository.getCachedPricePhp(requireContext())
                            val txt = if (price != null && last > 0L) {
                                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                val nf = NumberFormat.getNumberInstance(Locale.US).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
                                "₱${nf.format(price)} / PAXG • Updated: ${fmt.format(Date(last))}"
                            } else if (last > 0L) {
                                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                "Price cached • Updated: ${fmt.format(Date(last))}"
                            } else {
                                "Price not available"
                            }
                            textPriceUpdated.text = txt
                        }

                        updatePriceUpdatedView()

                        btnPriceRefresh.setOnClickListener {
                            it.isEnabled = false
                            lifecycleScope.launch {
                                val fetched = PaxgPriceRepository.fetchAndCachePricePhp(requireContext())
                                updatePriceUpdatedView()
                                // update the value labels using the fresh cache
                                renderValueEstimate(currentResult ?: result)
                                it.isEnabled = true
                                val msg = if (fetched != null) getString(R.string.price_refresh_cache_updated) else getString(R.string.price_refresh_failed)
                                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                            }
                        }

                // Always show "Add Karat Stamp Closeup" for the stamp button
                addStampButton.text = getString(R.string.add_karat_stamp_closeup)

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
                        Toast.makeText(requireContext(), getString(R.string.please_select_feedback_option), Toast.LENGTH_SHORT).show()
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

                        Snackbar.make(binding?.root ?: view, getString(R.string.feedback_sent_thanks), Snackbar.LENGTH_LONG).show()
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

        // Schedule hourly PAXG price updates (best-effort). Keep existing scheduled work if present.
        try {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val work = PeriodicWorkRequestBuilder<com.gabby.studiowebwrapper.worker.PaxgPriceWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(requireContext()).enqueueUniquePeriodicWork("paxg_price", ExistingPeriodicWorkPolicy.KEEP, work)

            // Warm cache once in background (best-effort)
            lifecycleScope.launch {
                try {
                    PaxgPriceRepository.fetchAndCachePricePhp(requireContext())
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
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

    private fun showStampPickerInstructionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.add_karat_stamp_closeup))
            .setMessage(getString(R.string.add_karat_stamp_closeup) + "\n\n" + getString(R.string.improve_tip_closeup))
            .setPositiveButton(getString(R.string.take_photo)) { _, _ ->
                pickStampLauncher.launch("image/*")
            }
            .setNegativeButton(getString(R.string.history_delete_cancel)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun setStampPickerLoading(isLoading: Boolean) {
        binding?.apply {
            addStampButton.isEnabled = !isLoading
            if (isLoading) {
                addStampButton.text = getString(R.string.processing)
            } else {
                addStampButton.text = getString(R.string.add_karat_stamp_closeup)
            }
        }
    }

    private fun applyStampCloseupResult(stamp: com.gabby.studiowebwrapper.util.StampOcrResult) {
        val prev = currentResult ?: return
        val prevTotal = computedTotalScore(prev)
        val stampDetected = stamp.detected && (stamp.confidence >= 35f)
        var updated = prev.copy()
        if (stampDetected) {
            updated = updated.copy(
                stampText = stamp.normalizedStamp ?: updated.stampText,
                stampConfidence = stamp.confidence,
                stampDetected = true
            )
        }

        // Compute visual likelihood similar to the initial grading path and apply same multiplier
        val yolo = prev.yoloScore.coerceIn(0, 100)
        val lbp = prev.lbpScore.coerceIn(0, 100)
        val orb = prev.orbScore.coerceIn(0, 100)
        val hasComponents = yolo > 0 || lbp > 0 || orb > 0
        val visualLikelihood = if (hasComponents) (yolo + lbp + orb) / 3 else prev.qualityScore.coerceIn(0, 100)

        val newTotal = if (stampDetected) (visualLikelihood * 1.15f).coerceIn(0f, 100f).toInt() else prevTotal
        updated = updated.copy(
            qualityScore = newTotal,
            totalComputedScore = newTotal,
            goldValueEstimate = null
        )
        currentResult = updated

        updateUiWithResult(updated, previousScore = prevTotal)

        viewLifecycleOwner.lifecycleScope.launch {
            persistUpdatedHistoryEntry(updated)
        }

        // Show transient confirmation snackbar with details
        val snackMsg = if (stampDetected) {
            getString(R.string.stamp_recognized_msg, stamp.normalizedStamp ?: "unknown", prevTotal, updated.totalComputedScore)
        } else {
            getString(R.string.couldnt_read_stamp)
        }
        val root = binding?.root ?: view ?: requireActivity().window.decorView.rootView
        Snackbar.make(root, snackMsg, Snackbar.LENGTH_LONG).show()
    }

    private suspend fun persistUpdatedHistoryEntry(updated: SuggestMetadataOutput) {
        val context = context ?: return
        // Allow persisting updates for local history entries even if the user is not authenticated.
        // Use the preview URI or sourceHash to locate the original history entry inserted earlier.
        val userId = NativeRepository.getCurrentUser(context)?.id.orEmpty()
        val previewRef = arguments?.getString(ARG_PREVIEW_DATA_URI).orEmpty()
        if (previewRef.isBlank() && updated.sourceHash.isNullOrBlank()) return

        withContext(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getInstance(context).historyDao()
                val existing = if (!updated.sourceHash.isNullOrBlank()) {
                    // Try to find by sourceHash first (most reliable)
                    dao.findLatestByUserAndSourceHash(userId, updated.sourceHash)
                        ?: dao.findLatestByUserAndSourceHash("", updated.sourceHash)
                } else {
                    // Fallback to previewUri. Try with current userId, then without.
                    dao.findLatestByUserAndPreviewUri(userId, previewRef)
                        ?: dao.findLatestByUserAndPreviewUri("", previewRef)
                }

                if (existing == null) return@withContext

                val merged = existing.copy(
                    sourceHash = updated.sourceHash ?: existing.sourceHash,
                    resultJson = Gson().toJson(updated)
                )
                dao.insert(merged)
            } catch (e: Exception) {
                Log.w("GradeResultFragment", "Failed to persist updated history entry", e)
            }
        }
    }

    private fun updateUiWithResult(result: SuggestMetadataOutput, previousScore: Int? = null) {
        binding?.apply {
            val total = computedTotalScore(result)
            val band = likelihoodBandForScore(total)
            val bandRes = bandStringResForScore(total)
            tierBadgeText.text = getString(bandRes)
            tierBadgeText.setBackgroundResource(badgeBackgroundForBand(band))

            if (previousScore != null) {
                ObjectAnimator.ofInt(overallScoreBar, "progress", previousScore, total).setDuration(600).start()
                val delta = total - previousScore
                // Only display the delta change when a karat stamp was detected by the user
                if (result.stampDetected) {
                    val deltaStr = if (delta > 0) "+$delta" else if (delta < 0) "$delta" else "+0"
                    totalScoreText.text = getString(R.string.visual_match_primary, total)
                    totalScoreText.append(" ($deltaStr)")
                    val (rangeMin, rangeMax) = calculateConfidenceRange(result)
                    val halfRange = (rangeMax - rangeMin) / 2
                    totalScoreText.append(" • ${getString(R.string.estimated_range_label)}: ±${halfRange}%")
                } else {
                    totalScoreText.text = getString(R.string.visual_match_primary, total)
                }
            } else {
                overallScoreBar.progress = total
                totalScoreText.text = getString(R.string.visual_match_primary, total)
            }

            // Secondary evidence line
            modelUsedText.text = evidenceSecondaryLine(result)

            materialText.text = resolvedStampLabel(result)
            karatBasisText.text = resolvedStampBasis(result)
            bandShortMessageText.text = getString(bandShortMessageResForScore(total))
            analysisText.text = buildUserFacingAnalysis(result)
            renderValueEstimate(result)
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
