package com.binteum.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.binteum.core.NapNoiseGenerator
import com.binteum.core.NapSound
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

enum class NapSoundStatus { STOPPED, STARTING, PLAYING, MUTED, FOCUS_UNAVAILABLE, FOCUS_LOST, UNAVAILABLE }

data class NapSoundState(
    val status: NapSoundStatus = NapSoundStatus.STOPPED,
    val sound: NapSound = NapSound.OFF,
    val volumePercent: Int = 20,
) {
    val isPlaying: Boolean get() = status == NapSoundStatus.PLAYING
}

/**
 * Foreground-only, locally synthesized rest sound. Public calls/callbacks run on main.
 * Focus loss and host safety loss stop playback; focus gain never restarts it.
 * No system/vehicle volume writes, credentials, media downloads, or background service.
 */
class NapSoundPlayer(
    context: Context,
    private val canPlay: () -> Boolean,
    private val onStateChanged: (NapSoundState) -> Unit = {},
) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "BinteumNapSound") }
    private var active: Playback? = null
    /** Includes a short voluntary fade after active has been cleared. Main-thread owned. */
    private val outstanding = mutableSetOf<Playback>()
    private var closed = false
    private var nextId = 0L

    var state = NapSoundState()
        private set
    val isPlaying: Boolean get() = state.isPlaying

    private class Playback(
        val id: Long,
        val sound: NapSound,
        val volume: AtomicInteger,
        val preview: Boolean,
    ) {
        val stopping = AtomicBoolean(false)
        val immediate = AtomicBoolean(false)
        lateinit var focus: AudioFocusRequest
    }

    private val safetyCheck = object : Runnable {
        override fun run() {
            if (active == null || closed) return
            if (!safe()) stop() else main.postDelayed(this, 100)
        }
    }

    /** True means a silent selection or an accepted start, not verified audible output. */
    fun play(sound: NapSound, volumePercent: Int = 20, preview: Boolean = false): Boolean {
        mainThread()
        stop()
        val volume = volumePercent.coerceIn(0, 100)
        if (closed || !safe()) return false
        if (sound == NapSound.OFF || volume == 0) {
            emit(NapSoundState(if (volume == 0) NapSoundStatus.MUTED else NapSoundStatus.STOPPED, sound, volume))
            return true
        }
        val session = Playback(++nextId, sound, AtomicInteger(volume), preview)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        return try {
            session.focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ change ->
                    if (change < 0 && outstanding.contains(session)) {
                        session.immediate.set(true); session.stopping.set(true)
                        runCatching { audio.abandonAudioFocusRequest(session.focus) }
                        if (active === session) {
                            stop()
                            emit(state.copy(status = NapSoundStatus.FOCUS_LOST))
                        }
                    }
                }, main).build()
            if (audio.requestAudioFocus(session.focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                runCatching { audio.abandonAudioFocusRequest(session.focus) }
                emit(NapSoundState(NapSoundStatus.FOCUS_UNAVAILABLE, sound, volume))
                false
            } else {
                active = session
                outstanding.add(session)
                emit(NapSoundState(NapSoundStatus.STARTING, sound, volume))
                worker.execute { stream(session, attributes) }
                main.post(safetyCheck)
                true
            }
        } catch (_: RuntimeException) {
            if (active === session) active = null
            outstanding.remove(session)
            runCatching { audio.abandonAudioFocusRequest(session.focus) }
            emit(NapSoundState(NapSoundStatus.UNAVAILABLE, sound, volume))
            false
        }
    }

    /** Zero stops and releases focus; raising volume afterwards does not restart audio. */
    fun setVolume(percent: Int) {
        mainThread()
        val volume = percent.coerceIn(0, 100)
        active?.volume?.set(volume)
        if (volume == 0) {
            stop()
            emit(state.copy(status = NapSoundStatus.MUTED, volumePercent = 0))
        } else emit(state.copy(volumePercent = volume,
            status = if (state.status == NapSoundStatus.MUTED) NapSoundStatus.STOPPED else state.status))
    }

    /** Use immediate=true on lifecycle, safety loss, or before a wake chime. */
    fun stop(immediate: Boolean = true) {
        mainThread()
        main.removeCallbacks(safetyCheck)
        val session = active
        active = null
        val ending = if (immediate) outstanding.toList() else listOfNotNull(session)
        ending.forEach { it.immediate.set(immediate); it.stopping.set(true) }
        // For a voluntary fade retain focus until the render worker releases its track.
        if (immediate) ending.forEach { runCatching { audio.abandonAudioFocusRequest(it.focus) } }
        if (!closed) emit(state.copy(status = NapSoundStatus.STOPPED))
    }

    fun close() {
        mainThread()
        stop()
        closed = true
        worker.shutdown()
    }

    private fun stream(session: Playback, attributes: AudioAttributes) {
        var track: AudioTrack? = null
        var failed = false
        try {
            if (session.stopping.get()) return
            val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val output = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, FRAMES * 4)).build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED)
            if (session.stopping.get()) return
            val generator = NapNoiseGenerator(session.sound)
            val samples = ShortArray(FRAMES)
            var gain = 0f
            var writtenFrames = 0L
            output.play()
            main.post {
                if (!closed && active === session && safe()) emit(state.copy(status = NapSoundStatus.PLAYING))
                else if (active === session) stop()
            }
            val startedAt = SystemClock.elapsedRealtime()
            while (!session.immediate.get()) {
                if (session.preview && SystemClock.elapsedRealtime() - startedAt >= PREVIEW_MS) session.stopping.set(true)
                val fadingOut = session.stopping.get()
                val target = if (fadingOut) 0f else MAX_GAIN * session.volume.get() / 100f
                val step = MAX_GAIN / (SAMPLE_RATE * if (fadingOut) .2f else .5f)
                for (index in samples.indices) {
                    val delta = target - gain
                    gain += sign(delta) * min(abs(delta), step)
                    samples[index] = (generator.nextSample() * gain * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
                }
                var offset = 0
                var noProgressAt = SystemClock.elapsedRealtime()
                while (offset < samples.size && !session.immediate.get()) {
                    val count = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0)
                    if (count == 0) {
                        check(SystemClock.elapsedRealtime() - noProgressAt < 1_000)
                        Thread.sleep(5)
                    } else {
                        offset += count; writtenFrames += count
                        noProgressAt = SystemClock.elapsedRealtime()
                    }
                }
                if (fadingOut && gain == 0f) {
                    // Allow only the short final fade buffer to drain; never hold the UI thread.
                    val deadline = SystemClock.elapsedRealtime() + 250
                    while (!session.immediate.get() && SystemClock.elapsedRealtime() < deadline &&
                        (output.playbackHeadPosition.toLong() and 0xffffffffL) < writtenFrames) Thread.sleep(5)
                    break
                }
            }
        } catch (_: Exception) {
            failed = !session.stopping.get()
        } catch (_: LinkageError) {
            failed = !session.stopping.get()
        } finally {
            track?.let { output ->
                runCatching { output.pause() }; runCatching { output.flush() }; runCatching { output.release() }
            }
            val unavailable = failed
            main.post {
                outstanding.remove(session)
                runCatching { audio.abandonAudioFocusRequest(session.focus) }
                if (!closed && active === session) {
                    active = null; main.removeCallbacks(safetyCheck)
                    emit(state.copy(status = if (unavailable) NapSoundStatus.UNAVAILABLE else NapSoundStatus.STOPPED))
                }
            }
        }
    }

    private fun safe() = runCatching(canPlay).getOrDefault(false)
    private fun emit(next: NapSoundState) { state = next; onStateChanged(next) }
    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "Nap sound calls must run on main" }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAMES = 960
        const val MAX_GAIN = .18f
        const val PREVIEW_MS = 8_000L
    }
}
