package com.gabby.studiowebwrapper.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import org.junit.Assert.*
import org.junit.Test

class NativeRepositoryScrubTest {
    private val gson = Gson()

    @Test
    fun scrub_removes_redacted_fields_and_preserves_scores() {
        val sample = SuggestMetadataOutput(
            material = "Yellow Gold",
            purity = "18K",
            stampText = "14K",
            stampConfidence = 92,
            stampDetected = true,
            gemstones = null,
            qualityScore = 87,
            analysis = "This is a detailed analysis with potential PII.",
            similarProducts = listOf(),
            yoloScore = 80,
            lbpScore = 85,
            orbScore = 86,
            expectedWeightGrams = 3.2f,
            sourceHash = "abc123",
            yoloModelUsed = "v1",
            explainability = listOf("row1"),
            yoloDetections = null,
            captureWarnings = listOf("low_light"),
            rescanSuggestions = listOf("retake")
        )

        val raw = gson.toJson(sample)
        val scrubbed = NativeRepository.scrubResultJsonForUpload(raw)
        val obj = JsonParser.parseString(scrubbed).asJsonObject

        // Redacted fields must be removed
        assertFalse(obj.has("stampText"))
        assertFalse(obj.has("sourceHash"))
        assertFalse(obj.has("analysis"))
        assertFalse(obj.has("similarProducts"))
        assertFalse(obj.has("yoloDetections"))

        // Numeric scores preserved
        assertTrue(obj.has("qualityScore"))
        assertEquals(87, obj.get("qualityScore").asInt)
        assertTrue(obj.has("yoloScore"))
        assertEquals(80, obj.get("yoloScore").asInt)
    }

    @Test
    fun scrub_fallback_redacts_stamp_and_source_in_malformed_json() {
        val malformed = "{\"stampText\":\"secret-stamp\",\"sourceHash\":\"sh-xyz\",\"qualityScore\":90}"
        val scrubbed = NativeRepository.scrubResultJsonForUpload(malformed)
        // Should contain redacted markers for stampText and sourceHash or have them removed
        assertTrue(scrubbed.contains("[redacted]") || !scrubbed.contains("secret-stamp"))
        assertTrue(scrubbed.contains("[redacted]") || !scrubbed.contains("sh-xyz"))
        // Should still include qualityScore if possible
        val obj = JsonParser.parseString(scrubbed)
        if (obj.isJsonObject) {
            val o = obj.asJsonObject
            assertTrue(o.has("qualityScore"))
            assertEquals(90, o.get("qualityScore").asInt)
        }
    }
}
