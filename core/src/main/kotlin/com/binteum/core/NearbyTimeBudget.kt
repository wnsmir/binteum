package com.binteum.core

enum class NearbyBudgetStatus { FITS, NOT_ENOUGH_TIME, UNKNOWN_WINDOW, UNCONFIRMED_ROUTE, INVALID_PLAN }

data class NearbyBudgetAssessment(
    val status: NearbyBudgetStatus,
    val requiredSeconds: Long?,
    val availableSeconds: Int?,
    val returnReserveSeconds: Int,
    val spareSeconds: Long?,
) {
    val fits: Boolean get() = status == NearbyBudgetStatus.FITS
}

/**
 * A time check, not a route finder. The host must obtain outbound AND return walking times from
 * a route provider or an explicit user check. Straight-line distances are not walking times.
 * Even a fitting plan is an estimate: this does not establish phone tracking or a return alarm.
 */
object NearbyTimeBudget {
    const val MIN_RETURN_RESERVE_SECONDS = 5 * 60

    fun assess(
        availableSeconds: Int?,
        outboundSeconds: Int,
        returnSeconds: Int,
        staySeconds: Int,
        routeTimesConfirmed: Boolean,
        returnReserveSeconds: Int = MIN_RETURN_RESERVE_SECONDS,
    ): NearbyBudgetAssessment {
        val reserve = returnReserveSeconds.coerceAtLeast(MIN_RETURN_RESERVE_SECONDS)
        val available = availableSeconds?.takeIf { it >= 0 }
        if (outboundSeconds <= 0 || returnSeconds <= 0 || staySeconds < 0) {
            return NearbyBudgetAssessment(NearbyBudgetStatus.INVALID_PLAN, null, available, reserve, null)
        }
        // Long arithmetic prevents a malformed/very large input wrapping into a fitting trip.
        val required = outboundSeconds.toLong() + returnSeconds + staySeconds + reserve
        val spare = available?.toLong()?.minus(required)
        val status = when {
            available == null -> NearbyBudgetStatus.UNKNOWN_WINDOW
            !routeTimesConfirmed -> NearbyBudgetStatus.UNCONFIRMED_ROUTE
            spare!! < 0 -> NearbyBudgetStatus.NOT_ENOUGH_TIME
            else -> NearbyBudgetStatus.FITS
        }
        return NearbyBudgetAssessment(status, required, available, reserve, spare)
    }
}
