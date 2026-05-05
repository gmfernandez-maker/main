package com.gabby.studiowebwrapper.model

import com.google.gson.annotations.SerializedName

data class UserDto(
    val id: String,
    val email: String,
    @SerializedName("fullName") val fullName: String? = null
)

data class AuthResponse(
    val message: String,
    val user: UserDto
)

data class ApiError(
    val error: String,
    val details: String? = null
)

data class Gemstone(
    val type: String,
    val cut: String? = null,
    val clarity: String? = null
)

data class SimilarProduct(
    val name: String,
    val url: String,
    val price: String,
    val imageUrl: String
)

data class YoloDetectionOutput(
    val classId: Int,
    val score: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

data class SuggestMetadataOutput(
    val material: String,
    val purity: String? = null,
    val stampText: String? = null,
    val stampConfidence: Int = 0,
    val stampDetected: Boolean = false,
    val gemstones: List<Gemstone>? = null,
    val qualityScore: Int,
    val analysis: String,
    val similarProducts: List<SimilarProduct>? = null,
    // Algorithm-specific scores (client-side sample values)
    val yoloScore: Int = 0,
    val lbpScore: Int = 0,
    val orbScore: Int = 0,
    // Estimated physical weight (grams) inferred from visual features (sample)
    val expectedWeightGrams: Float? = null
    ,
    // Stable hash of the submitted source image, used to suppress duplicate history rows.
    val sourceHash: String? = null,
    // Human-readable label for the YOLO model actually used to generate this result.
    val yoloModelUsed: String? = null,
    // Computed total from the three algorithm scores (average)
    val totalComputedScore: Int = if (yoloScore + lbpScore + orbScore == 0) 0 else ((yoloScore + lbpScore + orbScore) / 3),
    // Human-readable scoring explanation rows for the result screen.
    val explainability: List<String>? = null,
    // Raw YOLO detections used to render debug overlay in result preview.
    val yoloDetections: List<YoloDetectionOutput>? = null,
    // Capture/quality warnings surfaced from pre-grade guardrails.
    val captureWarnings: List<String>? = null,
    // Actionable guidance shown when users should rescan.
    val rescanSuggestions: List<String>? = null
)

data class GradeRequest(
    val fileDataUri: String,
    val fileName: String
)

data class GradeResponse(
    val data: SuggestMetadataOutput? = null,
    val error: String? = null
)
