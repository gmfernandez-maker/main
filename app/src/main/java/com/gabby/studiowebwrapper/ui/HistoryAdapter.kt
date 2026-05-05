package com.gabby.studiowebwrapper.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.HistoryEntry
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.google.gson.Gson
import java.io.File

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
        val score: TextView = itemView.findViewById(R.id.textScore)
        val grade: TextView = itemView.findViewById(R.id.textGrade)
        val badge: TextView = itemView.findViewById(R.id.textBadge)
        val deleteButton: MaterialButton = itemView.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.deleteButton.setOnClickListener { onDelete(item) }

        // Load thumbnail file
        try {
            val f = File(item.previewUri)
            if (f.exists()) holder.thumb.load(f) else holder.thumb.setImageDrawable(null)
        } catch (e: Exception) {
            holder.thumb.setImageDrawable(null)
        }

        val gson = Gson()
        val result = try { gson.fromJson(item.resultJson, SuggestMetadataOutput::class.java) } catch (e: Exception) { null }
        val purity = result?.purity?.ifBlank { "Unknown" } ?: "Unknown"
        val material = result?.material?.ifBlank { "Item" } ?: "Item"
        holder.title.text = "Predicted: $purity $material"
        holder.date.text = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", java.util.Date(item.timestamp))
        val scoreValue = result?.qualityScore
        val stampText = if (result?.stampDetected == true && !result.stampText.isNullOrBlank()) {
            "${result.stampText} (${result.stampConfidence}%)"
        } else {
            "No Stamp Evidence"
        }
        val stampDetected = result?.stampDetected == true && !result.stampText.isNullOrBlank()
        holder.score.text = scoreValue?.let { "Score: $it | Stamp: $stampText" } ?: "Score: N/A | Stamp: $stampText"
        if (scoreValue != null) {
            val tier = tierForScore(scoreValue)
            holder.grade.text = "Grade: $tier"
            holder.badge.text = if (stampDetected) "Stamp Verified" else "No Stamp Evidence"
            holder.badge.setBackgroundResource(if (stampDetected) R.drawable.bg_badge_tier_a else R.drawable.bg_badge_tier_d)
        } else {
            holder.grade.text = "Grade: Unknown"
            holder.badge.text = if (stampDetected) "Stamp Verified" else "No Stamp Evidence"
            holder.badge.setBackgroundResource(if (stampDetected) R.drawable.bg_badge_tier_a else R.drawable.bg_badge_tier_d)
        }
    }
}
