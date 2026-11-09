package com.binteum.app

import android.app.Instrumentation
import android.os.Bundle
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit opt-in device diagnostic: no app UI, custom audio attributes, or app focus logic. */
class TtsSdkProbe : Instrumentation() {
    private var language = "ko"
    private var server = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        language = arguments?.getString("language") ?: "ko"
        server = arguments?.getString("mode") == "server"
        start()
    }
    override fun onStart() {
        val latch = CountDownLatch(1)
        var outcome = "timeout"
        var initialized = false
        var started = false
        var sdk: TextToSpeech? = null
        val listener = object : EventListener {
            override fun onReady() = Unit
            override fun onStart() { started = true }
            override fun onDone() { outcome = "completed"; latch.countDown() }
            override fun onStop() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onError(errorType: ErrorType) { outcome = "synthesis:$errorType"; latch.countDown() }
        }
        try {
            runOnMainSync {
                try {
                    val credentials = if (server) JSONObject(File(targetContext.filesDir, "pleos-tts.json").readText()) else null
                    val tts = TextToSpeech(targetContext.applicationContext, if (server) Mode.SERVER else Mode.ON_DEVICE)
                    sdk = tts
                    tts.initialize(object : OnInitializeListener {
                        override fun onSuccess() {
                            initialized = true
                            try {
                                tts.addEventListener(listener)
                                tts.setLocale(if (language == "en") Locale.US else Locale.KOREA)
                                fun speak() = tts.speak(if (language == "en") "Your timer has finished." else "설정한 시간이 끝났습니다.", true)
                                if (credentials == null) speak()
                                else tts.registerApp(credentials.getString("clientId"), credentials.getString("clientSecret"), object : OnServerConnectionListener {
                                    override fun onConnected() {
                                        try { speak() } catch (_: Exception) { outcome = "server-request-failed"; latch.countDown() }
                                    }
                                    override fun onFailed(errorType: ErrorType) { outcome = "registration:$errorType"; latch.countDown() }
                                })
                            } catch (error: Exception) { outcome = "request:${error.javaClass.simpleName}"; latch.countDown() }
                        }
                        override fun onFailure(errorType: ErrorType) { outcome = "initialize:$errorType"; latch.countDown() }
                    })
                } catch (error: Exception) { outcome = "create:${error.javaClass.simpleName}"; latch.countDown() }
            }
            latch.await(25, TimeUnit.SECONDS)
        } finally {
            runOnMainSync {
                runCatching { sdk?.removeEventListener(listener) }
                runCatching { sdk?.stop() }
                runCatching { sdk?.release() }
            }
        }
        finish(if (outcome == "completed") -1 else 0, Bundle().apply {
            putString("mode", if (server) "server" else "on-device"); putString("outcome", outcome); putString("language", language)
            putBoolean("initialized", initialized); putBoolean("startCallback", started)
        })
    }
}
