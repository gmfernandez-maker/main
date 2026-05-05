package com.gabby.studiowebwrapper.ui

import android.content.Context
import android.util.Log
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.AppDatabase
import com.gabby.studiowebwrapper.data.HistoryEntry
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.databinding.FragmentHomeBinding
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.google.gson.Gson
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    interface Callbacks {
        fun navigateToUploadForm()
        fun navigateToAccountScreen()
        fun navigateToHistoryScreen()
        fun navigateToGradeDetail(resultJson: String, previewUri: String)
    }

    private var callbacks: Callbacks? = null
    private var binding: FragmentHomeBinding? = null
    private val gson = Gson()
    private var latestEntry: HistoryEntry? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get data from arguments if available (use safe access)
        val recentResultJson = arguments?.getString("recentResultJson")
        val recentPreviewUri = arguments?.getString("recentPreviewUri")

        binding?.apply {
            viewDetailButton.setOnClickListener {
                val argResult = arguments?.getString("recentResultJson")
                val argPreview = arguments?.getString("recentPreviewUri")
                if (!argResult.isNullOrBlank() && !argPreview.isNullOrBlank()) {
                    callbacks?.navigateToGradeDetail(argResult, argPreview)
                } else if (latestEntry != null) {
                    callbacks?.navigateToGradeDetail(latestEntry!!.resultJson, latestEntry!!.previewUri)
                } else {
                    android.widget.Toast.makeText(requireContext(), "No recent results. Please grade an item.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            // Display recent result if available
            if (recentResultJson != null && recentPreviewUri != null) {
                displayRecentResult(recentResultJson, recentPreviewUri)
            }

            // Load and display past results and stats from Room history.
            loadPastResultsAndStats(recentResultJson, recentPreviewUri)
        }
    }

    private fun displayRecentResult(resultJson: String, previewUri: String) {
        val result = gson.fromJson(resultJson, SuggestMetadataOutput::class.java)
        binding?.apply {
            try {
                recentPreviewImage.load(previewUri)
            } catch (e: Exception) {
                Log.e("HomeFragment", "Failed to load recent preview: $previewUri", e)
                recentPreviewImage.setImageDrawable(null)
            }
            recentScoreText.text = "${result.qualityScore}/100"
            val purity = result.purity?.ifBlank { "Unknown" } ?: "Unknown"
            val hasStampEvidence = result.stampDetected && !result.stampText.isNullOrBlank()
            recentMaterialText.text = if (hasStampEvidence) {
                "$purity (${result.stampText}, ${result.stampConfidence}%)"
            } else {
                "$purity (No Stamp Evidence)"
            }
            recentMaterialText.setBackgroundResource(if (hasStampEvidence) R.drawable.bg_badge_tier_a else R.drawable.bg_badge_tier_d)
            recentMaterialText.setTextColor(resources.getColor(if (hasStampEvidence) R.color.jg_gold_light else R.color.jg_text_secondary, null))
            recentAnalysisText.text = result.analysis
        }
    }

    private data class HistoryCard(
        val title: String,
        val scoreLabel: String,
        val karatLabel: String,
        val stampLabel: String,
        val resultJson: String,
        val previewUri: String,
        val timestamp: Long,
        val scoreValue: Int?
    )

    private fun loadPastResultsAndStats(recentResultJson: String?, recentPreviewUri: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) {
                val userId = NativeRepository.getCurrentUser(requireContext())?.id ?: return@withContext emptyList<HistoryCard>()
                val entries = AppDatabase.getInstance(requireContext()).historyDao().getAllForUserOnce(userId)
                entries.mapNotNull { entry ->
                    runCatching {
                        val parsed = gson.fromJson(entry.resultJson, SuggestMetadataOutput::class.java)
                        val score = parsed.qualityScore
                        val karat = parsed.purity?.ifBlank { "Unknown" } ?: "Unknown"
                        val stampLabel = if (parsed.stampDetected && !parsed.stampText.isNullOrBlank()) {
                            "${parsed.stampText} (${parsed.stampConfidence}%)"
                        } else {
                            "No Stamp Evidence"
                        }
                        val title = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(entry.timestamp))
                        HistoryCard(
                            title = title,
                            scoreLabel = if (score > 0) score.toString() else "N/A",
                            karatLabel = karat,
                            stampLabel = stampLabel,
                            resultJson = entry.resultJson,
                            previewUri = entry.previewUri,
                            timestamp = entry.timestamp,
                            scoreValue = if (score > 0) score else null
                        )
                    }.getOrNull()
                }
            }

            if (!isAdded) return@launch

            binding?.apply {
                if (cards.isNotEmpty()) {
                    if (recentResultJson.isNullOrBlank() || recentPreviewUri.isNullOrBlank()) {
                        val first = cards.first()
                        displayRecentResult(first.resultJson, first.previewUri)
                    }

                    latestEntry = HistoryEntry(
                        resultJson = cards.first().resultJson,
                        previewUri = cards.first().previewUri,
                        userId = NativeRepository.getCurrentUser(requireContext())?.id ?: "",
                        timestamp = cards.first().timestamp
                    )

                    updateStatTiles(cards)

                    pastResultsContainer.removeAllViews()
                    val listToShow = cards.take(10)
                    listToShow.forEach { card -> pastResultsContainer.addView(createResultCard(card)) }
                    emptyPastResultsView.visibility = View.GONE
                } else {
                    latestEntry = null
                    homeTotalValue.text = "0"
                    homeAvgValue.text = "0"
                    homeTopValue.text = "N/A"
                    pastResultsContainer.removeAllViews()
                    emptyPastResultsView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun updateStatTiles(cards: List<HistoryCard>) {
        val scores = cards.mapNotNull { it.scoreValue }
        val avg = if (scores.isNotEmpty()) scores.average() else 0.0
        val topKarat = cards
            .groupingBy { it.karatLabel }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "N/A"

        binding?.homeTotalValue?.text = cards.size.toString()
        binding?.homeAvgValue?.text = if (scores.isEmpty()) "0" else String.format("%.1f", avg)
        binding?.homeTopValue?.text = topKarat
    }

    private fun createResultCard(card: HistoryCard): View {
        val resultData = mapOf(
            "title" to card.title,
            "score" to card.scoreLabel,
            "karat" to card.karatLabel,
            "stamp" to card.stampLabel
        )

        val cardView = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            setBackgroundResource(R.drawable.bg_dashboard_tile)
            isClickable = true
            isFocusable = true
        }

        val titleView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = resultData["title"] ?: ""
            setTextColor(resources.getColor(R.color.jg_gold_light, null))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val scoreView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
            text = "YOLOv8 Score: ${resultData["score"] ?: "N/A"}"
            setTextColor(resources.getColor(R.color.jg_text_primary, null))
            textSize = 12.5f
        }

        val materialView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4
            }
            text = "Predicted Karat: ${resultData["karat"] ?: "N/A"} | Stamp: ${resultData["stamp"] ?: "N/A"}"
            setTextColor(resources.getColor(R.color.jg_text_secondary, null))
            textSize = 12f
        }

        val stampDetected = card.stampLabel != "No Stamp Evidence"
        materialView.setBackgroundResource(if (stampDetected) R.drawable.bg_badge_tier_a else R.drawable.bg_badge_tier_d)
        materialView.setPadding(12, 10, 12, 10)
        materialView.setTextColor(resources.getColor(if (stampDetected) R.color.jg_gold_light else R.color.jg_text_secondary, null))

        cardView.addView(titleView)
        cardView.addView(scoreView)
        cardView.addView(materialView)

        cardView.setOnClickListener {
            callbacks?.navigateToGradeDetail(card.resultJson, card.previewUri)
        }

        return cardView
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        callbacks = null
        super.onDetach()
    }

    companion object {
        fun newInstance(): HomeFragment {
            return HomeFragment().apply {
                arguments = Bundle()
            }
        }

        fun newInstanceWithResult(resultJson: String, previewUri: String): HomeFragment {
            return HomeFragment().apply {
                arguments = Bundle().apply {
                    putString("recentResultJson", resultJson)
                    putString("recentPreviewUri", previewUri)
                }
            }
        }
    }
}
