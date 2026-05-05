package com.gabby.studiowebwrapper.util

import android.content.Context
import com.google.gson.Gson
import org.opencv.core.CvType
import org.opencv.core.Mat

data class ReferenceData(val name: String, val lbpHist: FloatArray, val orbDescriptors: Mat?)

private data class ReferenceJson(val name: String?, val lbp_hist: List<Float>?, val orb_desc: List<List<Int>>?)

object ReferenceManager {

    fun loadAll(
        context: Context,
        folder: String = "references"
    ): List<ReferenceData> {
        val assetList = try { context.assets.list(folder) ?: arrayOf() } catch (e: Exception) { arrayOf<String>() }
        val gson = Gson()
        val results = mutableListOf<ReferenceData>()
        for (file in assetList) {
            if (!file.endsWith(".json")) continue
            val path = "$folder/$file"
            try {
                val text = context.assets.open(path).bufferedReader().use { it.readText() }
                val parsed = gson.fromJson(text, ReferenceJson::class.java)
                val name = parsed.name ?: file.removeSuffix(".json")
                var lbp = parsed.lbp_hist ?: listOf()

                var orbMat: Mat? = if (parsed.orb_desc != null && parsed.orb_desc.isNotEmpty()) {
                    // only construct OpenCV Mat if descriptor data is present; avoid creating Mat() when native libs
                    // are not available (that would throw UnsatisfiedLinkError)
                    try {
                        val rows = parsed.orb_desc.size
                        val cols = parsed.orb_desc[0].size
                        val mat = Mat(rows, cols, CvType.CV_8U)
                        for (r in 0 until rows) {
                            val rowBytes = ByteArray(cols)
                            for (c in 0 until cols) rowBytes[c] = parsed.orb_desc[r][c].toByte()
                            mat.put(r, 0, rowBytes)
                        }
                        mat
                    } catch (t: Throwable) {
                        // If native OpenCV isn't available, fall back to null descriptors and continue
                        null
                    }
                } else {
                    null
                }

                val lbpArr = FloatArray(lbp.size)
                for (i in lbp.indices) lbpArr[i] = lbp[i]

                results.add(ReferenceData(name, lbpArr, orbMat))
            } catch (e: Exception) {
                // skip malformed entries
            }
        }

        return results
    }

}
