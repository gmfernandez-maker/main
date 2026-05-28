package com.gabby.studiowebwrapper.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.HistoryEntry
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import androidx.lifecycle.lifecycleScope
import com.gabby.studiowebwrapper.data.PaxgPriceRepository
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    interface Callbacks {
        fun navigateToGradeDetail(resultJson: String, previewUri: String)
    }

    private var callbacks: Callbacks? = null
    private lateinit var viewModel: HistoryViewModel
    private lateinit var adapter: HistoryAdapter
    private val gson = Gson()
    private var selectedTier: String = TIER_ALL
    private var allEntries: List<HistoryEntry> = emptyList()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerHistory)
        val tierChipGroup = view.findViewById<ChipGroup>(R.id.filterChipGroup)
        val swipe = view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefresh)
        adapter = HistoryAdapter(
            onClick = { entry: HistoryEntry ->
                callbacks?.navigateToGradeDetail(entry.resultJson, entry.previewUri)
            },
            onDelete = { entry: HistoryEntry ->
                showDeleteConfirmation(entry)
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        attachSwipeToDelete(recycler)
        setupTierFilter(tierChipGroup)

        viewModel = ViewModelProvider(this).get(HistoryViewModel::class.java)
        viewModel.history.observe(viewLifecycleOwner) { list ->
            allEntries = list
            applyTierFilter()
        }

        // Pull-to-refresh: refresh cached PAXG price and update live prices shown in history
        swipe.setOnRefreshListener {
            swipe.isRefreshing = true
            viewLifecycleOwner.lifecycleScope.launch {
                val fetched = try { PaxgPriceRepository.fetchAndCachePricePhp(requireContext()) } catch (e: Exception) { null }
                // Force adapter rebind so live prices are recomputed using the new cache
                adapter.notifyDataSetChanged()
                swipe.isRefreshing = false
                val msg = if (fetched != null) getString(R.string.price_refreshed) else getString(R.string.price_refresh_failed)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTierFilter(chipGroup: ChipGroup) {
        chipGroup.removeAllViews()

        val labels = listOf(TIER_ALL, "Tier A", "Tier B", "Tier C", "Tier D")
        val createdChips = labels.map { label ->
            Chip(requireContext()).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isClickable = true
                checkedIcon = null
                setTextColor(ContextCompat.getColor(requireContext(), R.color.jg_text_primary))
            }
        }

        createdChips.forEach { chipGroup.addView(it) }
        createdChips.firstOrNull()?.isChecked = true

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) {
                selectedTier = TIER_ALL
                applyTierFilter()
                return@setOnCheckedStateChangeListener
            }

            val selectedChip = group.findViewById<Chip>(checkedIds.first())
            selectedTier = selectedChip?.text?.toString().orEmpty().ifBlank { TIER_ALL }
            applyTierFilter()
        }
    }

    private fun applyTierFilter() {
        if (selectedTier == TIER_ALL) {
            adapter.submitList(allEntries)
            return
        }

        val filtered = allEntries.filter { entry ->
            tierForEntry(entry) == selectedTier
        }
        adapter.submitList(filtered)
    }

    private fun tierForEntry(entry: HistoryEntry): String {
        val result = runCatching { gson.fromJson(entry.resultJson, SuggestMetadataOutput::class.java) }.getOrNull()
        val score = (result?.totalComputedScore ?: result?.qualityScore ?: 0).coerceIn(0, 100)
        return when {
            score >= 85 -> "Tier A"
            score >= 70 -> "Tier B"
            score >= 55 -> "Tier C"
            else -> "Tier D"
        }
    }

    private fun showDeleteConfirmation(entry: HistoryEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_delete_title)
            .setMessage(R.string.history_delete_message)
            .setNegativeButton(R.string.history_delete_cancel, null)
            .setPositiveButton(R.string.history_delete_confirm) { _, _ ->
                viewModel.delete(entry)
                Toast.makeText(requireContext(), R.string.history_delete_success, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun attachSwipeToDelete(recycler: RecyclerView) {
        val swipePaint = Paint().apply { color = Color.parseColor("#D32F2F") }
        val deleteIcon = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_delete)?.mutate()?.apply {
            setTint(Color.WHITE)
        }

        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val icon = deleteIcon
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    if (dX > 0f) {
                        c.drawRect(
                            itemView.left.toFloat(),
                            itemView.top.toFloat(),
                            itemView.left + dX,
                            itemView.bottom.toFloat(),
                            swipePaint
                        )
                    } else {
                        c.drawRect(
                            itemView.right + dX,
                            itemView.top.toFloat(),
                            itemView.right.toFloat(),
                            itemView.bottom.toFloat(),
                            swipePaint
                        )
                    }

                    if (icon != null) {
                        val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = iconTop + icon.intrinsicHeight

                        if (dX > 0f) {
                            val iconLeft = itemView.left + iconMargin
                            val iconRight = iconLeft + icon.intrinsicWidth
                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        } else {
                            val iconRight = itemView.right - iconMargin
                            val iconLeft = iconRight - icon.intrinsicWidth
                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        }
                        icon.draw(c)
                    }
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position >= adapter.currentList.size) {
                    adapter.notifyItemChanged(viewHolder.absoluteAdapterPosition)
                    return
                }

                val entry = adapter.currentList[position]
                viewModel.delete(entry)

                Snackbar.make(recycler, R.string.history_deleted_swipe, Snackbar.LENGTH_LONG)
                    .setAction(R.string.history_undo) {
                        viewModel.insert(entry)
                    }
                    .show()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

    companion object {
        private const val TIER_ALL = "All"
    }

    override fun onDetach() {
        callbacks = null
        super.onDetach()
    }
}
