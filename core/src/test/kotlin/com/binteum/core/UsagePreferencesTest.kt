package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class UsagePreferencesTest {
    private fun event(id: String, category: UsageCategory, remaining: Int? = 600, soc: Int? = 65, simulated: Boolean = false) =
        UsageEvent(id, id.toLongOrNull() ?: 1, category, UsageEntryPoint.MANUAL, remaining,
            if (remaining == null) ChargeTimeSource.UNKNOWN else ChargeTimeSource.VEHICLE, soc, 80, simulated)

    @Test fun `unknown remaining time is never treated as a short charge`() {
        val unknown = (1..20).map { event(it.toString(), UsageCategory.QUIZ, remaining = null) }
        assertEquals(RemainingTimeBucket.UNKNOWN, UsagePreferences.timeBucket(null))
        assertEquals(RemainingTimeBucket.UNKNOWN, UsagePreferences.timeBucket(-1))
        assertEquals(UsageCategory.VIDEO, UsagePreferences.rank(unknown, 200, 65).first())
        assertEquals(UsageCategory.QUIZ, UsagePreferences.rank(unknown, null, 65).first())
        assertEquals(RemainingTimeBucket.FIVE_TO_FIFTEEN, UsagePreferences.timeBucket(300))
        assertEquals(RemainingTimeBucket.FIFTEEN_TO_THIRTY, UsagePreferences.timeBucket(900))
        assertEquals(RemainingTimeBucket.THIRTY_PLUS, UsagePreferences.timeBucket(1800))
    }

    @Test fun `choices in matching time and SOC context rank first without simulation pollution`() {
        val history = listOf(event("1", UsageCategory.REST, soc = 65), event("2", UsageCategory.VIDEO, soc = 20)) +
            (3..100).map { event(it.toString(), UsageCategory.QUIZ, simulated = true) }
        assertEquals(UsageCategory.REST, UsagePreferences.rank(history, 600, 65).first())
        assertEquals(UsageCategory.VIDEO, UsagePreferences.rank(history, 600, 20).first())
        assertEquals(0, UsagePreferences.matchingChoices(history, UsageCategory.QUIZ, 600))
    }

    @Test fun `completion updates replace event while distinct choices accumulate`() {
        val initial = event("1", UsageCategory.REST)
        val first = UsagePreferences.record(emptyList(), initial)
        val completed = UsagePreferences.record(first, initial.copy(durationSeconds = 180, completed = true))
        assertEquals(1, completed.size)
        assertEquals(true, completed.single().completed)
        assertEquals(1, UsagePreferences.matchingChoices(completed + completed, UsageCategory.REST, 600))
        val next = UsagePreferences.record(completed, event("2", UsageCategory.REST))
        assertEquals(2, UsagePreferences.matchingChoices(next, UsageCategory.REST, 600))
    }

    @Test fun `provider choices never retain playback durations or inferred completions`() {
        for (category in listOf(UsageCategory.VIDEO, UsageCategory.EXTERNAL_MEDIA, UsageCategory.FOOD, UsageCategory.WALK)) {
            val saved = UsagePreferences.record(emptyList(), event("1", category).copy(durationSeconds = 600, completed = true, rating = ActivityRating.LIKE)).single()
            assertNull(saved.durationSeconds)
            assertNull(saved.completed)
            assertNull(saved.rating)
        }
    }

    @Test fun `food and walk provider events cannot gain ranking weight from forged completion`() {
        for (category in listOf(UsageCategory.FOOD, UsageCategory.WALK)) {
            val forged = event("1", category).copy(completed = true, rating = ActivityRating.LIKE)
            val neutral = event("2", UsageCategory.VIDEO)
            // Same-context choices tie; unsupported completion must not beat the default order.
            assertEquals(UsageCategory.VIDEO, UsagePreferences.rank(listOf(forged, neutral), 600, 65,
                listOf(UsageCategory.VIDEO, category)).first())
        }
    }

    @Test fun `nap sessions retain explicit app completion and use charging context`() {
        val initial = event("1", UsageCategory.NAP, remaining = 1500)
        val saved = UsagePreferences.record(emptyList(), initial.copy(durationSeconds = 600, completed = true)).single()
        assertEquals(600, saved.durationSeconds)
        assertEquals(true, saved.completed)
        assertEquals(UsageCategory.NAP, UsagePreferences.rank(listOf(saved), 1500, 65).first())
        assertEquals(UsageCategory.VIDEO, UsagePreferences.rank(listOf(saved), 200, 65).first())
    }

    @Test fun `explicit own activity dislike ranks below a neutral category`() {
        val disliked = event("1", UsageCategory.REST).copy(completed = true, rating = ActivityRating.DISLIKE)
        assertEquals(UsageCategory.VIDEO, UsagePreferences.rank(listOf(disliked), 600, 65,
            listOf(UsageCategory.REST, UsageCategory.VIDEO)).first())
        val liked = disliked.copy(rating = ActivityRating.LIKE)
        assertEquals(UsageCategory.REST, UsagePreferences.rank(listOf(liked), 600, 65,
            listOf(UsageCategory.REST, UsageCategory.VIDEO)).first())
    }

    @Test fun `unfinished activities cannot acquire explicit completion feedback`() {
        val unfinished = event("1", UsageCategory.REST).copy(completed = false, rating = ActivityRating.DISLIKE)
        assertNull(UsagePreferences.record(emptyList(), unfinished).single().rating)
        assertEquals(UsageCategory.REST, UsagePreferences.rank(listOf(unfinished), 600, 65,
            listOf(UsageCategory.REST, UsageCategory.VIDEO)).first())
    }

    @Test fun `history retention is bounded and preserves newest chronological records`() {
        val history = (1..3).map { event(it.toString(), UsageCategory.REST) }
        val next = UsagePreferences.record(history, event("4", UsageCategory.QUIZ), maxEvents = 3)
        assertEquals(listOf("2", "3", "4"), next.map { it.id })
        val replayed = UsagePreferences.record(next, history.first().copy(completed = true), maxEvents = 3)
        assertEquals(listOf("2", "3", "4"), replayed.map { it.id })
    }
}
