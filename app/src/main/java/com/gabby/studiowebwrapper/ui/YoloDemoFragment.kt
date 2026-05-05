package com.gabby.studiowebwrapper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.util.VanillaVisionPipeline
import org.opencv.android.OpenCVLoader
import androidx.lifecycle.ViewModelProvider
import com.gabby.studiowebwrapper.data.Report
import com.gabby.studiowebwrapper.data.ReportViewModel
import com.google.gson.Gson

class YoloDemoFragment : Fragment() {

    private var pipeline: VanillaVisionPipeline? = null
    private lateinit var reportVm: ReportViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_yolo_demo, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val imageView = view.findViewById<ImageView>(R.id.imageViewInput)
        val runBtn = view.findViewById<Button>(R.id.buttonRun)
        val resultText = view.findViewById<TextView>(R.id.textViewResult)
        val debugText = view.findViewById<TextView>(R.id.textViewDebug)

        // Initialize OpenCV and baseline pipeline.
        OpenCVLoader.initDebug()
        pipeline = VanillaVisionPipeline(requireContext())
        resultText.text = "Pipeline ready"
        debugText.text = "Debug\nOpenCV initialized\nPipeline initialized"

        // init report viewmodel
        reportVm = ViewModelProvider(requireActivity()).get(ReportViewModel::class.java)

        // Try to load sample image from assets/sample.jpg; fallback to blank bitmap
        val placeholder: Bitmap = try {
            requireContext().assets.open("sample.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        }

        imageView.setImageBitmap(placeholder)

        runBtn.setOnClickListener {
            runInference(placeholder, resultText, debugText)
        }
    }

    private fun runInference(placeholder: Bitmap, resultText: TextView, debugText: TextView) {
        resultText.text = "Running..."
        try {
            val analysis = pipeline?.analyze(placeholder)
            if (analysis == null) {
                resultText.text = "Pipeline not initialized"
                debugText.text = "Debug\nPipeline is null"
                return
            }

            val sb = StringBuilder()
            sb.append("Detections: ${analysis.detections.size}\n")
            analysis.detections.forEachIndexed { i, d ->
                sb.append("#$i class=${d.classId} score=${"%.2f".format(d.score)} bbox=${d.x1},${d.y1},${d.x2},${d.y2}\n")
            }
            sb.append("Query ORB descriptors: ${analysis.queryOrbDescriptorRows}\n")
            sb.append("Query LBP bins: ${analysis.queryLbpBins}\n")

            val top = analysis.topScore
            if (top != null) {
                sb.append("\nTop reference: ${top.referenceName} score=${"%.3f".format(top.finalScore)} (lbp=${"%.3f".format(top.lbpScore)}, orb=${"%.3f".format(top.orbScore)})\n")

                // persist report
                try {
                    val gson = Gson()
                    val yoloJson = gson.toJson(analysis.detections)
                    val report = Report(
                        timestamp = System.currentTimeMillis(),
                        imagePath = null,
                        yoloJson = yoloJson,
                        topReference = top.referenceName,
                        lbpScore = top.lbpScore,
                        orbScore = top.orbScore,
                        finalScore = top.finalScore
                    )
                    reportVm.insert(report) { id ->
                        sb.append("Saved report id=$id\n")
                        resultText.text = sb.toString()
                    }
                } catch (e: Exception) {
                    sb.append("\nFailed to save report: ${e.message}\n")
                }
            } else {
                sb.append("\nNo matched reference (extraction-only mode)\n")
            }

            analysis.notes.forEach { note -> sb.append("- $note\n") }

            val dbg = StringBuilder()
            dbg.append("Debug\n")
            dbg.append("YOLO available: ${analysis.yoloAvailable}\n")
            dbg.append("YOLO status: ${analysis.yoloStatus}\n")
            dbg.append("Grader available: ${analysis.graderAvailable}\n")
            dbg.append("Grader status: ${analysis.graderStatus}\n")
            dbg.append("References loaded: ${analysis.hasReferences}\n")
            dbg.append("Mode: ${if (analysis.topScore != null) "Matching" else "Extraction-only"}\n")
            dbg.append("Detections: ${analysis.detections.size}\n")
            if (analysis.notes.isNotEmpty()) {
                dbg.append("Notes:\n")
                analysis.notes.forEach { note -> dbg.append("- $note\n") }
            }
            debugText.text = dbg.toString()

            resultText.text = sb.toString()
        } catch (e: Exception) {
            resultText.text = "Inference failed: ${e.message}"
            debugText.text = "Debug\nRun failed: ${e.message}"
        }
    }
}
