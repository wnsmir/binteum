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
                "불편하면 활동을 중단하세요.",
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
                "정한 행동을 기억하고 활동을 마칩니다.",
            ),
        ),
        OwnActivity(
            id = "quiz_8",
            title = "8분 가벼운 퀴즈",
            kind = ActivityKind.QUIZ,
            durationSeconds = 480,
            instructions = listOf(
                "문제 5개를 풀고 정답을 확인합니다.",
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

    // Explicitly selected guided sessions; keep the existing preference ranking unchanged.
    val guided = listOf(
        OwnActivity("breathing_3", "3분 호흡", ActivityKind.REST, 180, listOf(
            "어깨의 힘을 빼고 편안하게 앉아 보세요.",
            "평소처럼 자연스럽게 숨을 들이쉬고 내쉬세요. 숨을 참거나 깊게 쉬려고 애쓰지 않아도 돼요.",
            "호흡이 오가는 느낌에 잠시 주의를 기울여 보세요.",
            "불편하면 바로 멈추고 편안한 호흡으로 돌아오세요.",
        )),
        OwnActivity("meditation_3", "3분 명상", ActivityKind.REST, 180, listOf(
            "편안한 자세로 앉아 시선을 부드럽게 내려놓으세요.",
            "몸이 좌석에 닿는 느낌과 주변의 소리를 가볍게 알아차려 보세요.",
            "생각이 떠오르면 해결하려 하지 않고 다시 지금의 감각으로 돌아와 보세요.",
            "활동을 마친 뒤 출발 전 주변을 확인하세요.",
        )),
    )

    fun find(id: String): OwnActivity? = (all + guided).firstOrNull { it.id == id }

    fun canStart(id: String, windowSeconds: Int, status: SessionStatus): Boolean =
        status == SessionStatus.CHARGING && find(id)?.let {
            it.durationSeconds.toLong() + ActivityRecommender.DEFAULT_RESERVE_SECONDS <= windowSeconds.toLong()
        } == true
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
