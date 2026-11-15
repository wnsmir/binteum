package com.binteum.core

/** A user duration is monotonic; a charge ETA remains live and is never a completion signal. */
data class WaitingPlan(val startedElapsed: Long, val durationSeconds: Int?, val initialSoc: Int?) {
    init { require(durationSeconds == null || durationSeconds > 0) }
    fun remaining(nowElapsed: Long, chargeSeconds: Int?): Int? = durationSeconds?.let {
        val elapsed = (nowElapsed - startedElapsed).coerceAtLeast(0)
        ((it * 1000L - elapsed).coerceAtLeast(0) + 999).div(1000).toInt()
    } ?: chargeSeconds?.coerceAtLeast(0)
    fun budget(chargeBudget: Int?, nowElapsed: Long): Int? {
        val time = remaining(nowElapsed, null)
        return if (time == null) chargeBudget else chargeBudget?.let { minOf(time, it) } ?: time
    }
    fun progress(nowElapsed: Long, soc: Int?, target: Int): Float = if (durationSeconds != null) {
        ((nowElapsed - startedElapsed).coerceAtLeast(0) / (durationSeconds * 1000f)).coerceIn(0f, 1f)
    } else {
        val start = initialSoc ?: soc ?: 0
        if (soc == null) 0f else if (soc >= target) 1f else
            ((soc - start).toFloat() / (target - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    companion object {
        /**
         * Activity recreation may retain a plan, but an OS-restored Bundle from an earlier
         * process must not restart one. The host creates one random token per process and
         * saves it with the plan. Reject future/invalid elapsed timestamps as well, since
         * elapsed realtime has no meaning across a device restart.
         */
        fun restore(
            savedProcessToken: String?, currentProcessToken: String,
            startedElapsed: Long, durationSeconds: Int?, initialSoc: Int?, nowElapsed: Long,
        ): WaitingPlan? {
            if (currentProcessToken.isBlank() || savedProcessToken != currentProcessToken ||
                startedElapsed < 0 || nowElapsed < startedElapsed ||
                (durationSeconds != null && durationSeconds <= 0) ||
                (initialSoc != null && initialSoc !in 0..100)) return null
            return WaitingPlan(startedElapsed, durationSeconds, initialSoc)
        }
    }
}
