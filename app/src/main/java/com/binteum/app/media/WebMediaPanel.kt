package com.binteum.app.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener
import com.binteum.app.ui.AppIcon
import com.binteum.app.ui.AppPalette
import com.binteum.app.ui.AppTheme
import com.binteum.app.ui.IconDrawable
import com.binteum.app.ui.ThemeMode
import com.binteum.core.VideoCatalog
import com.binteum.core.VideoSample
import com.binteum.core.MediaSelectionQueue

/**
 * An inline, parked-only web surface. The host owns fresh vehicle/UX state.
 * Only explicit choices load a player; a recommendation never starts playback. No Android JS interface
 * or vehicle information is exposed to the page. A hidden/blocked surface is
 * destroyed, not merely covered or left playing in the background.
 */
class WebMediaPanel(context: Context) : LinearLayout(context) {
    /** Host rechecks fresh charging and vehicle safety before content loads or plays. */
    var onContentOpen: (() -> Boolean)? = null
    /** App-level category choice only; never reports a video ID or provider playback history. */
    var onVideoSelected: (() -> Unit)? = null
    private enum class Page(val asset: String) {
        YOUTUBE("media/youtube.html"), REST("media/rest.html")
    }

    private data class ThemedButton(val view: Button, val icon: AppIcon, val primary: Boolean)
    private data class Launcher(val page: Page, val view: LinearLayout)

    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(context)
    private val currentTitle = TextView(context)
    private val frame = FrameLayout(context)
    private val launcherRow = LinearLayout(context)
    private val recommendationHeading = TextView(context)
    private val videoChoices = LinearLayout(context).apply { orientation = VERTICAL }
    private var candidates = VideoCatalog.all
    private var selectedVideo: VideoSample = VideoCatalog.all.first()
    private var recommendationKey = ""
    private var browseMode = false
    private var remainingBudgetSeconds: Int? = null
    private val launchButtons = mutableListOf<Button>()
    private val launchers = mutableListOf<Launcher>()
    private val primaryText = mutableListOf<TextView>()
    private val secondaryText = mutableListOf<TextView>()
    private val themedButtons = mutableListOf<ThemedButton>()
    private val iconViews = mutableListOf<Pair<ImageView, AppIcon>>()
    private lateinit var reopenButton: Button
    private lateinit var closeButton: Button
    private var palette = AppTheme.palette(context, ThemeMode.SYSTEM)
    private var webView: WebView? = null
    val hasOpenContent: Boolean get() = webView != null
    private var selectedPage: Page? = null
    private var playbackAllowed = false
    private var released = false
    private var generation = 0
    private var pollingGeneration = -1
    private var lastWebState = ""
    private var hostReparentDepth = 0
    private val videoSelection = MediaSelectionQueue()
    private val thumbnailExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val thumbnails = mutableMapOf<String, android.graphics.Bitmap>()
    private val thumbnailWaiters = mutableMapOf<String, MutableList<ImageView>>()
    private val videoCards = mutableMapOf<String, LinearLayout>()

    init {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, 0)
        recommendationHeading.textSize = 28f
        recommendationHeading.setTypeface(null, Typeface.BOLD)
        recommendationHeading.setPadding(0, dp(12), 0, dp(12))
        primaryText += recommendationHeading
        addView(recommendationHeading)
        addView(videoChoices, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        launcherRow.orientation = VERTICAL
        addView(launcherRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        currentTitle.textSize = 26f
        currentTitle.setTypeface(null, Typeface.BOLD)
        currentTitle.setPadding(0, dp(24), 0, dp(12))
        primaryText += currentTitle
        addView(currentTitle)
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        reopenButton = actionButton(context.getString(com.binteum.app.R.string.ui_text_129), AppIcon.RESET, false) {
            when (selectedPage) {
                Page.YOUTUBE -> selectVideo(selectedVideo.id)
                Page.REST -> open(Page.REST)
                null -> setStatus(context.getString(com.binteum.app.R.string.ui_text_130))
            }
        }
        launchButtons += reopenButton
        closeButton = actionButton(context.getString(com.binteum.app.R.string.ui_text_053), AppIcon.CLOSE, false) {
            destroySurface()
            setStatus(context.getString(com.binteum.app.R.string.ui_text_131))
        }
        actions.addView(reopenButton, actionLayout())
        actions.addView(closeButton, actionLayout())
        addView(actions)
        status.textSize = 24f
        status.setPadding(0, dp(14), 0, dp(16))
        secondaryText += status
        addView(status)
        // Keep the player controls comfortably larger than YouTube's 200x200 minimum.
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, dp(440)))
        frame.visibility = View.GONE
        applyPalette(palette)
        setPlaybackAllowed(false, context.getString(com.binteum.app.R.string.ui_text_132))
    }

    /** Recolors our surfaces only. Does not reload, resume, or touch YouTube's iframe UI. */
    fun applyPalette(palette: AppPalette) {
        this.palette = palette
        setBackgroundColor(Color.TRANSPARENT)
        primaryText.forEach { it.setTextColor(palette.text) }
        secondaryText.forEach { it.setTextColor(palette.textMuted) }
        iconViews.forEach { (view, icon) ->
            view.background = rounded(palette.accent, dp(18))
            view.setImageDrawable(IconDrawable(icon, palette.onAccent, dp(32)))
        }
        themedButtons.forEach { item ->
            val foreground = if (item.primary) palette.onAccent else palette.text
            item.view.setTextColor(foreground)
            item.view.backgroundTintList = ColorStateList.valueOf(if (item.primary) palette.accent else palette.surfaceAlt)
            item.view.setCompoundDrawablesRelativeWithIntrinsicBounds(IconDrawable(item.icon, foreground, dp(26)), null, null, null)
        }
        recommendationKey = "" // Recolor choice rows on the next host update, never reload the player.
        refreshPresentation()
        webView?.let { view ->
            view.setBackgroundColor(palette.surface)
            applyHtmlPalette(view)
        }
    }

    private fun applyHtmlPalette(view: WebView) {
        // Fixed property names and color values only. No URL, user text or native bridge.
        val colors = cssColors()
        val scheme = JSONObject.quote(if (palette.dark) "dark" else "light")
        view.evaluateJavascript("(function(){var c=$colors;Object.keys(c).forEach(function(k){document.documentElement.style.setProperty(k,c[k]);});document.documentElement.style.colorScheme=$scheme;})()", null)
    }

    /** Host uses a synchronous try/finally scope only when moving this visible, allowed panel. */
    fun beginHostReparent() { if (!released) hostReparentDepth++ }

    fun endHostReparent() {
        if (hostReparentDepth > 0) hostReparentDepth--
        if (hostReparentDepth == 0 && !isAttachedToWindow) pauseForHost()
    }

    /** Must be called on the UI thread. true never resumes or loads media. */
    fun setPlaybackAllowed(allowed: Boolean, reason: String = context.getString(com.binteum.app.R.string.ui_text_133)) {
        if (released) return
        val wasAllowed = playbackAllowed
        playbackAllowed = allowed
        refreshActions()
        if (!allowed) {
            destroySurface()
            setStatus(reason)
        } else if (!wasAllowed) {
            recommendationKey = ""
            setStatus(context.getString(com.binteum.app.R.string.ui_text_134))
        }
    }

    /** Browsing is independent from recommendations; duration is advice, safety remains mandatory. */
    fun setBrowseMode(enabled: Boolean) {
        if (browseMode == enabled) return
        browseMode = enabled
        recommendationKey = ""
        updateRecommendations(remainingBudgetSeconds)
    }

    fun updateRecommendations(remainingSeconds: Int?) {
        remainingBudgetSeconds = remainingSeconds?.coerceAtLeast(0)
        candidates = if (browseMode) VideoCatalog.all else VideoCatalog.recommend(remainingBudgetSeconds)
        recommendationHeading.text = if (browseMode) context.getString(com.binteum.app.R.string.media_browse_heading, candidates.size)
            else if (remainingBudgetSeconds == null) context.getString(com.binteum.app.R.string.media_unknown_heading)
            else context.getString(com.binteum.app.R.string.video_recommendation_heading,
                remainingBudgetSeconds!! / 60, remainingBudgetSeconds!! % 60, candidates.size)
        val key = "$browseMode/${remainingBudgetSeconds == null}/" + candidates.joinToString { "${it.id}:${fitsBudget(it)}" }
        if (key != recommendationKey) {
            recommendationKey = key
            videoChoices.removeAllViews()
            videoCards.clear()
            candidates.forEach { sample -> videoChoices.addView(videoCard(sample)) }
            videoChoices.addView(TextView(context).apply {
                text = context.getString(when {
                    remainingBudgetSeconds == null -> com.binteum.app.R.string.media_unknown_duration
                    browseMode -> com.binteum.app.R.string.media_browse_duration
                    else -> com.binteum.app.R.string.ui_text_137
                })
                textSize = 20f; setTextColor(palette.textMuted)
                setPadding(0, dp(6), 0, dp(12))
            })
            refreshPresentation()
        }
    }

    private fun fitsBudget(sample: VideoSample): Boolean? = remainingBudgetSeconds?.let {
        sample.seconds.toLong() + 120 <= it.toLong()
    }

    /** Explicit dashboard/card choice. true means the choice was accepted, not that playback succeeded. */
    fun selectVideo(id: String): Boolean = VideoCatalog.all.firstOrNull { it.id == id }?.let(::playVideo) ?: false

    private fun videoCard(sample: VideoSample): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(20), dp(12))
            background = rounded(palette.surface, dp(22), palette.border)
            layoutParams = LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            isClickable = true; isFocusable = true
            contentDescription = context.getString(com.binteum.app.R.string.video_card_description,
                sample.title, sample.seconds / 60, sample.seconds % 60)
            foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null,
                rounded(Color.WHITE, dp(22)))
            setOnClickListener { playVideo(sample) }
        }
        val preview = FrameLayout(context).apply {
            background = rounded(0xFF15191F.toInt(), dp(14)); clipToOutline = true
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(IconDrawable(AppIcon.PLAY, palette.textMuted, dp(36)))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        preview.addView(image, FrameLayout.LayoutParams(-1, -1))
        val duration = TextView(context).apply {
            text = context.getString(com.binteum.app.R.string.video_duration, sample.seconds / 60, sample.seconds % 60)
            textSize = 18f; setTextColor(Color.WHITE)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(0xDD000000.toInt(), dp(6))
        }
        preview.addView(duration, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(6), dp(6), dp(6), dp(6))
        })
        // Compact enough for the content column in split-screen, while retaining a 16:9 preview.
        val previewWidth = if (resources.configuration.screenWidthDp < 650) 140 else 224
        card.addView(preview, LayoutParams(dp(previewWidth), dp(previewWidth * 9 / 16)))
        val text = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(20), 0, 0, 0)
            addView(TextView(context).apply {
                this.text = sample.title; textSize = 25f; setTextColor(palette.text)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                this.text = sample.author; textSize = 20f; setTextColor(palette.textMuted)
                setPadding(0, dp(8), 0, dp(8))
            })
            addView(TextView(context).apply {
                this.text = context.getString(when (fitsBudget(sample)) {
                    true -> com.binteum.app.R.string.media_fits_duration
                    false -> com.binteum.app.R.string.media_exceeds_duration
                    null -> com.binteum.app.R.string.media_unknown_choice
                })
                textSize = 20f; setTextColor(if (fitsBudget(sample) == false) palette.warning else palette.textMuted)
            })
        }
        card.addView(text, LayoutParams(0, -2, 1f))
        videoCards[sample.id] = card
        loadThumbnail(sample.id, image)
        return card
    }

    private fun loadThumbnail(id: String, image: ImageView) {
        if (!playbackAllowed || released) return
        thumbnails[id]?.let { image.setImageBitmap(it); return }
        val waiting = thumbnailWaiters[id]
        if (waiting != null) { waiting.add(image); return }
        thumbnailWaiters[id] = mutableListOf(image)
        thumbnailExecutor.execute {
            val bitmap = runCatching {
                // IDs only come from the bundled, verified catalog; no arbitrary URLs or credentials.
                val connection = java.net.URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                    connection.instanceFollowRedirects = false
                    if (connection.responseCode == 200) connection.inputStream.use(android.graphics.BitmapFactory::decodeStream) else null
                } finally { connection.disconnect() }
            }.getOrNull()
            handler.post {
                if (!released && bitmap != null) thumbnails[id] = bitmap
                thumbnailWaiters.remove(id)?.forEach { view ->
                    if (!released && bitmap != null) view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun playVideo(sample: VideoSample): Boolean {
        if (!playbackAllowed || released || (!browseMode && candidates.none { it.id == sample.id }) || onContentOpen?.invoke() == false) return false
        selectedVideo = sample
        val existing = webView
        if (existing != null && selectedPage == Page.YOUTUBE) {
            // Retain the last explicit tap even when the document is still loading.
            videoSelection.select(sample.id)
            existing.settings.mediaPlaybackRequiresUserGesture = false
            dispatchPendingVideo(existing, generation)
            refreshPresentation()
        } else open(Page.YOUTUBE, play = true)
        onVideoSelected?.invoke()
        setStatus(context.getString(com.binteum.app.R.string.ui_text_139))
        post { if (playbackAllowed && frame.isAttachedToWindow) frame.requestRectangleOnScreen(android.graphics.Rect(0, 0, frame.width, frame.height), false) }
        return true
    }

    private fun dispatchPendingVideo(view: WebView, token: Int) {
        if (webView !== view || generation != token || !playbackAllowed || released) return
        val request = videoSelection.claim() ?: return
        // The packaged function queues this selection until the official IFrame player is ready.
        // Missing HTML functions must not consume a tap; retry after page-ready/poll callbacks.
        view.evaluateJavascript("(function(){if(typeof window.binteumPlayVideo !== 'function')return false;window.binteumPlayVideo(${JSONObject.quote(request.videoId)});return true;})()") { accepted ->
            if (webView !== view || generation != token || released) return@evaluateJavascript
            if (videoSelection.acknowledge(request, accepted == "true")) dispatchPendingVideo(view, token)
        }
    }

    /** Call whenever this panel is no longer the visible activity/tab. */
    fun pauseForHost() {
        setPlaybackAllowed(false, context.getString(com.binteum.app.R.string.ui_text_140))
    }

    /** Call from the owning activity's onDestroy. Safe to call repeatedly. */
    fun release() {
        if (released) return
        playbackAllowed = false
        released = true
        thumbnailExecutor.shutdownNow()
        thumbnailWaiters.clear()
        thumbnails.clear()
        refreshActions()
        destroySurface()
    }

    override fun onDetachedFromWindow() {
        if (hostReparentDepth == 0) pauseForHost()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            val twoColumns = w - paddingLeft - paddingRight >= dp(800)
            launcherRow.orientation = if (twoColumns) HORIZONTAL else VERTICAL
            launchers.forEachIndexed { index, launcher ->
                launcher.view.layoutParams = if (twoColumns) {
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(if (index == 0) 0 else dp(8), 0, if (index == 0) dp(8) else 0, 0)
                    }
                } else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, dp(12))
                }
            }
            frame.layoutParams?.let {
                it.height = ((w - paddingLeft - paddingRight) * 9 / 16 + dp(144)).coerceIn(dp(400), dp(620))
                frame.layoutParams = it
            }
        }
    }

    private fun actionLayout() = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(4), 0, dp(4), 0)
    }

    private fun actionButton(label: String, icon: AppIcon, primary: Boolean, action: () -> Unit): Button {
        val view = Button(context).apply {
            text = label
            textSize = 24f
            isAllCaps = false
            minHeight = dp(64)
            minimumHeight = dp(64)
            compoundDrawablePadding = dp(12)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = rounded(palette.surfaceAlt, dp(18))
            setOnClickListener { action() }
        }
        themedButtons += ThemedButton(view, icon, primary)
        return view
    }

    private fun addLauncher(page: Page, icon: AppIcon, title: String, description: String, action: String) {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            minimumWidth = dp(280)
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }
        val heading = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val image = ImageView(context).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        iconViews += image to icon
        heading.addView(image, LayoutParams(dp(56), dp(56)))
        val titleView = TextView(context).apply {
            text = title; textSize = 28f; setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), 0, 0, 0)
        }
        primaryText += titleView
        heading.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        card.addView(heading)
        val descriptionView = TextView(context).apply {
            text = description; textSize = 24f; setPadding(0, dp(16), 0, dp(18))
        }
        secondaryText += descriptionView
        card.addView(descriptionView)
        val button = actionButton(action, AppIcon.PLAY, page == Page.YOUTUBE) {
            if (playbackAllowed && !released) open(page)
        }
        card.addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        launchButtons += button
        launcherRow.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        launchers += Launcher(page, card)
    }

    private fun refreshActions() {
        launchButtons.forEach { it.isEnabled = playbackAllowed && !released }
        if (::reopenButton.isInitialized) reopenButton.isEnabled = playbackAllowed && !released && selectedPage != null &&
            (selectedPage != Page.YOUTUBE || browseMode || candidates.any { it.id == selectedVideo.id })
        if (::closeButton.isInitialized) {
            closeButton.isEnabled = webView != null && !released
            closeButton.visibility = if (webView != null) View.VISIBLE else View.GONE
        }
        if (::reopenButton.isInitialized) reopenButton.visibility =
            if (selectedPage != null && webView == null) View.VISIBLE else View.GONE
        currentTitle.visibility = if (selectedPage != null) View.VISIBLE else View.GONE
        launcherRow.visibility = if (webView == null) View.VISIBLE else View.GONE
        themedButtons.forEach { it.view.alpha = if (it.view.isEnabled) 1f else 0.42f }
    }

    private fun refreshPresentation() {
        val title = when (selectedPage) { Page.YOUTUBE -> selectedVideo.title; Page.REST -> context.getString(com.binteum.app.R.string.ui_text_141); null -> null }
        currentTitle.text = when {
            webView != null -> context.getString(com.binteum.app.R.string.current_content, title)
            title != null -> context.getString(com.binteum.app.R.string.recent_content, title)
            else -> context.getString(com.binteum.app.R.string.ui_text_142)
        }
        launchers.forEach { item ->
            item.view.background = rounded(palette.surface, dp(24), if (webView != null && selectedPage == item.page) palette.accent else palette.border)
        }
        videoCards.forEach { (id, card) ->
            card.background = rounded(palette.surface, dp(22), if (webView != null && id == selectedVideo.id) palette.textMuted else palette.border)
            card.isSelected = webView != null && id == selectedVideo.id
        }
        refreshActions()
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun cssColors() = JSONObject().apply {
        put("--app-bg", cssColor(palette.surface))
        put("--app-surface", cssColor(palette.surfaceAlt))
        put("--app-text", cssColor(palette.text))
        put("--app-muted", cssColor(palette.textMuted))
        put("--app-accent", cssColor(palette.accent))
        put("--app-on-accent", cssColor(palette.onAccent))
        put("--app-border", cssColor(palette.border))
    }

    private fun cssColor(color: Int) = "#%06x%02x".format(color and 0xffffff, color ushr 24)

    private fun themeDeclarations(): String {
        val colors = cssColors()
        return colors.keys().asSequence().joinToString("") { key -> "$key:${colors.getString(key)};" }
    }

    @Suppress("DEPRECATION", "SetJavaScriptEnabled")
    private fun open(page: Page, play: Boolean = false) {
        if (!playbackAllowed || released) return
        if (onContentOpen?.invoke() == false) {
            setPlaybackAllowed(false, context.getString(com.binteum.app.R.string.ui_text_143))
            return
        }
        destroySurface()
        selectedPage = page
        if (play && page == Page.YOUTUBE) videoSelection.select(selectedVideo.id)
        refreshPresentation()
        lastWebState = ""
        val origin = "https://${context.packageName.lowercase()}"
        val baseUrl = "$origin/media/"
        val html = try {
            context.assets.open(if (page == Page.YOUTUBE && context.resources.configuration.locales[0].language == "en") "media/youtube-en.html" else page.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                .replace("__VIDEO_ID__", selectedVideo.id)
                .replace("__APP_ORIGIN__", origin)
                .replace("__THEME_VARS__", themeDeclarations())
                .replace("__COLOR_SCHEME__", if (palette.dark) "dark" else "light")
                .replace("__LANG__", if (context.resources.configuration.locales[0].language == "en") "en" else "ko")
        } catch (_: Exception) {
            setStatus(context.getString(com.binteum.app.R.string.ui_text_144))
            return
        }
        val instance = try { WebView(context).apply { settings.textZoom = (context.resources.configuration.fontScale * 100).toInt() } } catch (_: Exception) {
            setStatus(context.getString(com.binteum.app.R.string.ui_text_145))
            return
        }
        webView = instance
        val token = generation
        instance.setBackgroundColor(palette.surface)
        refreshPresentation()
        instance.settings.apply {
            javaScriptEnabled = true // Only our packaged HTML and the official player use JS.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = !play
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
        }
        instance.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        instance.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "https") return true
                val localDocument = uri.host == context.packageName.lowercase() && uri.path == "/media/"
                val playerFrame = uri.host == "www.youtube.com" && uri.path.orEmpty().startsWith("/embed/")
                if (request.hasGesture() && !localDocument && !playerFrame && playbackAllowed) {
                    // Finish WebView's navigation callback before disposing it.
                    handler.post { if (webView === view) openExternal(uri) }
                    return true
                }
                // Embedded player frames stay inside the official player. No general
                // website is ever loaded into the privileged local top-level document.
                if (!request.isForMainFrame) return false
                if (localDocument) return false
                setStatus(context.getString(com.binteum.app.R.string.ui_text_146))
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (webView === view && token == generation && pollingGeneration != token) {
                    applyHtmlPalette(view)
                    videoSelection.markDocumentReady()
                    dispatchPendingVideo(view, token)
                    pollingGeneration = token
                    pollState(view, token)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && webView === view) {
                    setStatus(context.getString(com.binteum.app.R.string.ui_text_147))
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && webView === view) {
                    setStatus(context.getString(com.binteum.app.R.string.server_error, response.statusCode))
                }
            }

            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                if (webView === view) setStatus(context.getString(com.binteum.app.R.string.ui_text_148))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (webView === view) {
                    generation++
                    videoSelection.reset()
                    webView = null
                    frame.removeView(view)
                    frame.visibility = View.GONE
                    view.destroy()
                    refreshPresentation()
                    setStatus(context.getString(com.binteum.app.R.string.ui_text_149))
                }
                return true
            }
        }
        frame.visibility = View.VISIBLE
        frame.addView(instance, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setStatus(if (page == Page.YOUTUBE) context.getString(com.binteum.app.R.string.ui_text_150) else context.getString(com.binteum.app.R.string.ui_text_151))
        // Android sets this real app identifier as the HTTPS origin/Referer. This
        // does not host a site or assert that this Pleos device is certified.
        instance.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", baseUrl)
    }

    private fun pollState(view: WebView, token: Int) {
        if (webView !== view || generation != token || !playbackAllowed || released) return
        dispatchPendingVideo(view, token)
        view.evaluateJavascript("(function(){return window.binteumStatus || null;})()") { value ->
            if (webView !== view || generation != token || !playbackAllowed || released) return@evaluateJavascript
            val info = runCatching {
                when (val decoded = JSONTokener(value).nextValue()) {
                    is JSONObject -> decoded
                    is String -> JSONObject(decoded)
                    else -> null
                }
            }.getOrNull()
            val state = info?.optString("state").orEmpty()
            val key = "$state:${info?.optString("code").orEmpty()}"
            if (state.isNotEmpty() && key != lastWebState) {
                lastWebState = key
                setStatus(when (state) {
                    "ready" -> context.getString(com.binteum.app.R.string.ui_text_152)
                    "playing" -> context.getString(com.binteum.app.R.string.ui_text_153)
                    "paused" -> context.getString(com.binteum.app.R.string.ui_text_154)
                    "buffering" -> context.getString(com.binteum.app.R.string.ui_text_155)
                    "ended" -> context.getString(com.binteum.app.R.string.ui_text_156)
                    "autoplay_blocked" -> context.getString(com.binteum.app.R.string.ui_text_157)
                    "error" -> playerError(info?.optString("code").orEmpty())
                    "rest_ready" -> context.getString(com.binteum.app.R.string.ui_text_158)
                    "rest_running" -> context.getString(com.binteum.app.R.string.ui_text_159)
                    "rest_paused" -> context.getString(com.binteum.app.R.string.ui_text_160)
                    "rest_complete" -> context.getString(com.binteum.app.R.string.ui_text_161)
                    else -> context.getString(com.binteum.app.R.string.ui_text_162)
                })
            }
            handler.postDelayed({ pollState(view, token) }, 750L)
        }
    }

    private fun playerError(code: String): String = when (code) {
        "100" -> context.getString(com.binteum.app.R.string.ui_text_163)
        "101", "150" -> context.getString(com.binteum.app.R.string.ui_text_164)
        "153" -> context.getString(com.binteum.app.R.string.ui_text_165)
        "5" -> context.getString(com.binteum.app.R.string.ui_text_166)
        "timeout", "script" -> context.getString(com.binteum.app.R.string.ui_text_167)
        else -> context.getString(com.binteum.app.R.string.video_error, code)
    }

    private fun openExternal(uri: Uri) {
        if (!playbackAllowed || released || uri.scheme != "https" || uri.host.isNullOrBlank()) return
        // User-clicked player links go to the system handler, never our WebView.
        // Stop our player before leaving; no background audio or automatic return.
        pauseForHost()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addCategory(Intent.CATEGORY_BROWSABLE) })
        } catch (_: ActivityNotFoundException) {
            setStatus(context.getString(com.binteum.app.R.string.ui_text_168))
        } catch (_: SecurityException) {
            setStatus(context.getString(com.binteum.app.R.string.ui_text_169))
        }
    }

    private fun destroySurface() {
        generation++
        videoSelection.reset()
        val previous = webView
        webView = null
        if (previous != null) {
            previous.stopLoading()
            previous.onPause()
            previous.loadUrl("about:blank")
            frame.removeView(previous)
            previous.destroy()
        }
        frame.removeAllViews()
        frame.visibility = View.GONE
        refreshPresentation()
    }

    private fun setStatus(message: String) { status.text = message }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
