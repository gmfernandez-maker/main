package com.gabby.studiowebwrapper.util

import android.content.Context
import android.graphics.Bitmap

data class ScoreResult(val referenceName: String, val lbpScore: Float, val orbScore: Float, val finalScore: Float)

class Grader(
    context: Context,
    private val lbpWeight: Float = 0.5f,
    private val orbWeight: Float = 0.5f,
    private val useSpatialLbp: Boolean = true,
    private val spatialGridX: Int = 4,
    private val spatialGridY: Int = 4,
    private val useClahe: Boolean = true
) {
    private val references = ReferenceManager.loadAll(context = context)

    fun activeLbpBins(): Int {
        return if (useSpatialLbp) {
            256 * spatialGridX.coerceAtLeast(1) * spatialGridY.coerceAtLeast(1)
        } else {
            256
        }
    }

    fun gradeAgainstAll(bitmap: Bitmap): List<ScoreResult> {
        val lbpClassic = DescriptorUtils.computeLBPHist(bitmap, useClahe)
        val lbpSpatial = if (useSpatialLbp) {
            DescriptorUtils.computeSpatialLBPHist(bitmap, spatialGridX, spatialGridY, useClahe)
        } else {
            null
        }
        val orb = DescriptorUtils.orbDescriptorsFromBitmap(bitmap, useClahe = useClahe)
        val results = mutableListOf<ScoreResult>()
        for (ref in references) {
            val queryLbp = if (lbpSpatial != null && ref.lbpHist.size == lbpSpatial.size) {
                lbpSpatial
            } else {
                lbpClassic
            }

            val lbpScore = DescriptorUtils.lbpChi2Similarity(queryLbp, ref.lbpHist)
            val orbScore = DescriptorUtils.orbMatchScore(orb, ref.orbDescriptors)
            val final = lbpWeight * lbpScore + orbWeight * orbScore
            results.add(ScoreResult(ref.name, lbpScore, orbScore, final))
        }
        return results.sortedByDescending { it.finalScore }
    }

    fun hasReferences(): Boolean = references.isNotEmpty()
}
