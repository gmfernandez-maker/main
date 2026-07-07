package com.gabby.studiowebwrapper.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRepositoryTest {
    @Test
    fun scrubResultJsonForUpload_removesSensitiveFieldsAndKeepsSafeSignals() {
        val raw = """
            {
              "material": "Yellow Gold",
              "purity": "18K",
              "stampText": "18K",
              "sourceHash": "abc123",
              "analysis": "private note",
              "similarProducts": [{"name": "listing"}],
              "yoloDetections": [{"classId": 1}],
              "previewUri": "content://local/image",
              "userId": "user-1",
              "qualityScore": 82,
              "totalComputedScore": 80,
              "stampDetected": true
            }
        """.trimIndent()

        val scrubbed = NativeRepository.scrubResultJsonForUpload(raw)
        val obj = JsonParser.parseString(scrubbed).asJsonObject

        assertEquals("Yellow Gold", obj.get("material").asString)
        assertEquals("18K", obj.get("purity").asString)
        assertEquals(82, obj.get("qualityScore").asInt)
        assertEquals(80, obj.get("totalComputedScore").asInt)
        assertTrue(obj.get("stampDetected").asBoolean)

        assertFalse(obj.has("stampText"))
        assertFalse(obj.has("sourceHash"))
        assertFalse(obj.has("analysis"))
        assertFalse(obj.has("similarProducts"))
        assertFalse(obj.has("yoloDetections"))
        assertFalse(obj.has("previewUri"))
        assertFalse(obj.has("userId"))
    }

    @Test
    fun scrubResultJsonForUpload_blankInputReturnsEmptyObject() {
        assertEquals("{}", NativeRepository.scrubResultJsonForUpload(""))
    }
}
