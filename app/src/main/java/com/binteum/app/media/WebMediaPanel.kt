package com.binteum.app.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
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
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener
import com.binteum.app.R

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

    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(context)
    private val frame = FrameLayout(context)
    private val launchButtons = mutableListOf<Button>()
    private var webView: WebView? = null
    private var selectedPage: Page? = null
    private var playbackAllowed = false
    private var released = false
    private var generation = 0
    private var pollingGeneration = -1
    private var lastWebState = ""

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundColor(Color.rgb(244, 245, 247))
        addView(TextView(context).apply {
            text = "차 안에서 웹 콘텐츠 열기"
            textSize = 26f
            setTextColor(Color.rgb(28, 31, 36))
        })
        addView(TextView(context).apply {
            setText(R.string.web_media_notice)
            textSize = 24f
            setTextColor(Color.rgb(74, 78, 85))
            setPadding(0, dp(8), 0, dp(8))
        })
        val firstRow = buttonRow()
        addLaunchButton(firstRow, "예제 영상 열기") { open(Page.YOUTUBE) }
        addLaunchButton(firstRow, "60초 웹 휴식") { open(Page.REST) }
        val secondRow = buttonRow()
        addLaunchButton(secondRow, "다시 열기") {
            selectedPage?.let(::open) ?: setStatus("먼저 콘텐츠를 선택해 주세요.")
        }
        secondRow.addView(styledButton("닫기").apply {
            setOnClickListener {
                destroySurface()
                setStatus("콘텐츠를 닫았어요. 다시 보려면 직접 선택해 주세요.")
            }
        }, buttonLayout())
        status.textSize = 24f
        status.setTextColor(Color.rgb(74, 78, 85))
        status.setPadding(0, dp(8), 0, dp(8))
        addView(status)
        // Keep the player controls comfortably larger than YouTube's 200x200 minimum.
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, dp(440)))
        frame.visibility = View.GONE
        setPlaybackAllowed(false, "차량의 정차·안전 상태를 확인하면 열 수 있어요.")
    }

    /** Must be called on the UI thread. true never resumes or loads media. */
    fun setPlaybackAllowed(allowed: Boolean, reason: String = "지금은 웹 콘텐츠를 사용할 수 없어요.") {
        if (released) return
        val wasAllowed = playbackAllowed
        playbackAllowed = allowed
        launchButtons.forEach { it.isEnabled = allowed }
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
        launchButtons.forEach { it.isEnabled = false }
        destroySurface()
    }

    override fun onDetachedFromWindow() {
        pauseForHost()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            frame.layoutParams?.let {
                it.height = ((w - paddingLeft - paddingRight) * 9 / 16 + dp(144)).coerceIn(dp(400), dp(620))
                frame.layoutParams = it
            }
        }
    }

    private fun buttonRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        this@WebMediaPanel.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buttonLayout() = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun styledButton(label: String) = Button(context).apply {
        text = label
        textSize = 24f
        isAllCaps = false
        minHeight = dp(64)
        minimumHeight = dp(64)
        setTextColor(Color.rgb(30, 34, 40))
        backgroundTintList = ColorStateList.valueOf(Color.rgb(226, 229, 234))
    }

    private fun addLaunchButton(row: LinearLayout, label: String, action: () -> Unit) {
        val button = styledButton(label).apply {
            setOnClickListener { if (playbackAllowed && !released) action() }
        }
        launchButtons += button
        row.addView(button, buttonLayout())
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
        lastWebState = ""
        val origin = "https://${context.packageName.lowercase()}"
        val baseUrl = "$origin/media/"
        val html = try {
            context.assets.open(page.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                .replace("__APP_ORIGIN__", origin)
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
        instance.setBackgroundColor(Color.rgb(244, 245, 247))
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
    }

    private fun setStatus(message: String) { status.text = message }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
