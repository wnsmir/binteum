package com.binteum.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class NapNoiseGeneratorTest {
    @Test fun silentSelectionProducesExactSilence() {
        val generator = NapNoiseGenerator(NapSound.OFF, 5)
        repeat(10_000) { assertEquals(0f, generator.nextSample(), 0f) }
    }

    @Test fun allNoiseColorsStayFiniteAndBoundedWithoutLargeDcDrift() {
        for (sound in listOf(NapSound.WHITE, NapSound.PINK, NapSound.BROWN)) {
            val generator = NapNoiseGenerator(sound, 42)
            var sum = 0.0
            var energy = 0.0
            repeat(480_000) {
                val sample = generator.nextSample()
                assertTrue(sample.isFinite() && sample in -1f..1f)
                sum += sample; energy += sample * sample
            }
            assertTrue("$sound has useful sound energy", energy / 480_000 > .005)
            assertTrue("$sound should not drift toward a DC signal", abs(sum / 480_000) < .09)
        }
    }

    @Test fun pinkAndBrownContainLessFastVariationThanWhite() {
        fun differenceEnergy(sound: NapSound): Double {
            val generator = NapNoiseGenerator(sound, 1234)
            var previous = generator.nextSample()
            var differences = 0.0
            var energy = 0.0
            repeat(96_000) {
                val sample = generator.nextSample()
                differences += (sample - previous) * (sample - previous)
                energy += sample * sample
                previous = sample
            }
            return differences / energy
        }
        val white = differenceEnergy(NapSound.WHITE)
        val pink = differenceEnergy(NapSound.PINK)
        val brown = differenceEnergy(NapSound.BROWN)
        assertTrue(white > 1.5)
        assertTrue(pink < white / 2)
        assertTrue(brown < pink / 2)
    }
}
