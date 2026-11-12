package com.binteum.app.music

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

enum class MusicProvider(val displayName: String, internal val appPackage: String, internal val homeUrl: String) {
    YOUTUBE_MUSIC("YouTube Music", "com.google.android.apps.youtube.music", "https://music.youtube.com/"),
    APPLE_MUSIC("Apple Music", "com.apple.android.music", "https://music.apple.com/"),
    SPOTIFY("Spotify", "com.spotify.music", "https://open.spotify.com/");
}

/** Fixed official service destinations. No custom URL, credentials, device position, or playback history. */
object MusicLinks {
    fun home(provider: MusicProvider): Uri = Uri.parse(provider.homeUrl)

    fun search(provider: MusicProvider, mood: MusicMood): Uri = when (provider) {
        MusicProvider.YOUTUBE_MUSIC -> home(provider).buildUpon().appendPath("search")
            .appendQueryParameter("q", mood.searchTerm).build()
        MusicProvider.APPLE_MUSIC -> home(provider).buildUpon().appendPath("search")
            .appendQueryParameter("term", mood.searchTerm).build()
        MusicProvider.SPOTIFY -> home(provider).buildUpon().appendPath("search")
            .appendPath(mood.searchTerm).build()
    }
}

enum class MusicOpenResult { OPENED_APP, OPENED_LINK, NO_HANDLER, BLOCKED;
    val opened: Boolean get() = this == OPENED_APP || this == OPENED_LINK
}

/** An emulator WebView test shell is not an account sign-in browser. */
object MusicBrowserPolicy {
    fun isBrowserPackage(packageName: String): Boolean = packageName.isNotBlank() &&
        !packageName.contains("webview", ignoreCase = true) && packageName != "android"

    fun select(webHandlers: List<String>, declaredBrowsers: Set<String>, preferredPackage: String?): List<String> =
        webHandlers.distinct().filter { it in declaredBrowsers && isBrowserPackage(it) }
            .sortedBy { if (it == preferredPackage) 0 else 1 }
}

/** Limited intent queries only; neither installed-app inventory nor authentication inspection. */
object MusicBrowserResolver {
    fun packages(context: Context, uri: Uri): List<String> = runCatching {
        val manager = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0)
            .mapNotNull { it.activityInfo?.takeIf { activity -> activity.enabled && activity.exported }?.packageName }.toSet()
        val handlers = manager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.takeIf { activity -> activity.enabled && activity.exported }?.packageName }
        val preferred = manager.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        MusicBrowserPolicy.select(handlers, browsers, preferred)
    }.getOrDefault(emptyList())
}

/**
 * Opens the provider-owned app/browser session. An opened destination is not proof of authentication,
 * subscription, or playback. This app never receives passwords, cookies, or provider tokens.
 * The host must pause its own media first and describe external playback as externally controlled.
 */
class MusicLauncher internal constructor(
    private val context: Context,
    private val canOpen: () -> Boolean,
    private val browserLookup: (Uri) -> List<String>,
) {
    constructor(context: Context, canOpen: () -> Boolean) : this(context, canOpen, { MusicBrowserResolver.packages(context, it) })

    fun open(provider: MusicProvider, mood: MusicMood? = null): MusicOpenResult {
        if (!canOpen()) return MusicOpenResult.BLOCKED
        val uri = mood?.let { MusicLinks.search(provider, it) } ?: MusicLinks.home(provider)
        if (launch(uri, provider.appPackage)) return MusicOpenResult.OPENED_APP
        for (browser in browserLookup(uri).filter(MusicBrowserPolicy::isBrowserPackage)) {
            if (!canOpen()) return MusicOpenResult.BLOCKED
            if (launch(uri, browser)) return MusicOpenResult.OPENED_LINK
        }
        return if (canOpen()) MusicOpenResult.NO_HANDLER else MusicOpenResult.BLOCKED
    }

    private fun launch(uri: Uri, appPackage: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        intent.setPackage(appPackage)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Only the explicitly chosen provider is saved. It is never called an account connection. */
class MusicPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("binteum_music", Context.MODE_PRIVATE)

    var provider: MusicProvider
        get() = preferences.getString("provider", null)?.let { stored ->
            MusicProvider.entries.firstOrNull { it.name == stored }
        } ?: MusicProvider.YOUTUBE_MUSIC
        set(value) { preferences.edit().putString("provider", value.name).apply() }

    /** Resets only this app's preference; it does not sign out of the external provider. */
    fun resetProvider() { preferences.edit().remove("provider").apply() }
}
