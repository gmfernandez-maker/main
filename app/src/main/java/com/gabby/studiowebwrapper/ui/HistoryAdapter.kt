package com.gabby.studiowebwrapper.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
// MaterialButton removed: delete action no longer shown on history card
import com.google.android.material.progressindicator.CircularProgressIndicator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.HistoryEntry
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.google.gson.Gson
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import com.gabby.studiowebwrapper.data.PaxgPriceRepository
import androidx.core.content.ContextCompat

class HistoryAdapter(
    private val onClick: (HistoryEntry) -> Unit = {},
    private val onDelete: (HistoryEntry) -> Unit = {}
) : ListAdapter<HistoryEntry, HistoryAdapter.ViewHolder>(DIFF) {

    private fun tierForScore(score: Int): String {
        return when {
            score >= 85 -> "Tier A"
            score >= 70 -> "Tier B"
            score >= 55 -> "Tier C"
            else -> "Tier D"
        }
    }

    private fun clampScore(score: Int): Int = score.coerceIn(0, 100)

    private fun computedTotalScore(result: SuggestMetadataOutput?): Int {
        if (result == null) return 0
        val persistedTotal = result.totalComputedScore.coerceIn(0, 100)
        if (persistedTotal > 0) return persistedTotal
        val yolo = result.yoloScore.coerceIn(0, 100)
        val lbp = result.lbpScore.coerceIn(0, 100)
        val orb = result.orbScore.coerceIn(0, 100)
        val hasComponents = yolo > 0 || lbp > 0 || orb > 0
        return if (hasComponents) ((yolo + lbp + orb) / 3) else clampScore(result.qualityScore)
    }

    private fun badgeBackgroundForTier(tier: String): Int {
        return when (tier) {
            "Tier A" -> R.drawable.bg_badge_tier_a
            "Tier B" -> R.drawable.bg_badge_tier_b
            "Tier C" -> R.drawable.bg_badge_tier_c
            else -> R.drawable.bg_badge_tier_d
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HistoryEntry>() {
            override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean = oldItem == newItem
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.imageThumb)
        val title: TextView = itemView.findViewById(R.id.textTitle)
        val date: TextView = itemView.findViewById(R.id.textDate)
        val valueSaved: TextView = itemView.findViewById(R.id.textValueSaved)
        val valueLive: TextView = itemView.findViewById(R.id.textValueLive)
        val valueUplift: TextView = itemView.findViewById(R.id.textValueUplift)
        val score: TextView = itemView.findViewById(R.id.textScore)
        val confidenceGauge: CircularProgressIndicator = itemView.findViewById(R.id.confidenceGauge)
        val grade: TextView = itemView.findViewById(R.id.textGrade)
        val badge: TextView = itemView.findViewById(R.id.textBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.itemView.setOnClickListener { onClick(item) }
        // delete button removed

        // Load thumbnail file and ensure rounded clipping works
        try {
            val f = File(item.previewUri)
            if (f.exists()) {
                holder.thumb.load(f)
                holder.thumb.clipToOutline = true
            } else holder.thumb.setImageDrawable(null)
        } catch (e: Exception) {
            holder.thumb.setImageDrawable(null)
        }

        val gson = Gson()
        val result = try { gson.fromJson(item.resultJson, SuggestMetadataOutput::class.java) } catch (e: Exception) { null }
        val purity = result?.purity?.ifBlank { "Unknown" } ?: "Unknown"
        val material = result?.material?.ifBlank { "Item" } ?: "Item"
        holder.title.text = "$purity $material"
        holder.date.text = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", java.util.Date(item.timestamp))
        val scoreValue = computedTotalScore(result)

        val estimate = result?.goldValueEstimate
        if (estimate != null) {
            val amountFormatter = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
            // Show saved resale and a live recalculated resale (if cached price available)
            val saved = "₱${amountFormatter.format(estimate.resaleMidPhp)}"
            holder.valueSaved.text = holder.itemView.context.getString(R.string.history_value_saved_label) + ": " + saved

            val TROY_OUNCE_GRAMS = 31.1034768
            val cachedPrice = PaxgPriceRepository.getCachedPricePhp(holder.itemView.context)
            if (cachedPrice != null && cachedPrice > 0.0) {
                val perGramCurrent = cachedPrice / TROY_OUNCE_GRAMS
                val resaleMultiplier = 1.0 + (estimate.resaleUpliftPercent / 100.0)
                val liveResaleMid = estimate.pureGoldGrams * perGramCurrent * resaleMultiplier
                holder.valueLive.text = holder.itemView.context.getString(R.string.history_value_live_label) + ": ₱${amountFormatter.format(liveResaleMid)}"
            } else {
                // No cache: show saved as live too (muted)
                holder.valueLive.text = holder.itemView.context.getString(R.string.history_value_live_label) + ": " + saved
            }
            // Compute market change vs cached PAXG token price (if available)
            val cachedTokenPrice = PaxgPriceRepository.getCachedPricePhp(holder.itemView.context)
            if (cachedTokenPrice != null && estimate.paxgPricePhp > 0.0) {
                val diff = cachedTokenPrice - estimate.paxgPricePhp
                val pct = (diff / estimate.paxgPricePhp) * 100.0
                val arrow = if (pct >= 0) "▲" else "▼"
                val pctAbs = kotlin.math.abs(pct)
                val pctStr = String.format(Locale.getDefault(), "%s %.1f%%", arrow, pctAbs)
                holder.valueUplift.text = pctStr
                val colorRes = if (pct >= 0) R.color.jg_positive else R.color.jg_negative
                try {
                    holder.valueUplift.setTextColor(ContextCompat.getColor(holder.itemView.context, colorRes))
                } catch (_: Exception) {}
            } else {
                // Fallback: show the model uplift percent if market data not available
                holder.valueUplift.text = holder.itemView.context.getString(R.string.history_value_uplift, estimate.resaleUpliftPercent)
                try {
                    holder.valueUplift.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.jg_positive))
                } catch (_: Exception) {}
            }
        } else {
            holder.valueSaved.text = holder.itemView.context.getString(R.string.history_value_saved_label) + ": " + holder.itemView.context.getString(R.string.history_value_not_available)
            holder.valueLive.text = ""
            holder.valueUplift.text = ""
        }

        val stampDetected = result?.stampDetected == true && !result.stampText.isNullOrBlank()
        holder.score.text = holder.itemView.context.getString(R.string.history_score_label, scoreValue)
        // ensure gauge range and set progress with animation
        try {
            holder.confidenceGauge.max = 100
        } catch (e: Exception) { /* some older libs may not expose max property */ }
        holder.confidenceGauge.setProgressCompat(scoreValue, true)
        val tier = tierForScore(scoreValue)
        holder.grade.text = tier
        holder.badge.text = if (stampDetected) "KARAT STAMP DETECTED" else "NO KARAT STAMP"
        holder.badge.setBackgroundResource(if (stampDetected) R.drawable.bg_badge_tier_a else R.drawable.bg_badge_tier_d)
    }
}
