package com.binteum.core

enum class UsageCategory {
    VIDEO, REST, REFLECTION, QUIZ, EXTERNAL_MEDIA, FOOD, WALK, NAP;

    /** A provider launch does not establish playback, arrival, a meal, or a completed walk. */
    val supportsCompletion: Boolean
        get() = this == REST || this == REFLECTION || this == QUIZ || this == NAP
}
enum class UsageEntryPoint { RECOMMENDED, MANUAL }
enum class RemainingTimeBucket { UNKNOWN, UNDER_FIVE, FIVE_TO_FIFTEEN, FIFTEEN_TO_THIRTY, THIRTY_PLUS }
enum class ChargeLevelBand { UNKNOWN, LOW, MIDDLE, HIGH }

/**
 * One explicit app category/activity choice and its charging context.
 * No video IDs, titles, search terms or provider playback events belong in this record.
 * duration/completed describe app-owned activities only; opening a provider is not completion.
 * A completed NAP means the in-app rest session ended; it does not establish actual sleep.
 */
data class UsageEvent(
    val id: String,
    val atMillis: Long,
    val category: UsageCategory,
    val entryPoint: UsageEntryPoint,
    val remainingSeconds: Int?,
    val source: ChargeTimeSource,
    val stateOfCharge: Int?,
    val targetPercent: Int?,
    val simulation: Boolean,
    val durationSeconds: Int? = null,
    val completed: Boolean? = null,
    val rating: ActivityRating? = null,
)

object UsagePreferences {
    fun timeBucket(remainingSeconds: Int?): RemainingTimeBucket = when {
        remainingSeconds == null || remainingSeconds < 0 -> RemainingTimeBucket.UNKNOWN
        remainingSeconds < 300 -> RemainingTimeBucket.UNDER_FIVE
        remainingSeconds < 900 -> RemainingTimeBucket.FIVE_TO_FIFTEEN
        remainingSeconds < 1800 -> RemainingTimeBucket.FIFTEEN_TO_THIRTY
        else -> RemainingTimeBucket.THIRTY_PLUS
    }

    fun chargeBand(percent: Int?): ChargeLevelBand = when {
        percent == null || percent !in 0..100 -> ChargeLevelBand.UNKNOWN
        percent < 40 -> ChargeLevelBand.LOW
        percent < 80 -> ChargeLevelBand.MIDDLE
        else -> ChargeLevelBand.HIGH
    }

    /** Repeated persistence or completion updates replace one event, never add extra votes. */
    fun record(events: List<UsageEvent>, event: UsageEvent, maxEvents: Int = 2000): List<UsageEvent> {
        require(maxEvents > 0)
        if (event.id.isBlank()) return events.takeLast(maxEvents)
        val safeEvent = if (!event.category.supportsCompletion)
            event.copy(durationSeconds = null, completed = null, rating = null) else event.copy(
                durationSeconds = event.durationSeconds?.coerceAtLeast(0), rating = event.rating.takeIf { event.completed == true })
        return (events.filterNot { it.id == safeEvent.id } + safeEvent).sortedBy { it.atMillis }.takeLast(maxEvents)
    }

    /** Simple transparent choice frequencies, not a claim of trained reinforcement learning. */
    fun rank(
        events: List<UsageEvent>, remainingSeconds: Int?, stateOfCharge: Int?,
        categories: List<UsageCategory> = UsageCategory.entries,
    ): List<UsageCategory> {
        val bucket = timeBucket(remainingSeconds)
        val band = chargeBand(stateOfCharge)
        val eligible = events.asReversed().distinctBy { it.id }.filter { !it.simulation && it.id.isNotBlank() }
        val scores = categories.associateWith { category ->
            eligible.asSequence().filter { it.category == category && timeBucket(it.remainingSeconds) == bucket }
                .sumOf { event ->
                    // Unknown durations form their own bucket; never teach a "short wait" preference.
                    val sameCharge = chargeBand(event.stateOfCharge) == band
                    val ownContent = category.supportsCompletion
                    val feedback = if (ownContent && event.completed == true) when (event.rating) {
                        ActivityRating.LIKE -> 2.0
                        ActivityRating.DISLIKE -> -4.0
                        null -> 0.0
                    } else 0.0
                    (if (sameCharge) 2.0 else 1.0) + (if (ownContent && event.completed == true) 0.25 else 0.0) + feedback
                }
        }
        // Stable default order until the user has supplied choices in this time bucket.
        return categories.distinct().sortedByDescending { scores[it] ?: 0.0 }
    }

    fun matchingChoices(events: List<UsageEvent>, category: UsageCategory, remainingSeconds: Int?): Int =
        events.asReversed().distinctBy { it.id }.count {
            !it.simulation && it.id.isNotBlank() && it.category == category && timeBucket(it.remainingSeconds) == timeBucket(remainingSeconds)
        }
}
