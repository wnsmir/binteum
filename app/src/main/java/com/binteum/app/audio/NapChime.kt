package com.binteum.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** A foreground wake cue. Never changes the vehicle's volume or promises background delivery. */
class NapChime(context: Context, private val canPlay: () -> Boolean) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var focus: AudioFocusRequest? = null
    private var pulses = 0
    private val pulse = object : Runnable {
        override fun run() {
            if (!canPlay() || pulses-- <= 0) { stop(); return }
            val played = runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 650) == true }.getOrDefault(false)
            if (!played) { stop(); return }
            handler.postDelayed(this, 2_000)
        }
    }

    fun play(preview: Boolean = false): Boolean {
        stop()
        if (!canPlay() || audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) return false
        return try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setOnAudioFocusChangeListener({ if (it < 0) stop() }, handler).build()
            focus = request
            if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                stop(); false
            } else {
                tone = ToneGenerator(AudioManager.STREAM_ALARM, 70)
                val played = tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 650) == true
                if (!played) { stop(); false } else {
                    pulses = if (preview) 0 else 9
                    handler.postDelayed(pulse, 2_000)
                    true
                }
            }
        } catch (_: RuntimeException) { stop(); false }
    }

    fun stop() {
        handler.removeCallbacks(pulse)
        tone?.release(); tone = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
    }
}
