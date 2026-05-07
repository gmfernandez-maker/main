package com.gabby.studiowebwrapper.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.AppDatabase
import com.gabby.studiowebwrapper.data.FeedbackEntry
import com.gabby.studiowebwrapper.data.NativeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class AdminFeedbackFragment : Fragment() {

    private var container: LinearLayout? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_admin_feedback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.adminFeedbackList)
        loadFeedbackEntries()
    }

    private fun loadFeedbackEntries() {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(requireContext()).feedbackDao().getAllOrdered()
            }
            renderEntries(entries)
        }
    }

    private fun renderEntries(entries: List<FeedbackEntry>) {
        container?.removeAllViews()
        if (entries.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "No feedback available"
                setTextColor(resources.getColor(R.color.jg_text_secondary, null))
            }
            container?.addView(empty)
            return
        }

        entries.forEach { e ->
            container?.addView(createCardFor(e))
        }
    }

    private fun createCardFor(e: FeedbackEntry): View {
        val card = layoutInflater.inflate(R.layout.item_feedback_card, null)
        val userView = card.findViewById<TextView>(R.id.fbUser)
        val selView = card.findViewById<TextView>(R.id.fbSelection)
        val commentView = card.findViewById<TextView>(R.id.fbComment)
        val metaView = card.findViewById<TextView>(R.id.fbMeta)

        val userLabel = if (e.userId.isBlank()) "Anonymous" else e.userId
        userView.text = userLabel
        selView.text = e.selection
        commentView.text = e.comment ?: ""
        val ts = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.timestamp))
        metaView.text = "Confidence: ${e.modelConfidence}% • Routed: ${e.routedTo ?: "none"} • $ts"

        return card
    }

    override fun onDestroyView() {
        container = null
        super.onDestroyView()
    }
}
