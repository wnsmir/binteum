package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class ActivityExperienceTest {
    private fun recommendations(seconds: Int, profile: ActivityProfile = ActivityProfile()) =
        ActivityRecommender.recommend(seconds, SessionStatus.CHARGING, profile)

    private fun ids(seconds: Int) = recommendations(seconds).map { it.activity.id }

    private fun feedback(
        eventId: String = "feedback-1",
        attemptId: String = "attempt-1",
        activityId: String = "quiz_8",
        rating: ActivityRating = ActivityRating.LIKE,
        completed: Boolean = true,
    ) = OwnActivityFeedback(eventId, attemptId, activityId, rating, completed)

    @Test fun `exact second boundaries preserve two minutes for wrapping up`() {
        listOf(0, 119, 120, 299).forEach { assertTrue(ids(it).isEmpty()) }
        assertEquals(listOf("rest_3"), ids(300))
        assertEquals(listOf("rest_3"), ids(419))
        assertEquals(listOf("rest_3", "reflection_5"), ids(420))
        assertEquals(listOf("rest_3", "reflection_5"), ids(599))
        assertEquals(listOf("rest_3", "reflection_5", "quiz_8"), ids(600))
    }

    @Test fun `elapsed time invalidates an activity instead of refilling its window`() {
        assertTrue(recommendations(600).any { it.activity.id == "quiz_8" })
        assertFalse(recommendations(599).any { it.activity.id == "quiz_8" })
        assertTrue(recommendations(299).isEmpty())
    }

    @Test fun `every non charging state blocks even strongly preferred activities`() {
        val profile = ActivityProfile(setOf(ActivityKind.QUIZ))
        SessionStatus.values().filter { it != SessionStatus.CHARGING }.forEach { status ->
            assertTrue("Activities leaked in $status",
                ActivityRecommender.recommend(3600, status, profile).isEmpty())
        }
    }

    @Test fun `preference cannot override the remaining time filter`() {
        val profile = ActivityProfile(
            preferredKinds = setOf(ActivityKind.QUIZ),
            feedback = mapOf("quiz_8" to FeedbackCounts(likes = 50)),
        )
        assertEquals(listOf("rest_3"), recommendations(300, profile).map { it.activity.id })
    }

    @Test fun `explicit kind stays ahead of a conflicting learned preference`() {
        val profile = ActivityProfile(
            preferredKinds = setOf(ActivityKind.REST),
            feedback = mapOf(
                "rest_3" to FeedbackCounts(dislikes = 10_000),
                "quiz_8" to FeedbackCounts(likes = 10_000),
            ),
        )
        assertEquals("rest_3", recommendations(1200, profile).first().activity.id)
        assertEquals(3, recommendations(Int.MAX_VALUE, profile).size)
    }

    @Test fun `explicit own activity feedback changes ranking only in its profile`() {
        val baseline = ActivityProfile()
        val liked = ActivityFeedbackReducer.apply(baseline, feedback()).profile
        assertEquals("quiz_8", recommendations(600, liked).first().activity.id)
        assertEquals("rest_3", recommendations(600, baseline).first().activity.id)
        assertTrue(baseline.feedback.isEmpty())

        val disliked = ActivityFeedbackReducer.apply(
            baseline, feedback(rating = ActivityRating.DISLIKE)).profile
        assertEquals("quiz_8", recommendations(600, disliked).last().activity.id)
    }

    @Test fun `persisted event and attempt ids prevent duplicate feedback`() {
        val first = ActivityFeedbackReducer.apply(ActivityProfile(), feedback())
        assertTrue(first.applied)
        val restored = ActivityProfile(
            first.profile.preferredKinds.toSet(), first.profile.feedback.toMap(),
            first.profile.processedFeedbackIds.toSet(), first.profile.ratedAttemptIds.toSet(),
        )
        val replay = ActivityFeedbackReducer.apply(restored, feedback())
        assertFalse(replay.applied)
        assertEquals(FeedbackUpdateReason.DUPLICATE_EVENT, replay.reason)
        assertSame(restored, replay.profile)

        val repeatedTap = ActivityFeedbackReducer.apply(restored, feedback(eventId = "feedback-2"))
        assertEquals(FeedbackUpdateReason.ALREADY_RATED_ATTEMPT, repeatedTap.reason)
        assertEquals(FeedbackCounts(likes = 1), repeatedTap.profile.feedback["quiz_8"])

        val differentAttempt = ActivityFeedbackReducer.apply(
            restored, feedback(eventId = "feedback-3", attemptId = "attempt-2"))
        assertTrue(differentAttempt.applied)
        assertEquals(FeedbackCounts(likes = 2), differentAttempt.profile.feedback["quiz_8"])
    }

    @Test fun `interrupted activity cannot turn into a dislike`() {
        val before = ActivityProfile()
        val result = ActivityFeedbackReducer.apply(
            before, feedback(rating = ActivityRating.DISLIKE, completed = false))
        assertFalse(result.applied)
        assertEquals(FeedbackUpdateReason.NOT_COMPLETED, result.reason)
        assertSame(before, result.profile)
        assertTrue(result.profile.processedFeedbackIds.isEmpty())
    }

    @Test fun `external content and missing identifiers never enter local profile`() {
        val profile = ActivityProfile()
        listOf(feedback(activityId = "external-video"), feedback(eventId = " "),
            feedback(attemptId = "")).forEach { event ->
            val result = ActivityFeedbackReducer.apply(profile, event)
            assertFalse(result.applied)
            assertSame(profile, result.profile)
        }
    }

    @Test fun `large counts do not overflow or create an invalid score`() {
        val profile = ActivityProfile(feedback = mapOf(
            "quiz_8" to FeedbackCounts(Int.MAX_VALUE, Int.MAX_VALUE),
        ))
        assertTrue(recommendations(Int.MAX_VALUE, profile).all { it.score.isFinite() })
        val result = ActivityFeedbackReducer.apply(profile, feedback())
        assertEquals(FeedbackUpdateReason.COUNT_LIMIT_REACHED, result.reason)
        assertSame(profile, result.profile)
    }

    @Test fun `catalog provides executable own content with valid quiz choices`() {
        assertEquals(ActivityCatalog.all.size, ActivityCatalog.all.map { it.id }.toSet().size)
        ActivityCatalog.all.forEach { activity ->
            assertTrue(activity.instructions.isNotEmpty())
            assertTrue(activity.instructions.none { it.isBlank() })
            assertTrue(activity.durationSeconds > 0)
        }
        val quiz = ActivityCatalog.find("quiz_8")!!
        assertTrue(quiz.quizQuestions.size in 4..5)
        quiz.quizQuestions.forEach { question ->
            assertTrue(question.correctAnswerIndex in question.choices.indices)
            assertTrue(question.choices.size >= 2)
            assertTrue(question.explanation.isNotBlank())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative window is rejected`() { recommendations(-1) }

    @Test(expected = IllegalArgumentException::class)
    fun `negative reserve is rejected`() {
        ActivityRecommender.recommend(600, SessionStatus.CHARGING, reserveSeconds = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative feedback count is rejected`() { FeedbackCounts(likes = -1) }
}
