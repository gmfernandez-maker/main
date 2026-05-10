package com.gabby.studiowebwrapper.util

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileWriter

/**
 * Debug utility to batch-process images from a directory using the on-device pipeline.
 * Writes a CSV of MaterialClassifier diagnostics to the app's external files directory.
 * Usage: call from an Activity or background worker with a readable File directory
 * containing images (JPEG/PNG). The app needs READ_EXTERNAL_STORAGE on older Android versions
 * or the files must be inside the app's files area.
 */
object DebugBatchRunner {

    data class ResultRow(
        val filename: String,
        val goldScore: Float,
        val silverScore: Float,
        val colorBias: Float,
        val predicted: String,
        val confidence: Float
    )

    /**
     * Process all supported images under [imagesDir] and write diagnostics to CSV.
     * Returns the CSV file written.
     */
    fun processImagesToCsv(context: Context, imagesDir: File, referenceData: List<ReferenceData>): File {
        val outDir = context.getExternalFilesDir("debug") ?: context.filesDir
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "material_diagnostics.csv")

        FileWriter(outFile).use { writer ->
            writer.append("filename,goldScore,silverScore,colorBias,predicted,confidence\n")

            val images = imagesDir.listFiles()
                ?.filter {
                    it.isFile && run {
                        val name = it.name.lowercase()
                        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                    }
                }
                ?.sortedBy { it.name }
                ?: emptyList()

            for (f in images) {
                try {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue

                    // Call the same classifier used in the app
                    val score = MaterialClassifier.classify(bmp, referenceData)

                    val row = ResultRow(
                        filename = f.name,
                        goldScore = score.goldScore,
                        silverScore = score.silverScore,
                        colorBias = score.colorBias,
                        predicted = score.predicted,
                        confidence = score.confidence
                    )

                    writer.append("${row.filename},${row.goldScore},${row.silverScore},${row.colorBias},${row.predicted},${row.confidence}\n")
                    writer.flush()
                } catch (e: Exception) {
                    Log.w("DebugBatchRunner", "failed to process ${f.name}: ${e.message}")
                }
            }
        }

        Log.d("DebugBatchRunner", "Wrote diagnostics to ${outFile.absolutePath}")
        return outFile
    }
}
