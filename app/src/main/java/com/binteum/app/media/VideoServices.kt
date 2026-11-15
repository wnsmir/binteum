package com.binteum.app.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.binteum.app.music.MusicBrowserPolicy
import com.binteum.app.music.MusicBrowserResolver

enum class VideoProvider(val displayName: String, internal val appPackage: String, internal val homeUrl: String) {
    YOUTUBE("YouTube", "com.google.android.youtube", "https://www.youtube.com/"),
    NETFLIX("Netflix", "com.netflix.mediaclient", "https://www.netflix.com/");
}

/** Fixed public service homes only; no vehicle, account, search or viewing data in the URL. */
object VideoLinks {
    fun home(provider: VideoProvider): Uri = Uri.parse(provider.homeUrl)
}

enum class VideoOpenResult {
    OPENED_APP, OPENED_BROWSER, NO_HANDLER, BLOCKED;
    val opened: Boolean get() = this == OPENED_APP || this == OPENED_BROWSER
}

/**
 * Provider-owned app first, then a declared normal browser. The emulator's WebView test shell
 * is not a sign-in browser. Opening a destination is not authentication or playback success.
 * The host pauses its own player before this handoff and supplies a fresh safety gate.
 */
class VideoLauncher internal constructor(
    private val context: Context,
    private val canOpen: () -> Boolean,
    private val browserLookup: (Uri) -> List<String>,
) {
    constructor(context: Context, canOpen: () -> Boolean) : this(context, canOpen, {
        MusicBrowserResolver.packages(context, it)
    })

    fun open(provider: VideoProvider): VideoOpenResult {
        if (!canOpen()) return VideoOpenResult.BLOCKED
        val uri = VideoLinks.home(provider)
        if (launch(uri, provider.appPackage)) return VideoOpenResult.OPENED_APP
        for (browser in browserLookup(uri).distinct().filter(MusicBrowserPolicy::isBrowserPackage)) {
            if (!canOpen()) return VideoOpenResult.BLOCKED
            if (launch(uri, browser)) return VideoOpenResult.OPENED_BROWSER
        }
        return if (canOpen()) VideoOpenResult.NO_HANDLER else VideoOpenResult.BLOCKED
    }

    private fun launch(uri: Uri, appPackage: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(appPackage)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
