package com.binteum.app.music

/** Our editorial moods, not fetched songs, timed playlists, or a provider's recommendations. */
enum class MusicMood(
    val titleKo: String,
    val titleEn: String,
    val descriptionKo: String,
    val descriptionEn: String,
    internal val searchTerm: String,
) {
    PIANO("잔잔한 피아노", "Quiet piano", "가사 없이 잠깐 쉬어가요", "Take a short, wordless break", "relaxing piano"),
    ACOUSTIC("편안한 어쿠스틱", "Easy acoustic", "익숙한 멜로디로 기분 전환", "A gentle change of mood", "acoustic chill"),
    LOFI("느긋한 로파이", "Unhurried lo-fi", "생각을 정리하며 가볍게", "A light backdrop for your thoughts", "lofi chill"),
    JAZZ("카페 재즈", "Coffeehouse jazz", "조금 여유가 있는 충전 시간에", "Settle into a longer charging break", "coffeehouse jazz"),
    AMBIENT("고요한 앰비언트", "Calm ambient", "눈을 쉬며 느긋하게 듣기", "Listen quietly while resting your eyes", "calm ambient"),
    UPLIFT("산뜻한 음악", "A brighter mood", "출발 전 가볍게 기분 바꾸기", "A little lift before setting off", "feel good music");

    fun title(english: Boolean) = if (english) titleEn else titleKo
    fun description(english: Boolean) = if (english) descriptionEn else descriptionKo
}

object MusicCatalog {
    val all: List<MusicMood> = MusicMood.entries.toList()

    /** A time window changes mood ordering; it never certifies external song/playlist duration. */
    fun recommend(availableSeconds: Int?): List<MusicMood> = when {
        availableSeconds == null || availableSeconds < 0 -> listOf(MusicMood.PIANO, MusicMood.ACOUSTIC, MusicMood.LOFI)
        availableSeconds <= 8 * 60 -> listOf(MusicMood.UPLIFT, MusicMood.ACOUSTIC, MusicMood.PIANO)
        availableSeconds <= 20 * 60 -> listOf(MusicMood.LOFI, MusicMood.PIANO, MusicMood.ACOUSTIC)
        else -> listOf(MusicMood.JAZZ, MusicMood.AMBIENT, MusicMood.LOFI)
    }

    fun windowLabel(availableSeconds: Int?, english: Boolean): String = when {
        availableSeconds == null || availableSeconds < 0 -> if (english) "Choose your listening mood" else "원하는 분위기로 골라요"
        availableSeconds < 60 -> if (english) "A little music before wrapping up" else "마무리 전 잠깐 듣기"
        else -> if (english) "About ${availableSeconds / 60} minutes of listening time" else "약 ${availableSeconds / 60}분의 음악 여유"
    }
}
