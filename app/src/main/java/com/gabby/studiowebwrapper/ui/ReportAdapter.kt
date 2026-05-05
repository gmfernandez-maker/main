package com.gabby.studiowebwrapper.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.Report
import java.text.SimpleDateFormat
import java.util.*

class ReportAdapter(private val onClick: (Report) -> Unit = {}) : ListAdapter<Report, ReportAdapter.ViewHolder>(DIFF) {

    private fun tierForScore(score: Float): String {
        return when {
            score >= 0.85f -> "Tier A"
            score >= 0.70f -> "Tier B"
            score >= 0.55f -> "Tier C"
            else -> "Tier D"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Report>() {
            override fun areItemsTheSame(oldItem: Report, newItem: Report): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Report, newItem: Report): Boolean = oldItem == newItem
        }
    }

    init {
        setHasStableIds(true)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.textReportTitle)
        val details: TextView = itemView.findViewById(R.id.textReportDetails)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_report, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val r = getItem(position)
        holder.title.text = "Report #${r.id}"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val ts = sdf.format(Date(r.timestamp))
        val tier = tierForScore(r.finalScore)
        holder.details.text = "time=$ts\nbadge=$tier\nref=${r.topReference ?: "-"}\nfinal=${"%.3f".format(r.finalScore)}  lbp=${"%.3f".format(r.lbpScore)}  orb=${"%.3f".format(r.orbScore)}"
        holder.itemView.setOnClickListener { onClick(r) }
    }

    fun getReportAt(position: Int): Report = getItem(position)

    fun removeAt(position: Int): Report {
        val mutable = currentList.toMutableList()
        val r = mutable.removeAt(position)
        submitList(mutable)
        return r
    }

    fun addAt(position: Int, report: Report) {
        val mutable = currentList.toMutableList()
        val pos = position.coerceIn(0, mutable.size)
        mutable.add(pos, report)
        submitList(mutable)
    }
}
