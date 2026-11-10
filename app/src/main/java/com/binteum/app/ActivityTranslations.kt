package com.binteum.app

import com.binteum.core.OwnActivity
import com.binteum.core.QuizQuestion

/** App-owned content only; provider titles keep their original language. */
fun englishActivity(activity: OwnActivity): OwnActivity = when (activity.id) {
    "rest_3" -> activity.copy(title = "3-minute eye break", instructions = listOf(
        "Sit comfortably and look away from the screen.", "Close your eyes, or gently look into the distance if comfortable.",
        "Let your breathing stay natural and take a short break.", "Stop if you feel uncomfortable."))
    "reflection_5" -> activity.copy(title = "5-minute reflection", instructions = listOf(
        "Choose one thing on your mind.", "Separate what is already done from what remains.",
        "Choose the smallest useful next step.", "Keep that step in mind as you finish."))
    "breathing_3" -> activity.copy(title = "3-minute breathing", instructions = listOf(
        "Relax your shoulders and sit comfortably.", "Breathe naturally. You do not need to hold or deepen your breath.",
        "Gently notice your breathing for a moment.", "Stop and return to your usual breathing if uncomfortable."))
    "meditation_3" -> activity.copy(title = "3-minute mindfulness", instructions = listOf(
        "Sit comfortably and soften your gaze.", "Notice the seat supporting you and the sounds around you.",
        "When thoughts appear, gently return to what you can sense now.", "Check your surroundings before departure."))
    "quiz_8" -> activity.copy(title = "8-minute quiz", instructions = listOf(
        "Try five short questions.", "Choose an answer to see an explanation.", "Rest or finish after the last question."),
        quizQuestions = listOf(
            QuizQuestion("How many seconds are in three minutes?", listOf("90", "120", "180"), 2, "Three times 60 is 180 seconds."),
            QuizQuestion("What comes after 2, 4, 6?", listOf("7", "8", "10"), 1, "Adding two each time gives 8."),
            QuizQuestion("What is the perimeter of a square with 4 cm sides?", listOf("8 cm", "12 cm", "16 cm"), 2, "Four sides times 4 cm equals 16 cm."),
            QuizQuestion("How many minutes are in half an hour?", listOf("15", "30", "45"), 1, "Half of 60 minutes is 30 minutes."),
            QuizQuestion("Star, circle, star, circle. What comes next?", listOf("Star", "Circle", "Triangle"), 0, "The star and circle alternate.")))
    else -> activity
}
