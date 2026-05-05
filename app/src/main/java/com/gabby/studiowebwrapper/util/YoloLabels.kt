package com.gabby.studiowebwrapper.util

object YoloLabels {
    val classNames: List<String> = listOf(
        "Bracelet",
        "Earring",
        "Necklace",
        "Ring"
    )

    fun labelForClassId(classId: Int): String {
        return classNames.getOrNull(classId) ?: "Unknown"
    }
}
