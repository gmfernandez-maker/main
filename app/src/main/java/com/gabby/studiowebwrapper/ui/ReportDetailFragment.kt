package com.gabby.studiowebwrapper.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.ReportViewModel
import java.text.SimpleDateFormat
import java.util.*

class ReportDetailFragment : Fragment() {

    private fun tierForScore(score: Float): String {
        return when {
            score >= 0.85f -> "Tier A"
            score >= 0.70f -> "Tier B"
            score >= 0.55f -> "Tier C"
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

    private fun gradingCriteriaText(): String {
        return "Grading Criteria\n" +
            "Tier A: final score >= 0.85\n" +
            "Tier B: final score >= 0.70\n" +
            "Tier C: final score >= 0.55\n" +
            "Tier D: final score < 0.55"
    }

    companion object {
        private const val ARG_REPORT_ID = "report_id"
        fun newInstance(reportId: Long): ReportDetailFragment {
            val f = ReportDetailFragment()
            val args = Bundle()
            args.putLong(ARG_REPORT_ID, reportId)
            f.arguments = args
            return f
        }
    }

    private lateinit var reportVm: ReportViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_report_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val title = view.findViewById<TextView>(R.id.textDetailTitle)
        val meta = view.findViewById<TextView>(R.id.textDetailMeta)
        val badge = view.findViewById<TextView>(R.id.textDetailBadge)
        val criteria = view.findViewById<TextView>(R.id.textDetailCriteria)
        val body = view.findViewById<TextView>(R.id.textDetailBody)

        criteria.text = gradingCriteriaText()
        badge.setOnClickListener {
            criteria.visibility = if (criteria.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        reportVm = ViewModelProvider(requireActivity()).get(ReportViewModel::class.java)
        val id = arguments?.getLong(ARG_REPORT_ID) ?: -1L
        if (id < 0) {
            body.text = "Invalid report id"
            return
        }

        reportVm.getById(id).observe(viewLifecycleOwner) { r ->
            try {
                if (r == null) {
                    body.text = "Report not found"
                    return@observe
                }
                title.text = "Report #${r.id}"
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val ts = sdf.format(Date(r.timestamp))
                val tier = tierForScore(r.finalScore)
                badge.text = tier
                badge.setBackgroundResource(badgeBackgroundForTier(tier))
                meta.text = "Time: $ts\nReference: ${r.topReference ?: "-"}\nFinal Grade: ${"%.3f".format(r.finalScore)}\nLBP Grade: ${"%.3f".format(r.lbpScore)}\nORB Grade: ${"%.3f".format(r.orbScore)}\nTap badge to view grading criteria"
                body.text = r.yoloJson
            } catch (e: Exception) {
                Log.e("ReportDetailFragment", "Error displaying report id=$id", e)
                try { body.text = "Error displaying report: ${e.message}" } catch (_: Exception) {}
            }
        }
    }
}
