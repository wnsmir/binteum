package com.binteum.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import com.binteum.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Foreground-only speech. Debug credentials select SERVER; otherwise ON_DEVICE is used. */
class CompletionVoice(context: Context, private val canSpeak: () -> Boolean, private val onFailure: (ErrorType?) -> Unit) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private var engine: TextToSpeech? = null
    private var events: EventListener? = null
    private var focus: AudioFocusRequest? = null
    private var generation = 0
    private val timeout = Runnable { fail() }

    fun speak(text: String, locale: Locale) {
        stop()
        if (!canSpeak()) return
        val token = generation
        fun current(action: () -> Unit) { handler.post { if (token == generation && engine != null) action() } }
        try {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                    if (change < 0 && token == generation) stop()
                }, handler).build()
            focus = request
            if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { fail(); return }
            // Development credentials stay in private app storage, never in source or APK constants.
            val credentialsFile = File(app.filesDir, "pleos-tts.json")
            val credentials = if (BuildConfig.DEBUG && credentialsFile.isFile) {
                val config = JSONObject(credentialsFile.readText())
                val id = config.getString("clientId").trim()
                val secret = config.getString("clientSecret").trim()
                require(id.isNotEmpty() && secret.isNotEmpty())
                id to secret
            } else null
            val tts = TextToSpeech(app, if (credentials != null) Mode.SERVER else Mode.ON_DEVICE)
            engine = tts
            val listener = object : EventListener {
                override fun onReady() = Unit
                override fun onStart() = current { if (!canSpeak()) stop() else Log.i("CompletionVoice", "SDK start callback received; awaiting completion") }
                override fun onDone() = current { Log.i("CompletionVoice", "Playback completed"); stop() }
                override fun onStop() = current { stop() }
                override fun onError(errorType: ErrorType) = current { Log.w("CompletionVoice", "Speech error: $errorType"); fail(errorType) }
                override fun onUpdatedRms(rms: Double) = Unit
            }
            events = listener
            handler.postDelayed(timeout, 30_000)
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() = current {
                    if (!canSpeak()) { stop(); return@current }
                    try {
                        tts.addEventListener(listener)
                        tts.setLocale(locale)
                        tts.setAudioAttributes(attributes)
                        if (credentials == null) tts.speak(text, true)
                        else tts.registerApp(credentials.first, credentials.second, object : OnServerConnectionListener {
                            override fun onConnected() = current {
                                if (!canSpeak()) { stop(); return@current }
                                try { tts.speak(text, true) }
                                catch (_: Exception) { Log.w("CompletionVoice", "Server speech request failed"); fail() }
                            }
                            override fun onFailed(errorType: ErrorType) = current {
                                Log.w("CompletionVoice", "Server registration failed: $errorType"); fail(errorType)
                            }
                        })
                    } catch (error: Exception) { Log.w("CompletionVoice", "Speech request failed", error); fail() }
                }
                override fun onFailure(errorType: ErrorType) = current { Log.w("CompletionVoice", "Initialization failed: $errorType"); fail(errorType) }
            })
        } catch (error: Exception) { Log.w("CompletionVoice", "Speech unavailable: ${error.javaClass.simpleName}"); fail() }
    }

    private fun fail(error: ErrorType? = null) {
        val visible = canSpeak()
        stop()
        if (visible) onFailure(error)
    }

    fun stop() {
        generation++
        handler.removeCallbacks(timeout)
        val old = engine; engine = null
        events?.let { runCatching { old?.removeEventListener(it) } }; events = null
        runCatching { old?.stop() }
        runCatching { old?.release() }
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
    }
}
