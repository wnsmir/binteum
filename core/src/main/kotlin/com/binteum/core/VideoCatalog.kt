package com.binteum.core

data class VideoSample(val id: String, val title: String, val seconds: Int, val author: String)

object VideoCatalog {
    // Official YouTube watch-page metadata checked 2026-11-08: non-live, playableInEmbed=true.
    // Availability can change; runtime player errors remain authoritative.
    val all = listOf(
        VideoSample("PVGeM40dABA", "Coffee Run", 185, "Blender Studio"),
        VideoSample("aqz-KE-bpKQ", "Big Buck Bunny", 635, "Blender"),
    )

    fun recommend(remainingSeconds: Int?): List<VideoSample> {
        if (remainingSeconds == null) return all // No claimed fit without a time budget.
        return all.filter { it.seconds.toLong() + 120 <= remainingSeconds.toLong() }.sortedByDescending { it.seconds }
    }
}
