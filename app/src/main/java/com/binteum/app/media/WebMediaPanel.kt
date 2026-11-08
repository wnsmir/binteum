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

/**
 * An inline, parked-only web surface. The host owns fresh vehicle/UX state.
 * Nothing is loaded until an allowed user taps a button. No Android JS interface
 * or vehicle information is exposed to the page. A hidden/blocked surface is
 * destroyed, not merely covered or left playing in the background.
 */
class WebMediaPanel(context: Context) : LinearLayout(context) {
    /** Host rechecks current safety/time and starts its fixed window before any content loads. */
    var onContentOpen: (() -> Boolean)? = null
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
    private var selectedPage: Page? = null
    private var playbackAllowed = false
    private var released = false
    private var generation = 0
    private var pollingGeneration = -1
    private var lastWebState = ""
    private var hostReparentDepth = 0

    init {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, 0)
        launcherRow.orientation = VERTICAL
        addView(launcherRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addLauncher(Page.YOUTUBE, AppIcon.PLAY, "공식 예제 영상", "YouTube · 광고와 로딩 시간이 추가될 수 있어요.", "영상 열기")
        addLauncher(Page.REST, AppIcon.LEAF, "60초 눈 휴식", "화면에서 눈을 떼고 잠시 쉬어요. 인터넷 없이 이용할 수 있어요.", "휴식 열기")

        currentTitle.textSize = 26f
        currentTitle.setTypeface(null, Typeface.BOLD)
        currentTitle.setPadding(0, dp(24), 0, dp(12))
        primaryText += currentTitle
        addView(currentTitle)
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        reopenButton = actionButton("다시 열기", AppIcon.RESET, false) {
            selectedPage?.let(::open) ?: setStatus("먼저 콘텐츠를 선택해 주세요.")
        }
        launchButtons += reopenButton
        closeButton = actionButton("닫기", AppIcon.CLOSE, false) {
            destroySurface()
            setStatus("콘텐츠를 닫았어요. 다시 열려면 직접 선택해 주세요.")
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
        setPlaybackAllowed(false, "차량의 정차·안전 상태를 확인하면 열 수 있어요.")
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
    fun setPlaybackAllowed(allowed: Boolean, reason: String = "지금은 웹 콘텐츠를 사용할 수 없어요.") {
        if (released) return
        val wasAllowed = playbackAllowed
        playbackAllowed = allowed
        refreshActions()
        if (!allowed) {
            destroySurface()
            setStatus(reason)
        } else if (!wasAllowed) {
            setStatus("콘텐츠를 선택해 주세요. 영상은 플레이어에서 재생 버튼을 눌러 시작해요.")
        }
    }

    /** Call whenever this panel is no longer the visible activity/tab. */
    fun pauseForHost() {
        setPlaybackAllowed(false, "화면이 바뀌어 콘텐츠를 멈췄어요. 돌아오면 다시 선택해 주세요.")
    }

    /** Call from the owning activity's onDestroy. Safe to call repeatedly. */
    fun release() {
        if (released) return
        playbackAllowed = false
        released = true
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
        if (::reopenButton.isInitialized) reopenButton.isEnabled = playbackAllowed && !released && selectedPage != null
        if (::closeButton.isInitialized) closeButton.isEnabled = webView != null && !released
        themedButtons.forEach { it.view.alpha = if (it.view.isEnabled) 1f else 0.42f }
    }

    private fun refreshPresentation() {
        val title = when (selectedPage) { Page.YOUTUBE -> "공식 예제 영상"; Page.REST -> "60초 눈 휴식"; null -> null }
        currentTitle.text = when {
            webView != null -> "현재 콘텐츠 · $title"
            title != null -> "최근 콘텐츠 · $title"
            else -> "콘텐츠를 선택해 주세요"
        }
        launchers.forEach { item ->
            item.view.background = rounded(palette.surface, dp(24), if (webView != null && selectedPage == item.page) palette.accent else palette.border)
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

    private fun cssColor(color: Int) = "#%06x".format(color and 0xffffff)

    private fun themeDeclarations(): String {
        val colors = cssColors()
        return colors.keys().asSequence().joinToString("") { key -> "$key:${colors.getString(key)};" }
    }

    @Suppress("DEPRECATION", "SetJavaScriptEnabled")
    private fun open(page: Page) {
        if (!playbackAllowed || released) return
        if (onContentOpen?.invoke() == false) {
            setPlaybackAllowed(false, "지금은 콘텐츠를 열 수 없어요. 차량 상태와 남은 시간을 확인해 주세요.")
            return
        }
        destroySurface()
        selectedPage = page
        refreshPresentation()
        lastWebState = ""
        val origin = "https://${context.packageName.lowercase()}"
        val baseUrl = "$origin/media/"
        val html = try {
            context.assets.open(page.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                .replace("__APP_ORIGIN__", origin)
                .replace("__THEME_VARS__", themeDeclarations())
                .replace("__COLOR_SCHEME__", if (palette.dark) "dark" else "light")
        } catch (_: Exception) {
            setStatus("웹 콘텐츠 파일을 열지 못했어요. 앱을 다시 실행해 주세요.")
            return
        }
        val instance = try { WebView(context) } catch (_: Exception) {
            setStatus("이 기기에서 WebView를 시작하지 못했어요. 다른 활동을 선택해 주세요.")
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
            mediaPlaybackRequiresUserGesture = true
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
                setStatus("외부 페이지는 직접 선택한 링크만 열 수 있어요.")
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (webView === view && token == generation && pollingGeneration != token) {
                    applyHtmlPalette(view)
                    pollingGeneration = token
                    pollState(view, token)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && webView === view) {
                    setStatus("웹 콘텐츠를 불러오지 못했어요. 연결을 확인하거나 웹 휴식을 선택해 주세요.")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && webView === view) {
                    setStatus("서버 응답 오류 ${response.statusCode}. 다시 열거나 웹 휴식을 선택해 주세요.")
                }
            }

            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                if (webView === view) setStatus("보안 연결을 확인하지 못해 콘텐츠를 열지 않았어요.")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (webView === view) {
                    generation++
                    handler.removeCallbacksAndMessages(null)
                    webView = null
                    frame.removeView(view)
                    frame.visibility = View.GONE
                    view.destroy()
                    refreshPresentation()
                    setStatus("웹 화면이 종료됐어요. 직접 다시 열거나 다른 활동을 선택해 주세요.")
                }
                return true
            }
        }
        frame.visibility = View.VISIBLE
        frame.addView(instance, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setStatus(if (page == Page.YOUTUBE) "YouTube 플레이어를 연결하고 있어요. 재생 가능 여부는 연결 후 확인됩니다." else "기기 안의 웹 휴식을 열고 있어요.")
        // Android sets this real app identifier as the HTTPS origin/Referer. This
        // does not host a site or assert that this Pleos device is certified.
        instance.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", baseUrl)
    }

    private fun pollState(view: WebView, token: Int) {
        if (webView !== view || generation != token || !playbackAllowed || released) return
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
                    "ready" -> "플레이어에서 재생 버튼을 눌러 주세요."
                    "playing" -> "YouTube 플레이어가 재생 중이에요."
                    "paused" -> "영상이 일시정지됐어요."
                    "buffering" -> "영상을 불러오는 중이에요."
                    "ended" -> "영상이 끝났어요. 충전 상태와 활동 종료는 별도로 확인해 주세요."
                    "autoplay_blocked" -> "자동 재생이 제한됐어요. 플레이어의 재생 버튼을 직접 눌러 주세요."
                    "error" -> playerError(info?.optString("code").orEmpty())
                    "rest_ready" -> "60초 눈 쉬기 페이지에서 시작을 눌러 주세요. 네트워크 없이 사용할 수 있어요."
                    "rest_running" -> "웹 휴식을 진행하고 있어요. 편안한 자세로 쉬어 주세요."
                    "rest_paused" -> "웹 휴식을 멈췄어요. 다시 시작할 수 있어요."
                    "rest_complete" -> "60초 웹 휴식이 끝났어요. 앱에서 직접 마무리해 주세요."
                    else -> "콘텐츠를 준비하고 있어요."
                })
            }
            handler.postDelayed({ pollState(view, token) }, 750L)
        }
    }

    private fun playerError(code: String): String = when (code) {
        "100" -> "예제 영상이 삭제되었거나 비공개예요. 웹 휴식을 선택해 주세요."
        "101", "150" -> "이 영상은 앱 안에서 재생할 수 없어요. 웹 휴식을 선택해 주세요."
        "153" -> "YouTube가 앱 식별정보를 확인하지 못했어요(153). 이 기기의 재생 환경을 점검해야 해요."
        "5" -> "이 기기의 웹 플레이어에서 영상을 재생하지 못했어요(5)."
        "timeout", "script" -> "YouTube 연결을 완료하지 못했어요. 다시 열거나 네트워크 없는 웹 휴식을 이용해 주세요."
        else -> "영상 재생 오류${if (code.isBlank()) "" else " ($code)"}. 다시 열거나 웹 휴식을 선택해 주세요."
    }

    private fun openExternal(uri: Uri) {
        if (!playbackAllowed || released || uri.scheme != "https" || uri.host.isNullOrBlank()) return
        // User-clicked player links go to the system handler, never our WebView.
        // Stop our player before leaving; no background audio or automatic return.
        pauseForHost()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addCategory(Intent.CATEGORY_BROWSABLE) })
        } catch (_: ActivityNotFoundException) {
            setStatus("이 기기에는 링크를 열 수 있는 앱이 없어요. 차량 상태 확인 후 다른 활동을 선택해 주세요.")
        } catch (_: SecurityException) {
            setStatus("이 기기는 외부 링크 열기를 허용하지 않아요. 다른 활동을 선택해 주세요.")
        }
    }

    private fun destroySurface() {
        generation++
        handler.removeCallbacksAndMessages(null)
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
