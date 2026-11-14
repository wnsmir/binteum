package com.binteum.core

enum class NapFinishReason {
    REQUESTED_DURATION_COMPLETE, CHARGE_WINDOW_ENDING, ESTIMATE_UNAVAILABLE, CLOCK_RESET, CANCELLED,
}

data class NapProgress(
    val remainingSeconds: Int,
    val elapsedSeconds: Int,
    val finished: Boolean,
    val finishReason: NapFinishReason?,
    /** True only on the transition that finishes the session, not every later tick. */
    val shouldWake: Boolean,
)

/**
 * Foreground nap deadline. Pass Android SystemClock.elapsedRealtime(), never wall-clock time.
 * The host supplies the current conservative charge/departure budget on EVERY tick. A shorter
 * budget advances the wake time; a longer budget never silently extends the user's nap.
 * This domain object cannot guarantee an alarm after process death or while the device sleeps.
 */
class NapSession private constructor(
    val startedAtElapsedMillis: Long,
    val plannedDurationSeconds: Int,
    private val wakeReserveSeconds: Int,
    private var deadlineElapsedMillis: Long,
    private var deadlineReason: NapFinishReason,
) {
    private var lastTickElapsedMillis = startedAtElapsedMillis
    private var finalProgress: NapProgress? = null

    fun tick(nowElapsedMillis: Long, availableSeconds: Int?): NapProgress {
        finalProgress?.let { return it.copy(shouldWake = false) }
        if (nowElapsedMillis < lastTickElapsedMillis) return finish(lastTickElapsedMillis, NapFinishReason.CLOCK_RESET)
        lastTickElapsedMillis = nowElapsedMillis
        if (availableSeconds == null || availableSeconds < 0) return finish(nowElapsedMillis, NapFinishReason.ESTIMATE_UNAVAILABLE)
        val usableSeconds = (availableSeconds.toLong() - wakeReserveSeconds).coerceAtLeast(0)
        val candidateDeadline = safeAddMillis(nowElapsedMillis, usableSeconds * 1000)
        if (candidateDeadline < deadlineElapsedMillis) {
            deadlineElapsedMillis = candidateDeadline
            deadlineReason = NapFinishReason.CHARGE_WINDOW_ENDING
        }
        if (nowElapsedMillis >= deadlineElapsedMillis) return finish(nowElapsedMillis, deadlineReason)
        val remaining = ((deadlineElapsedMillis - nowElapsedMillis + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return NapProgress(remaining, elapsedSeconds(nowElapsedMillis), false, null, false)
    }

    fun cancel(nowElapsedMillis: Long): NapProgress {
        finalProgress?.let { return it.copy(shouldWake = false) }
        return finish(nowElapsedMillis.coerceAtLeast(lastTickElapsedMillis), NapFinishReason.CANCELLED)
    }

    private fun finish(nowElapsedMillis: Long, reason: NapFinishReason): NapProgress =
        NapProgress(0, elapsedSeconds(nowElapsedMillis), true, reason, reason != NapFinishReason.CANCELLED)
            .also { finalProgress = it }

    private fun elapsedSeconds(nowElapsedMillis: Long): Int =
        ((nowElapsedMillis - startedAtElapsedMillis).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    companion object {
        const val MIN_WAKE_RESERVE_SECONDS = 2 * 60

        /** Full remaining time, with no separate duration or wake buffer. */
        fun forRemainingTime(availableSeconds: Int?, nowElapsedMillis: Long): NapSession? {
            if (availableSeconds == null || availableSeconds <= 0 || nowElapsedMillis < 0) return null
            return NapSession(nowElapsedMillis, availableSeconds, 0,
                safeAddMillis(nowElapsedMillis, availableSeconds * 1000L), NapFinishReason.CHARGE_WINDOW_ENDING)
        }

        /** Sleep uses the complete current timer/charge/departure window, minus the wake buffer. */
        fun forWindow(availableSeconds: Int?, nowElapsedMillis: Long,
            wakeReserveSeconds: Int = MIN_WAKE_RESERVE_SECONDS): NapSession? =
            availableSeconds?.let { start(it, it, nowElapsedMillis, wakeReserveSeconds) }

        /** Unknown time requires a known departure limit or renewed vehicle estimate first. */
        fun start(
            requestedSeconds: Int,
            availableSeconds: Int?,
            nowElapsedMillis: Long,
            wakeReserveSeconds: Int = MIN_WAKE_RESERVE_SECONDS,
        ): NapSession? {
            if (requestedSeconds <= 0 || availableSeconds == null || nowElapsedMillis < 0) return null
            val reserve = wakeReserveSeconds.coerceAtLeast(MIN_WAKE_RESERVE_SECONDS)
            val usable = (availableSeconds.toLong() - reserve).coerceAtLeast(0)
            if (usable == 0L) return null
            val duration = minOf(requestedSeconds.toLong(), usable).toInt()
            val reason = if (duration < requestedSeconds) NapFinishReason.CHARGE_WINDOW_ENDING
                else NapFinishReason.REQUESTED_DURATION_COMPLETE
            return NapSession(nowElapsedMillis, duration, reserve, safeAddMillis(nowElapsedMillis, duration * 1000L), reason)
        }

        private fun safeAddMillis(start: Long, duration: Long): Long =
            if (Long.MAX_VALUE - start < duration) Long.MAX_VALUE else start + duration
    }
}
