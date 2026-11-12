package com.binteum.core

import kotlin.random.Random

enum class NapSound { OFF, WHITE, PINK, BROWN }

/** Offline noise, generated afresh; no recording, network stream, or looped sound asset. */
class NapNoiseGenerator(private val sound: NapSound, seed: Long = System.nanoTime()) {
    private val random = Random(seed)
    private val bands = FloatArray(16) { white() }
    private var counter = 0
    private var brown = 0f

    /** Normalized sample in [-1, 1]. Actual playback applies a separate low gain. */
    fun nextSample(): Float = when (sound) {
        NapSound.OFF -> 0f
        NapSound.WHITE -> white()
        NapSound.PINK -> {
            // Voss-style octave bands: each held random value changes at half the
            // previous band's frequency. Add fresh noise for the highest band.
            counter = (counter + 1) and 0xffff
            val band = Integer.numberOfTrailingZeros(counter).coerceAtMost(bands.lastIndex)
            bands[band] = white()
            ((bands.sum() + white()) * (3f / 17f)).coerceIn(-1f, 1f)
        }
        NapSound.BROWN -> {
            // Leaky integration bounds drift instead of allowing an accumulating DC offset.
            brown = brown * .985f + white() * .04f
            (brown * 3f).coerceIn(-1f, 1f)
        }
    }

    private fun white() = random.nextFloat() * 2f - 1f
}
