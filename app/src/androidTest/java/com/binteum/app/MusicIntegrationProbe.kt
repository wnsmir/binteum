package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.binteum.app.music.MusicCatalog
import com.binteum.app.music.MusicLauncher
import com.binteum.app.music.MusicLinks
import com.binteum.app.music.MusicMood
import com.binteum.app.music.MusicOpenResult
import com.binteum.app.music.MusicProvider
import com.binteum.app.music.MusicBrowserPolicy
import com.binteum.app.music.MusicBrowserResolver

/** Local catalog/link/launch-boundary checks. No provider request, UI launch, or account access. */
class MusicIntegrationProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val report = Bundle()
        var checks = 0
        fun verify(value: Boolean) { check(value); checks++ }
        try {
            verify(MusicCatalog.recommend(null).distinct().size == 3)
            verify(MusicCatalog.recommend(-1) == MusicCatalog.recommend(null))
            verify(MusicCatalog.recommend(8 * 60).first() == MusicMood.UPLIFT)
            verify(MusicCatalog.recommend(8 * 60 + 1).first() == MusicMood.LOFI)
            verify(MusicCatalog.recommend(20 * 60).first() == MusicMood.LOFI)
            verify(MusicCatalog.recommend(20 * 60 + 1).first() == MusicMood.JAZZ)
            verify(MusicCatalog.windowLabel(30, false).contains("잠깐"))
            verify(MusicCatalog.windowLabel(null, true).contains("mood"))
            val expectedHosts = mapOf(MusicProvider.YOUTUBE_MUSIC to "music.youtube.com",
                MusicProvider.APPLE_MUSIC to "music.apple.com", MusicProvider.SPOTIFY to "open.spotify.com")
            MusicProvider.entries.forEach { provider ->
                val home = MusicLinks.home(provider)
                verify(home.scheme == "https" && home.host == expectedHosts[provider] && home.userInfo == null && home.fragment == null)
                MusicCatalog.all.forEach { mood ->
                    val uri = MusicLinks.search(provider, mood)
                    verify(uri.scheme == "https" && uri.host == expectedHosts[provider] && uri.userInfo == null && uri.fragment == null &&
                        uri.path?.startsWith("/search") == true && !uri.toString().contains(" ") && uri.queryParameterNames.none {
                            it.contains("token", true) || it.contains("latitude", true) || it.contains("longitude", true)
                        })
                }
            }
            val intents = mutableListOf<Intent>()
            var rejectApp = false
            var rejectAll = false
            val fakeContext = object : ContextWrapper(targetContext) {
                override fun startActivity(intent: Intent) {
                    intents += Intent(intent)
                    if (rejectAll || (rejectApp && intent.`package` in setOf("com.spotify.music", "com.apple.android.music", "com.google.android.apps.youtube.music"))) throw ActivityNotFoundException()
                }
            }
            verify(MusicLauncher(fakeContext) { false }.open(MusicProvider.SPOTIFY) == MusicOpenResult.BLOCKED && intents.isEmpty())
            val launcher = MusicLauncher(fakeContext, { true }, { listOf("com.example.browser") })
            verify(launcher.open(MusicProvider.SPOTIFY, MusicMood.PIANO) == MusicOpenResult.OPENED_APP && intents.size == 1)
            verify(intents.last().`package` == "com.spotify.music" && intents.last().action == Intent.ACTION_VIEW &&
                intents.last().hasCategory(Intent.CATEGORY_BROWSABLE))
            intents.clear(); rejectApp = true
            verify(launcher.open(MusicProvider.APPLE_MUSIC) == MusicOpenResult.OPENED_LINK && intents.size == 2 && intents.last().`package` == "com.example.browser")
            verify(intents.all { it.data?.host == "music.apple.com" })
            intents.clear(); rejectAll = true
            verify(launcher.open(MusicProvider.YOUTUBE_MUSIC) == MusicOpenResult.NO_HANDLER && intents.size == 2)
            intents.clear(); rejectAll = false
            var gateCalls = 0
            verify(MusicLauncher(fakeContext, { ++gateCalls == 1 }, { listOf("com.example.browser") }).open(MusicProvider.SPOTIFY) == MusicOpenResult.BLOCKED && intents.size == 1)
            verify(MusicBrowserPolicy.select(listOf("org.chromium.webview_shell"), setOf("org.chromium.webview_shell"), "org.chromium.webview_shell").isEmpty())
            verify(MusicBrowserPolicy.select(listOf("com.example.linkviewer"), emptySet(), "com.example.linkviewer").isEmpty())
            verify(MusicBrowserPolicy.select(listOf("browser.one", "browser.two"), setOf("browser.one", "browser.two"), "browser.two") == listOf("browser.two", "browser.one"))
            intents.clear()
            verify(MusicLauncher(fakeContext, { true }, { listOf("org.chromium.webview_shell") }).open(MusicProvider.SPOTIFY) == MusicOpenResult.NO_HANDLER && intents.size == 1)
            MusicProvider.entries.forEach { provider ->
                val visible = targetContext.packageManager.resolveActivity(
                    Intent(Intent.ACTION_VIEW, MusicLinks.home(provider)).addCategory(Intent.CATEGORY_BROWSABLE),
                    PackageManager.MATCH_DEFAULT_ONLY,
                ) != null
                report.putBoolean("visibleHandler.${provider.name}", visible)
                report.putInt("eligibleBrowsers.${provider.name}", MusicBrowserResolver.packages(targetContext, MusicLinks.home(provider)).size)
            }
            report.putString("handlerCheck", "READ_ONLY; HTTPS handler alone is not an eligible sign-in browser")
            report.putString("result", "PASS: $checks music catalog, URL, and external-launch boundary checks")
            report.putBoolean("launchedRealUi", false)
            report.putBoolean("accessedAccount", false)
            finish(Activity.RESULT_OK, report)
        } catch (error: Exception) {
            report.putString("result", "FAIL after $checks checks: ${error.javaClass.simpleName}")
            finish(Activity.RESULT_CANCELED, report)
        }
    }
}
