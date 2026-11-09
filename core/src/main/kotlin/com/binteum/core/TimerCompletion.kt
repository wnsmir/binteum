package com.binteum.core

/** Use the exact monotonic deadline, never the rounded countdown label. */
object TimerCompletion {
    fun isDue(now: Long, deadline: Long, started: Long, finished: Long, targetMode: Boolean): Boolean =
        !targetMode && started > 0 && finished == 0L && deadline > 0 && now >= deadline

    fun canAnnounce(now: Long, deadline: Long, foreground: Boolean): Boolean =
        foreground && deadline > 0 && now >= deadline && now - deadline <= 2_000
}
