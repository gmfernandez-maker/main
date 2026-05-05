package com.gabby.studiowebwrapper.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.Report
import com.gabby.studiowebwrapper.data.ReportViewModel
import com.google.android.material.snackbar.Snackbar


class ReportsFragment : Fragment() {

    private lateinit var reportVm: ReportViewModel
    private lateinit var adapter: ReportAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_reports, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerViewReports)
        val emptyView = view.findViewById<TextView>(R.id.textViewEmpty)

        adapter = ReportAdapter { report ->
            // open detail fragment for selected report
            val frag = ReportDetailFragment.newInstance(report.id)
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, frag)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        reportVm = ViewModelProvider(requireActivity()).get(ReportViewModel::class.java)
        reportVm.getAll().observe(viewLifecycleOwner) { list ->
            if (list.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                recycler.visibility = View.GONE
                adapter.submitList(list)
            } else {
                emptyView.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                adapter.submitList(list)
            }
        }

        // enable swipe-to-delete with undo
        val itemTouchCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val deleted = adapter.getReportAt(pos)
                val dlg = AlertDialog.Builder(requireContext())
                    .setTitle("Delete report?")
                    .setMessage("Delete report #${deleted.id}?")
                    .setPositiveButton("Delete") { _, _ ->
                        adapter.removeAt(pos)
                        reportVm.delete(deleted)
                        Snackbar.make(requireView(), "Deleted report #${deleted.id}", Snackbar.LENGTH_LONG)
                            .setAction("UNDO") {
                                val toInsert = deleted.copy(id = 0L)
                                reportVm.insert(toInsert) { /* no-op */ }
                            }.show()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        adapter.notifyItemChanged(pos)
                    }
                    .setOnCancelListener { adapter.notifyItemChanged(pos) }
                dlg.show()
            }
        }
        val touchHelper = ItemTouchHelper(itemTouchCallback)
        touchHelper.attachToRecyclerView(recycler)
    }
}
