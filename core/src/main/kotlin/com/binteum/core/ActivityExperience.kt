package com.binteum.core

enum class ActivityKind { REST, REFLECTION, QUIZ }
enum class ActivityRating { LIKE, DISLIKE }

data class QuizQuestion(
    val prompt: String,
    val choices: List<String>,
    val correctAnswerIndex: Int,
    val explanation: String,
)

data class OwnActivity(
    val id: String,
    val title: String,
    val kind: ActivityKind,
    val durationSeconds: Int,
    val instructions: List<String>,
    val quizQuestions: List<QuizQuestion> = emptyList(),
)

/** App-owned content only. Quiz answers never become preference feedback. */
object ActivityCatalog {
    val all: List<OwnActivity> = listOf(
        OwnActivity(
            id = "rest_3",
            title = "3분 눈 쉬기",
            kind = ActivityKind.REST,
            durationSeconds = 180,
            instructions = listOf(
                "편안한 자세로 앉아 화면에서 잠시 시선을 떼세요.",
                "눈을 감거나, 편안하다면 먼 곳을 가볍게 바라보세요.",
                "호흡을 억지로 바꾸지 않고 잠시 쉬어 보세요.",
                "불편하거나 다른 일을 해야 하면 언제든 끝내도 괜찮아요.",
            ),
        ),
        OwnActivity(
            id = "reflection_5",
            title = "5분 생각 정리",
            kind = ActivityKind.REFLECTION,
            durationSeconds = 300,
            instructions = listOf(
                "지금 머릿속에 떠오르는 일 하나를 골라 보세요.",
                "그 일에서 이미 끝낸 부분과 아직 남은 부분을 나눠 생각해 보세요.",
                "다음에 할 수 있는 가장 작은 행동 하나를 정해 보세요.",
                "당장 해결하지 않아도 괜찮아요. 남은 시간은 편히 쉬세요.",
            ),
        ),
        OwnActivity(
            id = "quiz_8",
            title = "8분 가벼운 퀴즈",
            kind = ActivityKind.QUIZ,
            durationSeconds = 480,
            instructions = listOf(
                "다섯 문제를 천천히 풀어 보세요. 점수 경쟁은 없어요.",
                "답을 고르면 짧은 설명을 볼 수 있어요.",
                "문제를 다 풀면 남은 시간은 쉬거나 활동을 마칠 수 있어요.",
            ),
            quizQuestions = listOf(
                QuizQuestion("3분은 몇 초일까요?", listOf("90초", "120초", "180초"), 2,
                    "1분은 60초이므로 3분은 180초예요."),
                QuizQuestion("2, 4, 6 다음에 올 수는?", listOf("7", "8", "10"), 1,
                    "2씩 늘어나는 수열이므로 다음 수는 8이에요."),
                QuizQuestion("한 변이 4cm인 정사각형의 둘레는?", listOf("8cm", "12cm", "16cm"), 2,
                    "네 변의 길이를 더하면 4 × 4 = 16cm예요."),
                QuizQuestion("한 시간의 절반은 몇 분일까요?", listOf("15분", "30분", "45분"), 1,
                    "한 시간은 60분이므로 절반은 30분이에요."),
                QuizQuestion("별, 동그라미, 별, 동그라미 다음 모양은?", listOf("별", "동그라미", "세모"), 0,
                    "별과 동그라미가 번갈아 나오므로 다음은 별이에요."),
            ),
        ),
    )

    fun find(id: String): OwnActivity? = all.firstOrNull { it.id == id }
}

data class FeedbackCounts(val likes: Int = 0, val dislikes: Int = 0) {
    init { require(likes >= 0 && dislikes >= 0) }

    /** A neutral prior keeps one rating from claiming a certain preference. */
    val preference: Double
        get() = (2.0 + likes) / (4.0 + likes.toLong() + dislikes.toLong())
}

/**
 * The caller persists a separate profile for each time bucket if desired.
 * Feedback keys are ActivityCatalog IDs, not provider IDs or quiz results.
 * Persist deduplication sets with counts; reset them together when learning is reset.
 */
data class ActivityProfile(
    val preferredKinds: Set<ActivityKind> = emptySet(),
    val feedback: Map<String, FeedbackCounts> = emptyMap(),
    val processedFeedbackIds: Set<String> = emptySet(),
    val ratedAttemptIds: Set<String> = emptySet(),
)

data class ActivityRecommendation(
    val activity: OwnActivity,
    val score: Double,
    val reason: String,
)

object ActivityRecommender {
    const val DEFAULT_RESERVE_SECONDS = 120

    /**
     * windowSeconds is the remaining total user-selected window, not charge ETA.
     * Only a current CHARGING result permits activities; the caller re-evaluates
     * ChargingSession with fresh timestamps before calling and while playing.
     */
    fun recommend(
        windowSeconds: Int,
        status: SessionStatus,
        profile: ActivityProfile = ActivityProfile(),
        reserveSeconds: Int = DEFAULT_RESERVE_SECONDS,
    ): List<ActivityRecommendation> {
        require(windowSeconds >= 0 && reserveSeconds >= 0)
        if (status != SessionStatus.CHARGING) return emptyList()
        val budget = (windowSeconds.toLong() - reserveSeconds).coerceAtLeast(0)
        return ActivityCatalog.all.asSequence()
            .filter { it.durationSeconds <= budget }
            .map { activity ->
                val directPreference = activity.kind in profile.preferredKinds
                val feedback = profile.feedback[activity.id] ?: FeedbackCounts()
                // Explicit choices always outrank the bounded learned bonus.
                val score = (if (directPreference) 100.0 else 0.0) + 20.0 * feedback.preference
                val reason = when {
                    directPreference -> "직접 고른 활동 종류 · 마무리 여유 포함"
                    feedback.likes > feedback.dislikes -> "이 시간 구간에서 좋았던 활동 · 마무리 여유 포함"
                    else -> "선택한 시간 안에 할 수 있어요 · 마무리 여유 포함"
                }
                ActivityRecommendation(activity, score, reason)
            }
            .sortedWith(compareByDescending<ActivityRecommendation> { it.score }
                .thenBy { it.activity.durationSeconds }.thenBy { it.activity.id })
            .take(3)
            .toList()
    }
}

/** completed is explicit app/user completion, not inferred from selection, timeout, or interruption. */
data class OwnActivityFeedback(
    val eventId: String,
    val attemptId: String,
    val activityId: String,
    val rating: ActivityRating,
    val completed: Boolean,
)

enum class FeedbackUpdateReason {
    APPLIED, EMPTY_ID, UNKNOWN_ACTIVITY, NOT_COMPLETED, DUPLICATE_EVENT, ALREADY_RATED_ATTEMPT,
    COUNT_LIMIT_REACHED,
}

data class FeedbackUpdate(
    val profile: ActivityProfile,
    val applied: Boolean,
    val reason: FeedbackUpdateReason,
)

object ActivityFeedbackReducer {
    /** One explicit rating per completed activity attempt; retrying never increments twice. */
    fun apply(profile: ActivityProfile, event: OwnActivityFeedback): FeedbackUpdate {
        fun unchanged(reason: FeedbackUpdateReason) = FeedbackUpdate(profile, false, reason)
        if (event.eventId.isBlank() || event.attemptId.isBlank())
            return unchanged(FeedbackUpdateReason.EMPTY_ID)
        if (ActivityCatalog.find(event.activityId) == null)
            return unchanged(FeedbackUpdateReason.UNKNOWN_ACTIVITY)
        if (!event.completed) return unchanged(FeedbackUpdateReason.NOT_COMPLETED)
        if (event.eventId in profile.processedFeedbackIds)
            return unchanged(FeedbackUpdateReason.DUPLICATE_EVENT)
        if (event.attemptId in profile.ratedAttemptIds)
            return unchanged(FeedbackUpdateReason.ALREADY_RATED_ATTEMPT)

        val before = profile.feedback[event.activityId] ?: FeedbackCounts()
        if ((event.rating == ActivityRating.LIKE && before.likes == Int.MAX_VALUE) ||
            (event.rating == ActivityRating.DISLIKE && before.dislikes == Int.MAX_VALUE))
            return unchanged(FeedbackUpdateReason.COUNT_LIMIT_REACHED)
        val after = when (event.rating) {
            ActivityRating.LIKE -> before.copy(likes = before.likes + 1)
            ActivityRating.DISLIKE -> before.copy(dislikes = before.dislikes + 1)
        }
        return FeedbackUpdate(
            profile.copy(
                feedback = profile.feedback + (event.activityId to after),
                processedFeedbackIds = profile.processedFeedbackIds + event.eventId,
                ratedAttemptIds = profile.ratedAttemptIds + event.attemptId,
            ),
            applied = true,
            reason = FeedbackUpdateReason.APPLIED,
        )
    }
}
