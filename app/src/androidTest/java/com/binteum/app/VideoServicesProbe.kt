package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import com.binteum.app.media.*

/** Fake startActivity only: no external UI, accounts, provider requests or vehicle operations. */
class VideoServicesProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var checks = 0
        fun verify(value: Boolean, description: String) { check(value) { description }; checks++ }
        try {
            val expected = mapOf(
                VideoProvider.YOUTUBE to ("www.youtube.com" to "com.google.android.youtube"),
                VideoProvider.NETFLIX to ("www.netflix.com" to "com.netflix.mediaclient"),
            )
            val intents = mutableListOf<Intent>()
            var rejectApps = false
            var rejectEverything = false
            var securityFailure = false
            val appPackages = expected.values.map { it.second }.toSet()
            val context = object : ContextWrapper(targetContext) {
                override fun startActivity(intent: Intent) {
                    intents += Intent(intent)
                    if (securityFailure) throw SecurityException("fake denial")
                    if (rejectEverything || (rejectApps && intent.`package` in appPackages)) throw ActivityNotFoundException()
                }
            }
            for ((provider, details) in expected) {
                val uri = VideoLinks.home(provider)
                verify(uri.scheme == "https" && uri.host == details.first && uri.path == "/" &&
                    uri.userInfo == null && uri.query == null && uri.fragment == null, "fixed private-data-free home URL")
                intents.clear(); rejectApps = false
                val launcher = VideoLauncher(context, { true }, { listOf("example.browser") })
                verify(launcher.open(provider) == VideoOpenResult.OPENED_APP && intents.size == 1, "official app preferred")
                verify(intents.single().`package` == details.second && intents.single().action == Intent.ACTION_VIEW &&
                    intents.single().hasCategory(Intent.CATEGORY_BROWSABLE), "explicit official app handoff")
                intents.clear(); rejectApps = true
                verify(launcher.open(provider) == VideoOpenResult.OPENED_BROWSER && intents.size == 2, "ordinary browser fallback")
                verify(intents.last().`package` == "example.browser" && intents.all { it.data == uri }, "same fixed URL in browser")
            }
            intents.clear()
            verify(VideoLauncher(context, { false }, { listOf("example.browser") }).open(VideoProvider.YOUTUBE) == VideoOpenResult.BLOCKED &&
                intents.isEmpty(), "unsafe state never launches")
            intents.clear()
            var gateCalls = 0
            verify(VideoLauncher(context, { ++gateCalls == 1 }, { listOf("example.browser") }).open(VideoProvider.NETFLIX) == VideoOpenResult.BLOCKED &&
                intents.size == 1, "safety is rechecked after app failure before browser")
            intents.clear()
            verify(VideoLauncher(context, { true }, { listOf("org.chromium.webview_shell", "android") }).open(VideoProvider.YOUTUBE) == VideoOpenResult.NO_HANDLER &&
                intents.size == 1, "test shells are not sign-in browsers")
            intents.clear(); rejectEverything = true
            verify(VideoLauncher(context, { true }, { listOf("example.browser") }).open(VideoProvider.NETFLIX) == VideoOpenResult.NO_HANDLER &&
                intents.size == 2, "missing app and browser returns QR-capable result")
            intents.clear(); securityFailure = true
            verify(VideoLauncher(context, { true }, { emptyList() }).open(VideoProvider.NETFLIX) == VideoOpenResult.NO_HANDLER &&
                intents.size == 1, "security denial does not crash")
            verify(VideoOpenResult.OPENED_APP.opened && VideoOpenResult.OPENED_BROWSER.opened &&
                !VideoOpenResult.NO_HANDLER.opened && !VideoOpenResult.BLOCKED.opened, "launch result does not invent success")
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "PASS: $checks external video handoff checks (no real launch)") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("result", "FAIL after $checks: ${error.message}") })
        }
    }
}
