package com.binteum.app

import android.app.AlertDialog
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.binteum.app.media.WebMediaPanel
import com.binteum.app.vehicle.PleosVehicleDataSource
import com.binteum.app.ui.*
import com.binteum.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

class MainActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) {
        val preferences = base.getSharedPreferences("binteum_experience", MODE_PRIVATE)
        val config = Configuration(base.resources.configuration)
        val scale = preferences.getFloat("textScale", 1f).takeIf { it in .85f..1.3f } ?: 1f
        config.fontScale = config.fontScale * scale
        val language = preferences.getString("language", "ko").takeIf { it == "ko" || it == "en" } ?: "ko"
        config.setLocale(java.util.Locale.forLanguageTag(language))
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        outState.putIntArray("screenHistory", screenHistory.toIntArray())
        outState.putInt("activityReturnScroll", activityReturnScroll)
        super.onSaveInstanceState(outState)
    }

    private fun changeReadingPreference(key: String, language: String? = null, scale: Float? = null) {
        val edit = prefs.edit()
        if (language != null) edit.putString(key, language)
        if (scale != null) edit.putFloat(key, scale)
        edit.apply()
        saveCheckpoint()
        recreate()
    }

    private fun backgroundTitle(mode: BackgroundMode): String = getString(when (mode) {
        BackgroundMode.AUTO -> R.string.sky_auto
        BackgroundMode.DAWN -> R.string.sky_dawn
        BackgroundMode.DAY -> R.string.sky_day
        BackgroundMode.SUNSET -> R.string.sky_sunset
        BackgroundMode.NIGHT -> R.string.sky_night
        BackgroundMode.BLACK -> R.string.sky_black
        BackgroundMode.WHITE -> R.string.sky_white
    })

    private lateinit var completionVoice: com.binteum.app.audio.CompletionVoice
    private fun announceCompletion() {
        if (prefs.getBoolean("completionVoice", true) && resumed && safeToConfigure()) {
            completionVoice.speak(tx("빈틈의실 음성 안내입니다.", "This is a Binteum voice preview."),
                if (resources.configuration.locales[0].language == "en") java.util.Locale.US else java.util.Locale.KOREA)
        }
    }
    private var themeMode = ThemeMode.DARK
    private var backgroundMode = BackgroundMode.AUTO
    private var backgroundMotion = true
    private var panelOnRight = false
    private val ambientBackground = AmbientBackgroundDrawable()
    private lateinit var palette: AppPalette
    private val ink get() = palette.text
    private val muted get() = palette.textMuted
    private val mint get() = palette.accent
    private val paper get() = palette.background
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("binteum_experience", MODE_PRIVATE) }
    private lateinit var source: VehicleDataSource
    private lateinit var media: WebMediaPanel
    private var target = 80
    private val chargePlanner = ChargeTimePlanner()
    private lateinit var usageStore: UsageStore
    private var usageRecords = emptyList<UsageEvent>()
    private var departureAtMillis: Long? = null
    private var followVehicleTarget = true
    private var chargeGauge: ChargeGaugeView? = null
    private var estimateLabel: TextView? = null
    private var estimateSourceLabel: TextView? = null
    private var goalLabel: TextView? = null
    private var pendingVideoEntry = UsageEntryPoint.MANUAL
    private var vehicleTargetButton: Button? = null
    private var session = ChargingSession(80, targetEnabled = false)
    private var status = SessionStatus.WAITING_FOR_DATA
    private var snapshot: VehicleSnapshot? = null
    private var sourceStartedAt = 0L
    private var started = false
    private var resumed = false
    private var demoMode = false
    private var demoScenario = 0
    private var tab = 0
    private val screenHistory = ArrayDeque<Int>()
    private val screenScrolls = mutableMapOf<Int, Pair<Int, Int>>()
    private var pendingScrollRestore: Pair<Int, Int>? = null
    private var activityReturnScroll = 0
    private var developerOptionsExpanded = false
    private var moreActivitiesExpanded = false
    private val expandedSettings = mutableSetOf<String>()
    private var transientDialog: android.app.Dialog? = null
    private var connectionCard: LinearLayout? = null
    private var animateConnectionRefresh = false
    private val connectionRefresh = Runnable {
        val card = connectionCard
        if (tab == 2 && card?.isAttachedToWindow == true && started) {
            val parent = card.parent as? ViewGroup
            if (parent != null && animateConnectionRefresh && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                android.transition.TransitionManager.endTransitions(parent)
                val transition = android.transition.TransitionSet().apply {
                    ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                    addTransition(android.transition.ChangeBounds())
                    addTransition(android.transition.Fade(android.transition.Fade.IN or android.transition.Fade.OUT))
                    duration = 260
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    setMatchOrder(android.transition.Transition.MATCH_NAME, android.transition.Transition.MATCH_INSTANCE)
                }
                android.transition.TransitionManager.beginDelayedTransition(parent, transition)
            }
            card.removeAllViews()
            populateConnectionSettings(card)
            updateLabels()
        }
        animateConnectionRefresh = false
    }
    private var learningOn = true
    private var message = ""
    private var active: Run? = null
    private var recovery: Run? = null
    private var completed: Run? = null
    private var quizIndex = 0
    private var quizAnswer: Int? = null
    private var socLabel: TextView? = null
    private var statusLabel: TextView? = null
    private var timerLabel: TextView? = null
    private var diagnosticsLabel: TextView? = null
    private var leftScroll: ScrollView? = null
    private var contentScroll: ScrollView? = null
    private var lastWindowContentKey = ""
    private var lastReadinessKey = ""
    private var lastDebugObservation = ""
    private data class Run(val activityId: String, val attemptId: String, val startedElapsed: Long,
                           val startedWall: Long, val bucket: String)
    private val ticker = object : Runnable {
        override fun run() {
            if (!started) return
            ambientBackground.updateClock()
            ambientBackground.setActive(resumed && safeToConfigure())
            if (demoMode && demoScenario != 4) emitDemoScenario()
            snapshot?.let(::acceptSnapshot)
            if (!safeToConfigure()) completionVoice.stop()
            updateLabels()
            refreshMediaGate()
            if (tab == 0 && allowed() && active == null && recovery == null && completed == null &&
                windowContentKey() != lastWindowContentKey) render(preserveScroll = true)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab", 0) ?: 0
        savedInstanceState?.getIntArray("screenHistory")?.filter { it in 0..3 }?.takeLast(16)?.forEach(screenHistory::addLast)
        activityReturnScroll = savedInstanceState?.getInt("activityReturnScroll", 0) ?: 0
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "DARK") ?: "DARK") }.getOrDefault(ThemeMode.SYSTEM)
        palette = AppTheme.palette(this, themeMode)
        backgroundMode = runCatching {
            BackgroundMode.valueOf(prefs.getString("backgroundMode", "AUTO") ?: "AUTO")
        }.getOrDefault(BackgroundMode.AUTO)
        backgroundMotion = prefs.getBoolean("backgroundMotion", true)
        panelOnRight = prefs.getBoolean("panelOnRight", false)
        target = prefs.getInt("target", 80).takeIf { it in 1..100 } ?: 80
        followVehicleTarget = prefs.getBoolean("followVehicleTarget", true)
        departureAtMillis = prefs.getLong("departureAtMillis", 0L).takeIf { it > System.currentTimeMillis() }
        if (departureAtMillis == null) prefs.edit().remove("departureAtMillis").apply()
        usageStore = UsageStore(this)
        usageRecords = usageStore.recent()
        learningOn = prefs.getBoolean("learningOn", true)
        demoMode = BuildConfig.DEBUG && prefs.getBoolean("demoMode", false)
        demoScenario = prefs.getInt("demoScenario", 0).coerceIn(0, 4)
        completionVoice = com.binteum.app.audio.CompletionVoice(this,
            { resumed && safeToConfigure() && prefs.getBoolean("completionVoice", true) },
            { error -> android.widget.Toast.makeText(this,
                when (error) {
                    ai.pleos.playground.tts.constant.ErrorType.ON_DEVICE_ENGINE_ERROR -> R.string.voice_engine_failed
                    ai.pleos.playground.tts.constant.ErrorType.PERMISSION_DENIED -> R.string.voice_permission_failed
                    else -> R.string.voice_failed
                },
                android.widget.Toast.LENGTH_LONG).show() })
        media = WebMediaPanel(this)
        media.setBrowseMode(true)
        media.onContentOpen = { tab == 1 && allowed() && active == null && recovery == null }
        media.onVideoSelected = {
            recordChoice(UsageCategory.VIDEO, pendingVideoEntry)
            pendingVideoEntry = UsageEntryPoint.MANUAL
        }
        source = makeSource()
        restoreCheckpoint()
        session = ChargingSession(target, targetEnabled = false)
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true
        snapshot = null
        chargePlanner.reset()
        status = SessionStatus.WAITING_FOR_DATA
        sourceStartedAt = System.currentTimeMillis()
        media.setPlaybackAllowed(false, getString(com.binteum.app.R.string.ui_text_003))
        render()
        source.start { value -> runOnUiThread { if (started) acceptSnapshot(value) } }
        if (demoMode) emitDemoScenario()
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        completionVoice.stop()
        ambientBackground.setActive(false)
        transientDialog?.dismiss()
        transientDialog = null
        media.pauseForHost()
        saveCheckpoint()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        snapshot?.let(::acceptSnapshot)
        render()
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(connectionRefresh)
        active?.let { recovery = it; active = null }
        saveCheckpoint()
        source.stop()
        super.onStop()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!goBack()) super.onBackPressed()
    }

    override fun onDestroy() { completionVoice.stop(); ambientBackground.release(); media.release(); usageStore.close(); super.onDestroy() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        palette = AppTheme.palette(this, themeMode)
        render(preserveScroll = true)
    }

    private fun makeSource(): VehicleDataSource = if (demoMode) DemoVehicleDataSource() else PleosVehicleDataSource(this)

    private fun acceptSnapshot(value: VehicleSnapshot) {
        if (snapshot?.charging == false && value.charging == true && departureAtMillis?.let { it <= System.currentTimeMillis() } == true) {
            departureAtMillis = null; prefs.edit().remove("departureAtMillis").apply()
        }
        snapshot = value
        if (followVehicleTarget) value.vehicleTargetPercent?.takeIf { it in 1..100 }?.let { target = it }
        chargePlanner.observe(value, System.currentTimeMillis())
        val previous = status
        val result = if (value.observedAtMillis < sourceStartedAt) SessionResult(SessionStatus.WAITING_FOR_DATA)
            else session.update(value, System.currentTimeMillis())
        status = result.status
        if (status == SessionStatus.CHARGING && previous != status && active == null && recovery == null) {
            message = ""
        }
        if (status != SessionStatus.CHARGING) {
            media.setPlaybackAllowed(false, statusText())
            if (active != null) {
                interruptActivity(getString(R.string.activity_interrupted, statusText()))
            }
            if (status != SessionStatus.WAITING_FOR_DATA && recovery != null) {
                recovery?.let { finishChoice(it, false) }
                recovery = null
                message = getString(com.binteum.app.R.string.ui_text_004)
                saveCheckpoint()
            }
        }
        val readinessKey = "$status/${hasEnergyPermission()}/${value.stateOfCharge != null}/${value.charging != null}/${value.uxRestricted}"
        if (BuildConfig.DEBUG && !demoMode) {
            val diagnostic = "status=$status soc=${value.stateOfCharge} charging=${value.charging} uxRestricted=${value.uxRestricted} / " +
                (source as? PleosVehicleDataSource)?.diagnostics.orEmpty()
            if (diagnostic != lastDebugObservation) {
                Log.i("BinteumVehicle", diagnostic)
                lastDebugObservation = diagnostic
            }
        }
        if (previous != status || lastReadinessKey != readinessKey) {
            lastReadinessKey = readinessKey
            render(preserveScroll = true)
        } else updateLabels()
        if (!safeToConfigure()) transientDialog?.dismiss()
        maybeShowEnergyIntroduction()
    }

    private fun statusText() = when (status) {
        SessionStatus.CHARGING -> getString(com.binteum.app.R.string.ui_text_005)
        SessionStatus.TARGET_REACHED -> getString(com.binteum.app.R.string.ui_text_006)
        SessionStatus.INTERRUPTED -> getString(com.binteum.app.R.string.ui_text_007)
        SessionStatus.DRIVING_LOCKED -> getString(com.binteum.app.R.string.ui_text_008)
        SessionStatus.SUSPENDED -> getString(com.binteum.app.R.string.ui_text_009)
        SessionStatus.WAITING_FOR_DATA -> getString(com.binteum.app.R.string.ui_text_010)
    }

    private fun safeToConfigure(): Boolean = started && resumed && snapshot?.let {
        it.vehicleAwake && it.uxRestricted == false &&
            System.currentTimeMillis() - it.observedAtMillis in 0..30_000 && it.observedAtMillis >= sourceStartedAt
    } == true
    private fun allowed(): Boolean = safeToConfigure() && snapshot?.charging == true
    private fun chargeEstimate() = chargePlanner.estimate(snapshot, target, System.currentTimeMillis())
    private fun chargeWindow() = ChargeWindow.resolve(chargeEstimate(), departureAtMillis, System.currentTimeMillis())
    private fun recommendationSeconds(): Int? = ChargeWindow.forRecommendations(chargeEstimate(), departureAtMillis, System.currentTimeMillis()).seconds
    private fun refreshMediaGate() {
        media.updateRecommendations(chargeWindow().seconds)
        media.setPlaybackAllowed(tab == 1 && allowed() && active == null && recovery == null && completed == null, statusText())
    }
    private fun remainingWindowSeconds(): Int = chargeWindow().seconds ?: Int.MAX_VALUE
    private fun windowContentKey(): String = listOf(allowed(), chargeEstimate().source, target,
        UsagePreferences.timeBucket(chargeWindow().seconds), recommendationSeconds()?.let { seconds ->
            (ActivityCatalog.all + ActivityCatalog.guided).filter { it.durationSeconds + 120 <= seconds }.map { it.id }
        }, VideoCatalog.recommend(recommendationSeconds()).map { it.id }).joinToString("/")
    private fun remainingActivitySeconds(run: Run): Int {
        val duration = ActivityCatalog.find(run.activityId)?.durationSeconds ?: return 0
        return (duration - (SystemClock.elapsedRealtime() - run.startedElapsed).coerceAtLeast(0) / 1000).coerceAtLeast(0).toInt()
    }
    private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)
    private fun bucket() = UsagePreferences.timeBucket(chargeEstimate().seconds).name
    private fun tx(korean: String, english: String) = if (resources.configuration.locales[0].language == "en") english else korean
    private fun minutes(seconds: Int?) = if (seconds == null) tx("확인 중", "Unavailable")
        else if (seconds == 0) tx("목표 도달", "Target reached")
        else tx("약 ${(seconds + 59) / 60}분", "About ${(seconds + 59) / 60} min")
    private fun sourceTitle(source: ChargeTimeSource) = when (source) {
        ChargeTimeSource.VEHICLE -> tx("차량이 제공한 예상시간", "Vehicle estimate")
        ChargeTimeSource.RECENT_RATE -> tx("최근 충전 속도로 추정 · 달라질 수 있어요", "Estimated from recent charge rate · may change")
        ChargeTimeSource.DEPARTURE -> tx("출발 시각 기준 · 충전 완료 예상 아님", "Until departure · not a charge completion estimate")
        ChargeTimeSource.TARGET_REACHED -> tx("목표 충전량에 도달했어요", "Charge target reached")
        ChargeTimeSource.UNKNOWN -> tx("충전 예상시간을 확인하고 있어요", "Waiting for a charge estimate")
    }

    private fun render(preserveScroll: Boolean = false) {
        if (!::media.isInitialized) return
        palette = AppTheme.palette(this, themeMode, backgroundMode)
        handler.removeCallbacks(connectionRefresh)
        animateConnectionRefresh = false
        connectionCard = null
        val scrollRestore = pendingScrollRestore
        pendingScrollRestore = null
        val previousLeftScroll = scrollRestore?.first ?: if (preserveScroll) leftScroll?.scrollY ?: 0 else 0
        val previousContentScroll = scrollRestore?.second ?: if (preserveScroll) contentScroll?.scrollY ?: 0 else 0
        lastWindowContentKey = windowContentKey()
        val preserveMedia = tab == 1 && allowed() && active == null && recovery == null && completed == null
        if (preserveMedia) media.beginHostReparent()
        try {
            (media.parent as? ViewGroup)?.removeView(media)
            media.applyPalette(palette)
            timerLabel = null; diagnosticsLabel = null
            window.statusBarColor = paper
            window.navigationBarColor = palette.surface
            // The same drawable survives UI rebuilds so its motion never restarts on tab/theme changes.
            ambientBackground.configure(backgroundMode, palette.dark, backgroundMotion)
            val root = column().apply {
                background = ambientBackground
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            root.addView(buildTopBar(), LinearLayout.LayoutParams(-1, -2))
            val wide = resources.configuration.screenWidthDp >= 1100
            val body = if (wide) row() else column()
            val left = buildLeft(!wide)
            val right = buildRight()
            if (wide) {
                val sideWidth = if (resources.configuration.screenWidthDp >= 1500) 350 else 320
                body.setPadding(dp(16), 0, dp(16), dp(12))
                leftScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(left) }
                contentScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(right) }
                val panelParams = LinearLayout.LayoutParams(dp(sideWidth), -1)
                val contentParams = LinearLayout.LayoutParams(0, -1, 1f)
                if (panelOnRight) {
                    body.addView(contentScroll, contentParams)
                    body.addView(leftScroll, panelParams)
                } else {
                    body.addView(leftScroll, panelParams)
                    body.addView(contentScroll, contentParams)
                }
                root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                body.setPadding(dp(16), 0, dp(16), dp(12))
                body.addView(left); body.addView(right)
                leftScroll = null
                contentScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
                root.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            if (tab != 2) root.addView(buildNavigation(), LinearLayout.LayoutParams(-1, -2))
            setContentView(root)
            ambientBackground.callback = root
            ambientBackground.setActive(resumed && safeToConfigure())
            updateSystemBars()
            if (preserveScroll || scrollRestore != null) {
                val nextLeftScroll = leftScroll
                val nextContentScroll = contentScroll
                root.post {
                    if (root.isAttachedToWindow) {
                        nextLeftScroll?.scrollTo(0, previousLeftScroll)
                        nextContentScroll?.scrollTo(0, previousContentScroll)
                    }
                }
            }
            refreshMediaGate(); updateLabels()
        } finally {
            if (preserveMedia) media.endHostReparent()
        }
    }

    private fun performPanelAction() {
        if (tab == 2) goBack() else movePanel(!panelOnRight)
    }

    private fun buildPanelActionButton(): Button {
        val confirming = tab == 2
        val title = if (confirming) getString(R.string.settings_apply) else if (panelOnRight)
            tx("패널을 왼쪽으로", "Move panel left") else tx("패널을 오른쪽으로", "Move panel right")
        return button(title, selected = confirming) { performPanelAction() }.apply {
            contentDescription = if (confirming) getString(R.string.settings_confirm)
                else getString(if (panelOnRight) R.string.panel_move_left else R.string.panel_move_right)
            tooltipText = contentDescription
            textSize = if (confirming) 22f else 18f
        }
    }

    private fun movePanel(toRight: Boolean) {
        if (panelOnRight == toRight) return
        fun screenX(view: View?): Int? = view?.let {
            val position = IntArray(2); it.getLocationOnScreen(position); position[0]
        }
        val previousPanelX = screenX(leftScroll)
        val previousContentX = screenX(contentScroll)
        panelOnRight = toRight
        prefs.edit().putBoolean("panelOnRight", toRight).apply()
        render(preserveScroll = true)
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || leftScroll == null) return
        listOf(leftScroll to previousPanelX, contentScroll to previousContentX).forEach { (view, oldX) ->
            if (view != null && oldX != null) view.post {
                if (view.isAttachedToWindow) {
                    val nextX = screenX(view) ?: oldX
                    view.translationX = (oldX - nextX).toFloat()
                    view.animate().translationX(0f).setDuration(320)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start()
                }
            }
        }
    }

    private fun screenTitle(): String = when {
        active != null -> tx("활동 중", "Activity")
        recovery != null -> tx("활동 이어하기", "Resume activity")
        completed != null -> tx("활동 마무리", "Finish activity")
        tab == 1 -> tx("영상", "Videos")
        tab == 2 -> tx("설정", "Settings")
        tab == 3 -> tx("활동", "Activities")
        else -> tx("대시보드", "Dashboard")
    }

    private fun buildTopBar() = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(4))
        val canGoBack = tab != 0 || screenHistory.isNotEmpty() || active != null || recovery != null || completed != null
        addView(button(tx("뒤로", "Back"), enabled = canGoBack) { goBack() }.apply {
            withIcon(AppIcon.BACK); textSize = 20f
            contentDescription = tx("이전 화면으로", "Go back")
        }, LinearLayout.LayoutParams(-2, dp(56)))
        addView(label(screenTitle(), 25f, true).apply { setPadding(dp(20), 0, dp(12), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(iconButton(tx("설정", "Settings"), AppIcon.SETTINGS) { navigateTo(2) }, LinearLayout.LayoutParams(dp(56), dp(56)))
    }

    private fun buildNavigation() = ResponsiveCardGrid(this, minCardWidthDp = 1, maxCardWidthDp = 720, maxColumns = 1, gapDp = 0, centerContent = true).apply {
        setPadding(dp(16), dp(6), dp(16), dp(8))
        addView(row().apply {
            listOf(Triple(0, tx("대시보드", "Dashboard"), AppIcon.DASHBOARD),
                Triple(1, tx("영상", "Videos"), AppIcon.PLAY),
                Triple(3, tx("활동", "Activities"), AppIcon.LEAF)).forEach { (destination, title, glyph) ->
                addView(button(title, tab == destination) { navigateTo(destination) }.apply {
                    textSize = 20f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    if (resources.configuration.screenWidthDp < 600) {
                        setCompoundDrawablesRelativeWithIntrinsicBounds(null, IconDrawable(glyph,
                            if (tab == destination) palette.onAccent else ink, dp(24)), null, null)
                    } else withIcon(glyph, if (tab == destination) palette.onAccent else ink)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        })
    }

    private fun buildLeft(compact: Boolean): LinearLayout = column().apply {
        setPadding(dp(20), dp(20), dp(20), dp(24))
        background = rounded(palette.surface, 32, palette.border)
        addView(label(tx("빈틈의실", "Binteum"), 24f, true).apply { gravity = Gravity.CENTER })
        if (demoMode) addView(badge(tx("시연 · 모의 충전", "Demo · simulated charging"), palette.warningSurface, palette.warning))
        val gauge = FrameLayout(this@MainActivity)
        chargeGauge = ChargeGaugeView(this@MainActivity).also { gauge.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        val center = column().apply { gravity = Gravity.CENTER }
        socLabel = label("—", 48f, true).apply { gravity = Gravity.CENTER }.also { center.addView(it) }
        center.addView(label(tx("현재 충전량", "Battery level"), 20f).apply { gravity = Gravity.CENTER; setTextColor(muted) })
        gauge.addView(center, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        addView(gauge, LinearLayout.LayoutParams(-1, dp(if (compact) 190 else 220)))
        addView(sectionTitle(tx("목표까지 남은 충전", "Charge time to target"), AppIcon.CLOCK))
        estimateLabel = label("", 34f, true).also { addView(it) }
        estimateSourceLabel = label("", 20f).apply { setTextColor(muted) }.also { addView(it) }
        goalLabel = label("", 21f).also { addView(it) }
        addView(button(tx("충전 목표 선택", "Choose charge target"), enabled = safeToConfigure()) { chooseChargeTarget() })
        addView(label(tx("목표 선택은 앱의 추천 기준을 바꿔요. 차량에도 적용하려면 아래 버튼을 눌러 주세요.",
            "Choosing a goal changes recommendations. Use the button below to apply a supported vehicle limit."), 18f).apply { setTextColor(muted) })
        vehicleTargetButton = button(tx("차량 목표 적용", "Apply vehicle target"), enabled = !demoMode && safeToConfigure()) {
            applyVehicleTarget()
        }.also { addView(it) }
        val departure = departureAtMillis?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }
        addView(button(if (departure == null) tx("출발 시각 설정 · 선택", "Set departure · optional")
            else tx("출발 $departure · 변경", "Depart $departure · change"), enabled = safeToConfigure()) { chooseDeparture() })
        statusLabel = label("", 20f).apply { setTextColor(muted) }.also { addView(it) }
        if (!compact || tab == 2) {
            if (!compact) addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(buildPanelActionButton())
        }
    }

    private fun chooseChargeTarget() {
        if (!safeToConfigure()) return
        val options = listOf(70, 80, 90, 100)
        val names = listOf(tx("차량 목표 따르기", "Use vehicle target")) + options.map { "$it%" }
        chooseSetting(tx("추천에 사용할 충전 목표", "Charge goal for recommendations"), names,
            if (followVehicleTarget) 0 else options.indexOf(target) + 1) { index ->
            if (!safeToConfigure()) return@chooseSetting
            followVehicleTarget = index == 0
            target = if (followVehicleTarget) snapshot?.vehicleTargetPercent ?: target else options[index - 1]
            prefs.edit().putBoolean("followVehicleTarget", followVehicleTarget).putInt("target", target).apply()
            session = ChargingSession(target, targetEnabled = false)
            render(preserveScroll = true)
        }
    }

    private fun applyVehicleTarget() {
        if (demoMode || !safeToConfigure()) return
        val vehicle = source as? PleosVehicleDataSource ?: return
        vehicleTargetButton?.isEnabled = false
        vehicle.supportedChargeTargets { options ->
            if (isFinishing || isDestroyed || source !== vehicle || !resumed) return@supportedChargeTargets
            vehicleTargetButton?.isEnabled = safeToConfigure()
            if (options.isEmpty()) {
                android.widget.Toast.makeText(this, tx("이 차량에서 목표 변경을 사용할 수 없어요. 앱 목표는 유지돼요.",
                    "Vehicle target control is unavailable. Your app goal is kept."), android.widget.Toast.LENGTH_LONG).show()
            } else {
                chooseSetting(tx("차량 충전 목표에 적용", "Apply to vehicle charging limit"), options.map { "$it%" }, options.indexOf(target)) { index ->
                    if (!safeToConfigure() || source !== vehicle) return@chooseSetting
                    vehicleTargetButton?.isEnabled = false
                    vehicle.setChargeTarget(options[index]) { applied ->
                        if (isFinishing || isDestroyed || source !== vehicle) return@setChargeTarget
                        if (applied) {
                            target = options[index]; followVehicleTarget = true
                            prefs.edit().putInt("target", target).putBoolean("followVehicleTarget", true).apply()
                            // Do not pair an old target's ETA with the new limit. Wait for fresh vehicle data.
                            chargePlanner.reset()
                            snapshot = snapshot?.copy(vehicleTargetPercent = target, chargeTimeRemainingSeconds = null, chargeTimeObservedAtMillis = null)
                        }
                        android.widget.Toast.makeText(this, if (applied) tx("차량 목표 적용을 확인했어요", "Vehicle target confirmed")
                            else tx("차량에서 적용을 확인하지 못했어요. 차량 화면을 확인해 주세요.", "Could not confirm the change. Check the vehicle charging screen."), android.widget.Toast.LENGTH_LONG).show()
                        render(preserveScroll = true)
                    }
                }
            }
        }
    }

    private fun chooseDeparture() {
        if (!safeToConfigure()) return
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = departureAtMillis?.takeIf { it > System.currentTimeMillis() } ?: System.currentTimeMillis() + 30 * 60_000 }
        val dialog = android.app.TimePickerDialog(this, { _, hour, minute ->
            if (safeToConfigure()) {
                val chosen = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                departureAtMillis = chosen.timeInMillis
                prefs.edit().putLong("departureAtMillis", chosen.timeInMillis).apply()
                render(preserveScroll = true)
            }
        }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true)
        dialog.setTitle(tx("출발 시각 · 지난 시각은 내일", "Departure · past times mean tomorrow"))
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, tx("해제", "Clear")) { _, _ ->
            if (safeToConfigure()) { departureAtMillis = null; prefs.edit().remove("departureAtMillis").apply(); render(preserveScroll = true) }
        }
        transientDialog = dialog; dialog.show()
    }

    private fun buildRight(): LinearLayout = column().apply {
        setPadding(dp(if (panelOnRight) 16 else 28), dp(24), dp(if (panelOnRight) 28 else 16), dp(28))
        when {
            tab == 2 -> buildSettings(this)
            !allowed() -> buildBlockedState(addReadingColumn(this))
            recovery != null -> {
                addView(label(tx("이어 할 활동이 있어요", "Resume your activity"), 30f, true))
                addView(button(tx("이어서 하기", "Resume"), true) { resumeActivity() })
                addView(button(tx("마치기", "End activity")) { recovery?.let { finishChoice(it, false) }; recovery = null; saveCheckpoint(); render() })
            }
            active != null -> buildActive(addReadingColumn(this), active!!)
            completed != null -> buildFeedback(addReadingColumn(this), completed!!)
            tab == 1 -> buildMedia(this)
            tab == 3 -> buildActivities(this)
            else -> buildRecommendations(this)
        }
    }

    private fun hasEnergyPermission() = checkSelfPermission("android.car.permission.CAR_ENERGY") == PackageManager.PERMISSION_GRANTED

    private fun requestEnergyPermission() {
        if (hasEnergyPermission()) { reconnectVehicle(); return }
        val askedBefore = prefs.getBoolean("askedEnergyPermission", false)
        if (askedBefore && !shouldShowRequestPermissionRationale("android.car.permission.CAR_ENERGY")) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.onFailure { message = getString(com.binteum.app.R.string.ui_text_026); render() }
        } else {
            prefs.edit().putBoolean("askedEnergyPermission", true).apply()
            requestPermissions(arrayOf("android.car.permission.CAR_ENERGY"), 700)
        }
    }

    private fun reconnectVehicle() {
        if (demoMode) return
        media.pauseForHost()
        interruptActivity(getString(com.binteum.app.R.string.ui_text_027))
        recovery = null
        source.stop()
        snapshot = null; status = SessionStatus.WAITING_FOR_DATA
        sourceStartedAt = System.currentTimeMillis()
        render()
        if (started) source.start { value -> runOnUiThread { if (started) acceptSnapshot(value) } }
    }

    private fun buildBlockedState(container: LinearLayout) {
        val parent = surfaceCard()
        parent.addView(sectionTitle(getString(com.binteum.app.R.string.ui_text_028), AppIcon.SHIELD))
        container.addView(parent)
        val missingPermission = !demoMode && !hasEnergyPermission() && status == SessionStatus.WAITING_FOR_DATA
        val heading = when {
            missingPermission -> getString(com.binteum.app.R.string.ui_text_029)
            status == SessionStatus.TARGET_REACHED -> getString(com.binteum.app.R.string.ui_text_030)
            status == SessionStatus.INTERRUPTED -> getString(com.binteum.app.R.string.ui_text_031)
            else -> statusText()
        }
        val description = when {
            missingPermission -> getString(com.binteum.app.R.string.ui_text_032)
            status == SessionStatus.TARGET_REACHED -> getString(R.string.target_session_complete, target)
            status == SessionStatus.INTERRUPTED -> getString(com.binteum.app.R.string.ui_text_033)
            status == SessionStatus.DRIVING_LOCKED -> getString(com.binteum.app.R.string.ui_text_034)
            status == SessionStatus.SUSPENDED -> getString(com.binteum.app.R.string.ui_text_035)
            else -> getString(com.binteum.app.R.string.ui_text_036)
        }
        parent.addView(label(heading, 32f, true))
        parent.addView(label(description, 25f))
        if (missingPermission) parent.addView(button(getString(com.binteum.app.R.string.ui_text_037), true) { requestEnergyPermission() })
        if (!demoMode && status in listOf(SessionStatus.WAITING_FOR_DATA, SessionStatus.INTERRUPTED)) {
            if (!missingPermission) parent.addView(button(getString(com.binteum.app.R.string.ui_text_038)) { reconnectVehicle() })
            val value = snapshot
            fun available(present: Boolean) = getString(if (present) R.string.ui_text_039 else R.string.ui_text_040)
            parent.addView(label(getString(R.string.vehicle_checks, available(value?.stateOfCharge != null),
                available(value?.charging != null), available(value?.uxRestricted != null)), 23f))
        }
        if (BuildConfig.DEBUG && !demoMode && status != SessionStatus.DRIVING_LOCKED && status != SessionStatus.SUSPENDED) {
            parent.addView(button(getString(com.binteum.app.R.string.ui_text_041)) { switchSource() })
        }
        parent.addView(label(getString(com.binteum.app.R.string.ui_text_042), 23f))
    }

    /** Completing an activity and leaving a completed screen are different events. */
    private fun leaveActivityScreen() {
        active?.let { finishChoice(it, false) }
        recovery?.takeIf { it.attemptId != active?.attemptId }?.let { finishChoice(it, false) }
        active = null; recovery = null; completed = null
        message = ""
        saveCheckpoint()
    }

    private fun goBack(): Boolean {
        transientDialog?.takeIf { it.isShowing }?.let { it.dismiss(); return true }
        if (active != null || recovery != null || completed != null) {
            leaveActivityScreen()
            pendingScrollRestore = (leftScroll?.scrollY ?: 0) to activityReturnScroll
            render()
            return true
        }
        if (tab == 1 && media.closeOpenContent()) return true
        val destination = if (screenHistory.isNotEmpty()) screenHistory.removeLast() else if (tab != 0) 0 else return false
        navigateTo(destination, remember = false)
        return true
    }

    private fun navigateTo(destination: Int, remember: Boolean = true) {
        if (tab == destination) return
        screenScrolls[tab] = (leftScroll?.scrollY ?: 0) to (contentScroll?.scrollY ?: 0)
        if (remember) {
            screenHistory.addLast(tab)
            while (screenHistory.size > 16) screenHistory.removeFirst()
        }
        media.pauseForHost()
        leaveActivityScreen()
        pendingVideoEntry = UsageEntryPoint.MANUAL
        tab = destination
        pendingScrollRestore = screenScrolls[destination] ?: (0 to 0)
        render()
    }

    private fun maybeShowEnergyIntroduction() {
        if (demoMode || hasEnergyPermission() || !safeToConfigure() ||
            prefs.getBoolean("energyIntroductionSeen", false) || transientDialog?.isShowing == true) return
        prefs.edit().putBoolean("energyIntroductionSeen", true).apply()
        transientDialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle(getString(com.binteum.app.R.string.ui_text_044))
            .setMessage(getString(com.binteum.app.R.string.ui_text_045))
            .setPositiveButton(getString(com.binteum.app.R.string.ui_text_046)) { _, _ -> if (safeToConfigure()) requestEnergyPermission() }
            .setNegativeButton(getString(com.binteum.app.R.string.ui_text_047)) { _, _ -> render() }
            .show()
    }

    private fun dialogTheme() = if (palette.dark) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert

    private fun category(kind: ActivityKind) = when (kind) {
        ActivityKind.REST -> UsageCategory.REST
        ActivityKind.REFLECTION -> UsageCategory.REFLECTION
        ActivityKind.QUIZ -> UsageCategory.QUIZ
    }
    private fun categoryTitle(category: UsageCategory) = when (category) {
        UsageCategory.VIDEO -> tx("영상", "Videos")
        UsageCategory.REST -> tx("휴식", "Rest")
        UsageCategory.REFLECTION -> tx("생각 정리", "Reflection")
        UsageCategory.QUIZ -> tx("퀴즈", "Quiz")
        UsageCategory.EXTERNAL_MEDIA -> tx("외부 미디어", "External media")
    }
    private fun ownTitle(activity: OwnActivity): String = if (resources.configuration.locales[0].language != "en") activity.title else when (activity.id) {
        "rest_3" -> "3-minute eye break"; "reflection_5" -> "5-minute reflection"; "quiz_8" -> "8-minute quiz"
        "breathing_3" -> "3-minute breathing"; "meditation_3" -> "3-minute mindfulness"; else -> activity.title
    }
    private fun fitDescription(seconds: Int): String {
        val budget = chargeWindow().seconds
        return if (budget == null) tx("남은 충전시간 미확인 · 직접 선택할 수 있어요", "Charge time unavailable · choose freely")
        else if (seconds + 120 > budget) tx("남은 시간보다 길 수 있어요 · 직접 선택 가능", "May exceed the remaining time · you can still choose")
        else tx("마무리 2분을 남기고 할 수 있어요", "Fits with a 2-minute finish buffer")
    }
    private fun choiceCard(title: String, detail: String, glyph: AppIcon, action: () -> Unit) = surfaceCard().apply {
        setPadding(dp(20), dp(20), dp(20), dp(20))
        minimumHeight = dp(210)
        addView(icon(glyph, palette.text, 30).apply {
            background = rounded(palette.surfaceAlt, 16)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(space(10))
        addView(label(title, 25f, true))
        addView(label(detail, 20f).apply { setTextColor(muted) })
        addView(label(tx("열기  →", "Open  →"), 20f, true).apply { setPadding(0, dp(14), 0, 0) })
        isClickable = true; isFocusable = true
        contentDescription = "$title. $detail. ${tx("열기", "Open") }"
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        foreground = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), null, rounded(android.graphics.Color.WHITE, 26))
        setOnClickListener { action() }
    }
    private fun addCardGrid(parent: LinearLayout, minWidth: Int = 280, maxWidth: Int = 380, columns: Int = 3): ResponsiveCardGrid =
        ResponsiveCardGrid(this, minWidth, maxWidth, columns).also {
            parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
        }
    private fun addReadingColumn(parent: LinearLayout, width: Int = 720): LinearLayout = column().also { content ->
        addCardGrid(parent, 280, width, 1).addView(content)
    }
    private fun buildRecommendations(parent: LinearLayout) {
        parent.addView(label(tx("충전하는 동안, 나에게 맞게", "Make this charge time yours"), 32f, true))
        val window = chargeWindow()
        val budget = recommendationSeconds()
        parent.addView(label(sourceTitle(window.source), 21f).apply { setTextColor(muted) })
        parent.addView(label(tx("현재 ${snapshot?.stateOfCharge ?: "—"}% → 목표 $target%", "Now ${snapshot?.stateOfCharge ?: "—"}% → goal $target%"), 24f, true))
        val cards = addCardGrid(parent)
        if (budget == null || budget <= 120) {
            cards.addView(choiceCard(if (budget == null) tx("시간을 확인하고 있어요", "Waiting for a time estimate") else tx("곧 마무리할 시간이에요", "Time to wrap up"),
                if (budget == null) tx("충전량 변화가 쌓이면 예상시간을 계산해요. 아래 탭에서 원하는 활동을 바로 열 수 있어요.",
                    "An estimate appears after enough charging data. Open any activity from the tabs below.")
                else tx("긴 활동 추천은 쉬어갈게요. 영상과 활동은 아래 탭에서 직접 선택할 수 있어요.", "Long activities are not recommended now. You can still choose from the tabs."), AppIcon.CLOCK) { navigateTo(3) })
        } else {
            val feedback = if (learningOn && !demoMode) loadProfile(bucket()).feedback else emptyMap()
            val available = (ActivityCatalog.all + ActivityCatalog.guided).filter { it.durationSeconds + 120 <= budget }
                .sortedByDescending { feedback[it.id]?.preference ?: .5 }
            val videos = VideoCatalog.recommend(budget)
            val categories = (if (videos.isNotEmpty()) listOf(UsageCategory.VIDEO) else emptyList()) + available.map { category(it.kind) }.distinct()
            val ranked = if (learningOn) UsagePreferences.rank(usageRecords, chargeEstimate().seconds, snapshot?.stateOfCharge, categories) else categories
            ranked.take(3).forEach { kind ->
                val count = UsagePreferences.matchingChoices(usageRecords, kind, chargeEstimate().seconds)
                val reason = if (learningOn && count >= 2) tx("비슷한 남은 시간에 ${count}번 선택했어요", "Chosen $count times with similar time remaining")
                    else tx("충전량과 남은 시간에 맞는 활동", "Fits your charge level and remaining time")
                if (kind == UsageCategory.VIDEO) {
                    val sample = videos.first()
                    cards.addView(choiceCard(tx("짧은 영상 · ${sample.seconds / 60}분", "Short video · ${sample.seconds / 60} min"),
                        "${sample.title} · $reason", AppIcon.PLAY) {
                        if (allowed() && recommendationSeconds()?.let { sample.seconds + 120 <= it } == true) {
                            navigateTo(1); pendingVideoEntry = UsageEntryPoint.RECOMMENDED
                            if (!media.selectVideo(sample.id)) pendingVideoEntry = UsageEntryPoint.MANUAL
                        }
                    })
                } else {
                    val activity = available.first { category(it.kind) == kind }
                    cards.addView(choiceCard(ownTitle(activity), reason, activityIcon(activity.kind)) {
                        startActivity(activity, UsageEntryPoint.RECOMMENDED)
                    })
                }
            }
            if (ranked.isEmpty()) parent.addView(label(tx("지금은 짧게 쉬어보세요. 직접 선택은 아래 탭에서 할 수 있어요.", "Take a short break, or choose from the tabs below."), 24f))
        }
        parent.addView(label(tx("선택한 활동 종류·남은 충전시간·충전량을 이 기기에 기록해 다음 추천에 반영해요. 설정에서 확인·삭제할 수 있어요.",
            "Activity choices, remaining time and battery level stay on this device for recommendations. Review or clear them in Settings."), 19f).apply { setTextColor(muted) })
    }

    private fun buildActivities(parent: LinearLayout) {
        parent.addView(label(tx("원하는 활동을 골라보세요", "Choose any activity"), 32f, true))
        val cards = addCardGrid(parent)
        (ActivityCatalog.all + ActivityCatalog.guided).forEach { activity ->
            cards.addView(choiceCard(ownTitle(activity), fitDescription(activity.durationSeconds), activityIcon(activity.kind)) {
                startActivity(activity, UsageEntryPoint.MANUAL)
            })
        }
    }

    private fun buildMedia(parent: LinearLayout) {
        parent.addView(label(tx("보고 싶은 영상을 직접 선택", "Choose a video"), 32f, true))
        parent.addView(media)
        addCardGrid(parent).addView(choiceCard("Netflix", tx("외부 앱 또는 웹으로 열어요. 로그인·멤버십·기기 지원이 필요하며, 외부 재생은 이 앱에서 제어할 수 없어요.",
            "Opens an external app or browser. Login, membership and device support are required; playback is outside this app's control."), AppIcon.PLAY) { openNetflix() })
    }

    private fun recordChoice(kind: UsageCategory, entry: UsageEntryPoint, id: String = UUID.randomUUID().toString()): String {
        val estimate = chargeEstimate()
        val event = UsageEvent(id, System.currentTimeMillis(), kind, entry, estimate.seconds, estimate.source,
            snapshot?.stateOfCharge, target, demoMode)
        usageStore.save(event)
        usageRecords = UsagePreferences.record(usageRecords, event, 4000)
        return id
    }
    private fun finishChoice(run: Run, finished: Boolean) {
        val event = usageRecords.firstOrNull { it.id == run.attemptId } ?: return
        val duration = if (validClock(run)) ((SystemClock.elapsedRealtime() - run.startedElapsed).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
        val updated = event.copy(durationSeconds = duration, completed = finished)
        usageStore.save(updated); usageRecords = UsagePreferences.record(usageRecords, updated, 4000)
    }

    private fun openNetflix() {
        if (!allowed()) return
        recordChoice(UsageCategory.EXTERNAL_MEDIA, UsageEntryPoint.MANUAL)
        media.pauseForHost()
        val uri = Uri.parse("https://www.netflix.com/")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setPackage("com.netflix.mediaclient")
            })
        } catch (_: android.content.ActivityNotFoundException) {
            openNetflixWeb(uri)
        } catch (_: SecurityException) {
            openNetflixWeb(uri)
        }
    }

    private fun openNetflixWeb(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(this, R.string.netflix_unavailable, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(this, R.string.netflix_unavailable, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun startActivity(activity: OwnActivity, entry: UsageEntryPoint = UsageEntryPoint.MANUAL) {
        if (!allowed()) return
        if (entry == UsageEntryPoint.RECOMMENDED && recommendationSeconds()?.let { activity.durationSeconds + 120 <= it } != true) {
            render(preserveScroll = true); return
        }
        media.pauseForHost()
        activityReturnScroll = contentScroll?.scrollY ?: 0
        val attempt = recordChoice(category(activity.kind), entry)
        active = Run(activity.id, attempt, SystemClock.elapsedRealtime(), System.currentTimeMillis(), bucket())
        completed = null; recovery = null; quizIndex = 0; quizAnswer = null
        message = ""
        saveCheckpoint(); render()
    }

    private fun buildActive(container: LinearLayout, run: Run) {
        val parent = surfaceCard()
        container.addView(parent)
        val original = ActivityCatalog.find(run.activityId) ?: return
        val activity = if (resources.configuration.locales[0].language == "en") englishActivity(original) else original
        parent.addView(label(ownTitle(activity), 32f, true))
        parent.addView(label(fitDescription(activity.durationSeconds), 20f).apply { setTextColor(muted) })
        parent.addView(sectionTitle(getString(com.binteum.app.R.string.ui_text_061), AppIcon.CLOCK))
        timerLabel = label(clock(remainingActivitySeconds(run)), 64f, true).also { parent.addView(it) }
        if (activity.kind == ActivityKind.QUIZ && quizIndex < activity.quizQuestions.size) {
            val question = activity.quizQuestions[quizIndex]
            parent.addView(label("${quizIndex + 1} / ${activity.quizQuestions.size}   ${question.prompt}", 28f, true))
            question.choices.forEachIndexed { index, answer -> parent.addView(button(answer, quizAnswer == index) {
                if (allowed() && quizAnswer == null) { quizAnswer = index; render() }
            }) }
            quizAnswer?.let { chosen ->
                parent.addView(label((if (chosen == question.correctAnswerIndex) getString(com.binteum.app.R.string.ui_text_062) else getString(com.binteum.app.R.string.ui_text_063)) + question.explanation, 25f))
                parent.addView(button(getString(com.binteum.app.R.string.ui_text_064), true) { if (allowed()) { quizIndex++; quizAnswer = null; saveCheckpoint(); render() } })
            }
        } else activity.instructions.forEach { parent.addView(label("• $it", 26f)) }
        parent.addView(button(getString(com.binteum.app.R.string.ui_text_065), true) {
            if (allowed()) { active?.let { finishChoice(it, true) }; completed = active; active = null; message = ""; saveCheckpoint(); render() }
        })
        parent.addView(button(getString(com.binteum.app.R.string.ui_text_066)) { interruptActivity(getString(com.binteum.app.R.string.ui_text_067)); render() })
    }

    private fun buildFeedback(container: LinearLayout, run: Run) {
        val parent = surfaceCard()
        parent.addView(sectionTitle(getString(com.binteum.app.R.string.ui_text_068), AppIcon.SPARKLES))
        container.addView(parent)
        parent.addView(label(getString(com.binteum.app.R.string.ui_text_069), 32f, true))
        parent.addView(label(getString(com.binteum.app.R.string.ui_text_070), 24f))
        listOf(getString(com.binteum.app.R.string.ui_text_071) to ActivityRating.LIKE, getString(com.binteum.app.R.string.ui_text_072) to ActivityRating.DISLIKE).forEach { (title, rating) ->
            parent.addView(button(title, rating == ActivityRating.LIKE, learningOn && allowed()) {
                if (allowed() && learningOn) {
                    val result = ActivityFeedbackReducer.apply(loadProfile(run.bucket), OwnActivityFeedback(
                        "rating-${run.attemptId}", run.attemptId, run.activityId, rating, true))
                    if (!demoMode) saveProfile(run.bucket, result.profile)
                    usageRecords.firstOrNull { it.id == run.attemptId }?.let { before ->
                        val rated = before.copy(rating = rating)
                        usageStore.save(rated); usageRecords = UsagePreferences.record(usageRecords, rated, 4000)
                    }
                    completed = null
                    message = if (result.applied) getString(com.binteum.app.R.string.ui_text_073) else getString(com.binteum.app.R.string.ui_text_074)
                    render()
                }
            })
        }
        if (!learningOn) parent.addView(label(getString(com.binteum.app.R.string.ui_text_075), 24f))
        parent.addView(button(getString(com.binteum.app.R.string.ui_text_076)) { completed = null; render() })
    }

    private fun buildSettings(parent: LinearLayout) {
        parent.addView(label(getString(com.binteum.app.R.string.ui_text_014), 34f, true))
        val cards = addCardGrid(parent, 380, 520, 2)
        val display = surfaceCard()
        display.addView(sectionTitle(getString(com.binteum.app.R.string.ui_text_077), AppIcon.SYSTEM))
        val themeNames = listOf(getString(com.binteum.app.R.string.ui_text_078), getString(com.binteum.app.R.string.ui_text_079), getString(com.binteum.app.R.string.ui_text_080))
        display.addView(settingRow(getString(com.binteum.app.R.string.ui_text_081), getString(com.binteum.app.R.string.ui_text_082),
            themeNames[themeMode.ordinal]) {
            chooseSetting(getString(com.binteum.app.R.string.ui_text_081), themeNames, themeMode.ordinal) { changeTheme(ThemeMode.values()[it]) }
        })
        display.addView(settingRow(getString(com.binteum.app.R.string.ui_text_083), getString(com.binteum.app.R.string.ui_text_084),
            if (panelOnRight) getString(com.binteum.app.R.string.ui_text_085) else getString(com.binteum.app.R.string.ui_text_086)) {
            chooseSetting(getString(com.binteum.app.R.string.ui_text_083), listOf(getString(com.binteum.app.R.string.ui_text_086), getString(com.binteum.app.R.string.ui_text_085)), if (panelOnRight) 1 else 0) {
                movePanel(it == 1)
            }
        })
        val sizeLabels = listOf(R.string.text_small, R.string.text_default, R.string.text_large, R.string.text_largest).map { getString(it) }
        val sizes = listOf(.85f, 1f, 1.15f, 1.3f)
        val currentSize = sizes.indexOf(prefs.getFloat("textScale", 1f)).coerceAtLeast(0)
        display.addView(settingRow(getString(R.string.text_size), getString(R.string.text_size_detail), sizeLabels[currentSize]) {
            chooseSetting(getString(R.string.text_size), sizeLabels, currentSize) {
                if (it != currentSize) changeReadingPreference("textScale", scale = sizes[it])
            }
        })
        val languages = listOf("한국어", "English")
        val languageIndex = if (prefs.getString("language", "ko") == "en") 1 else 0
        display.addView(settingRow(getString(R.string.language), getString(R.string.language_detail), languages[languageIndex]) {
            chooseSetting(getString(R.string.language), languages, languageIndex) {
                if (it != languageIndex) changeReadingPreference("language", language = if (it == 1) "en" else "ko")
            }
        })
        cards.addView(display)
        buildHistorySettings(cards)
        val voice = surfaceCard()
        voice.addView(sectionTitle(tx("음성 미리 듣기 · 실험 기능", "Voice preview · experimental"), AppIcon.INFO))
        voice.addView(label(tx("Pleos 음성 서비스의 기기 지원을 확인하는 기능이에요. 충전 완료 알림은 아직 연결하지 않았어요.",
            "Tests device support for Pleos speech. Charging-completion announcements are not connected yet."), 20f))
        voice.addView(settingSwitch(getString(R.string.voice_enabled), tx("음성 미리 듣기 사용", "Enable voice preview"), prefs.getBoolean("completionVoice", true)) {
            prefs.edit().putBoolean("completionVoice", it).apply()
            if (!it) completionVoice.stop()
        })
        voice.addView(settingRow(getString(R.string.voice_test), getString(R.string.voice_test_detail), getString(R.string.voice_play)) {
            if (prefs.getBoolean("completionVoice", true) && safeToConfigure()) { media.pauseForHost(); announceCompletion() }
            else android.widget.Toast.makeText(this, R.string.voice_test_blocked, android.widget.Toast.LENGTH_LONG).show()
        })
        cards.addView(voice)
        val background = surfaceCard()
        background.addView(sectionTitle(getString(com.binteum.app.R.string.ui_text_087), AppIcon.SUN))
        background.addView(settingRow(getString(com.binteum.app.R.string.ui_text_088), getString(com.binteum.app.R.string.ui_text_089), backgroundTitle(backgroundMode)) {
            val modes = BackgroundMode.values()
            chooseSetting(getString(com.binteum.app.R.string.ui_text_088), modes.map { backgroundTitle(it) }, backgroundMode.ordinal) {
                backgroundMode = modes[it]
                prefs.edit().putString("backgroundMode", backgroundMode.name).apply()
                render(preserveScroll = true)
            }
        })
        background.addView(settingSwitch(getString(com.binteum.app.R.string.ui_text_090),
            getString(if (backgroundMode.isFlat) R.string.background_motion_locked else R.string.ui_text_091),
            backgroundMotion && !backgroundMode.isFlat, enabled = !backgroundMode.isFlat) {
            backgroundMotion = it
            prefs.edit().putBoolean("backgroundMotion", it).apply()
            ambientBackground.configure(backgroundMode, palette.dark, backgroundMotion)
        })
        cards.addView(background)
        val connection = surfaceCard()
        connectionCard = connection
        populateConnectionSettings(connection)
        cards.addView(connection)
    }

    private fun buildHistorySettings(parent: ViewGroup) {
        val history = surfaceCard()
        history.addView(sectionTitle(tx("선택 기록과 추천", "Choice history and recommendations"), AppIcon.SPARKLES))
        history.addView(label(tx("활동을 선택할 때마다 남은 충전시간·예측 출처·충전량·목표·직접/추천 선택을 이 기기에 저장해요. 자체 활동은 마침·중단도 기록해요.",
            "Every choice saves remaining charging time, its source, battery level, goal and direct/recommended entry on this device. App-owned activities also record completion or interruption."), 21f))
        history.addView(label(tx("세부 영상 제목·시청 이력은 저장하지 않아요. 시연 기록은 실제 선호도 학습에서 제외해요.",
            "Video titles and viewing history are not stored. Demo choices do not train your preferences."), 19f).apply { setTextColor(muted) })
        history.addView(settingSwitch(tx("기록으로 추천 순서 맞추기", "Personalize recommendation order"),
            tx("선택 기록은 계속 저장되며, 이 설정은 추천에 사용하는지만 바꿔요.", "Choices keep being saved; this controls whether they affect recommendations."), learningOn) {
            learningOn = it; prefs.edit().putBoolean("learningOn", it).apply()
        })
        history.addView(label(tx("저장된 선택 ${usageStore.count()}개 · 최근 기록", "${usageStore.count()} saved choices · recent history"), 24f, true))
        usageRecords.takeLast(5).asReversed().forEach { event ->
            val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(event.atMillis))
            val left = event.remainingSeconds?.let { tx("${(it + 59) / 60}분 남음", "${(it + 59) / 60} min left") } ?: tx("시간 미확인", "Time unknown")
            val entry = if (event.entryPoint == UsageEntryPoint.RECOMMENDED) tx("추천", "Recommended") else tx("직접", "Direct")
            val result = if (event.completed == true) tx(" · 마침", " · completed") else if (event.completed == false) tx(" · 중단", " · interrupted") else ""
            history.addView(label("$whenText · ${categoryTitle(event.category)} · $left · ${event.stateOfCharge?.let { "$it%" } ?: "—"} · $entry$result${if (event.simulation) tx(" · 시연", " · demo") else ""}", 19f))
        }
        history.addView(button(tx("선택 기록 모두 삭제", "Clear choice history")) {
            transientDialog = AlertDialog.Builder(this, dialogTheme()).setTitle(tx("이 기기의 선택 기록을 삭제할까요?", "Clear choices stored on this device?"))
                .setMessage(tx("누적 기록과 활동 평가가 지워져요.", "This clears saved choices and activity ratings."))
                .setNegativeButton(tx("취소", "Cancel"), null)
                .setPositiveButton(tx("삭제", "Clear")) { _, _ ->
                    if (safeToConfigure()) {
                        usageStore.clear(); usageRecords = emptyList()
                        val edit = prefs.edit(); prefs.all.keys.filter { it.startsWith("profile_") }.forEach { edit.remove(it) }; edit.apply()
                        render(preserveScroll = true)
                    }
                }.show()
        })
        parent.addView(history)
    }

    private fun refreshConnectionSettings(animate: Boolean = true) {
        animateConnectionRefresh = animateConnectionRefresh || animate
        handler.removeCallbacks(connectionRefresh)
        handler.postDelayed(connectionRefresh, 60)
    }

    private fun populateConnectionSettings(connection: LinearLayout) {
        diagnosticsLabel = null
        connection.addView(sectionTitle(getString(com.binteum.app.R.string.ui_text_092), AppIcon.SHIELD))
        connection.addView(label(if (demoMode) getString(com.binteum.app.R.string.ui_text_093) else statusText(), 26f, true))
        connection.addView(label(getString(com.binteum.app.R.string.ui_text_094), 22f).apply { setTextColor(muted) })
        if (!demoMode) {
            connection.addView(settingRow(getString(com.binteum.app.R.string.ui_text_095), getString(com.binteum.app.R.string.ui_text_096),
                if (hasEnergyPermission()) getString(com.binteum.app.R.string.ui_text_097) else getString(com.binteum.app.R.string.ui_text_098)) { requestEnergyPermission() })
            connection.addView(settingRow(getString(com.binteum.app.R.string.ui_text_099), getString(com.binteum.app.R.string.ui_text_100), getString(com.binteum.app.R.string.ui_text_101)) { reconnectVehicle() })
        }
        if (BuildConfig.DEBUG) {
            connection.addView(settingSwitch(getString(com.binteum.app.R.string.ui_text_016), getString(com.binteum.app.R.string.ui_text_102), demoMode) { switchSource() })
            connection.addView(settingRow(getString(com.binteum.app.R.string.ui_text_103), getString(com.binteum.app.R.string.ui_text_104), if (developerOptionsExpanded) getString(com.binteum.app.R.string.ui_text_105) else getString(com.binteum.app.R.string.ui_text_106)) {
                developerOptionsExpanded = !developerOptionsExpanded; refreshConnectionSettings()
            })
            if (developerOptionsExpanded) {
                if (!demoMode) diagnosticsLabel = label((source as? PleosVehicleDataSource)?.diagnostics.orEmpty(), 22f).also { connection.addView(it) }
                if (demoMode) {
                    val scenarios = listOf(getString(com.binteum.app.R.string.ui_text_107), getString(com.binteum.app.R.string.ui_text_108), getString(com.binteum.app.R.string.ui_text_109), getString(com.binteum.app.R.string.ui_text_110), getString(com.binteum.app.R.string.ui_text_111))
                    connection.addView(settingRow(getString(com.binteum.app.R.string.ui_text_112), getString(com.binteum.app.R.string.ui_text_113), scenarios[demoScenario]) {
                        chooseSetting(getString(com.binteum.app.R.string.ui_text_112), scenarios, demoScenario) {
                            session = ChargingSession(target, targetEnabled = false)
                            chargePlanner.reset()
                            persistTarget(); demoScenario = it
                            prefs.edit().putInt("demoScenario", it).apply()
                            emitDemoScenario(); refreshConnectionSettings()
                        }
                    })
                }
            }
        }
    }

    private fun chooseSetting(title: String, choices: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        transientDialog?.dismiss()
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val whiteSurface = palette.flat && !palette.dark
        val panel = column().apply {
            setPadding(dp(24), dp(20), dp(24), dp(24))
            background = rounded(if (whiteSurface) 0xE6FFFFFF.toInt() else 0xC9000000.toInt(), 28, palette.border)
        }
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(title, 28f, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(iconButton(getString(com.binteum.app.R.string.ui_text_114), AppIcon.CLOSE) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(64), dp(64)))
        panel.addView(heading)
        panel.addView(space(12))
        choices.forEachIndexed { index, name ->
            val checked = index == selected
            val option = row().apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(68)
                setPadding(dp(20), dp(10), dp(16), dp(10))
                background = rounded(if (whiteSurface) { if (checked) 0x22000000 else 0x09000000 }
                    else if (checked) 0x80000000.toInt() else 0x33000000,
                    18, if (checked) palette.textMuted else palette.border)
                addView(label(name, 24f, checked), LinearLayout.LayoutParams(0, -2, 1f))
                if (checked) addView(icon(AppIcon.CHECK, ink, 26), LinearLayout.LayoutParams(dp(32), dp(32)))
                isClickable = true; isFocusable = true; isSelected = checked
                contentDescription = name
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"; info.isCheckable = true; info.isChecked = checked
                    }
                }
                foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, rounded(android.graphics.Color.WHITE))
                setOnClickListener { dialog.dismiss(); onSelected(index) }
            }
            panel.addView(option, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val scroll = ScrollView(this).apply { addView(panel); isVerticalScrollBarEnabled = false }
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.25f)
        }
        transientDialog = dialog
        dialog.show()
        val maxHeight = (resources.displayMetrics.heightPixels * .82f).toInt()
        val width = minOf(dp(580), resources.displayMetrics.widthPixels - dp(48))
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        dialog.window?.setLayout(width, minOf(panel.measuredHeight, maxHeight))
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            scroll.alpha = 0f; scroll.translationY = dp(12).toFloat()
            scroll.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
    }

    private fun settingContent(title: String, description: String) = column().apply {
        addView(label(title, 26f, true))
        addView(label(description, 22f).apply { setTextColor(muted) })
    }

    private fun settingRow(title: String, description: String, value: String, action: () -> Unit) = column().apply {
        transitionName = "setting-row:$title"
        setPadding(dp(16), dp(12), dp(16), dp(16))
        minimumHeight = dp(88)
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(title, 26f, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label(value, 22f).apply { setTextColor(muted); setPadding(dp(12), 0, dp(8), 0) })
        heading.addView(icon(AppIcon.CHEVRON_RIGHT, muted, 22), LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(heading)
        addView(label(description, 22f).apply { setTextColor(muted) })
        isClickable = true; isFocusable = true
        contentDescription = "$title, $value. $description"
        foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, rounded(android.graphics.Color.WHITE))
        setOnClickListener { action() }
    }

    private fun settingSwitch(title: String, description: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) = row().apply {
        transitionName = "setting-switch:$title"
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        addView(settingContent(title, description), LinearLayout.LayoutParams(0, -2, 1f))
        val control = column().apply { gravity = Gravity.CENTER; alpha = if (enabled) 1f else .45f }
        val state = label(if (checked) getString(com.binteum.app.R.string.ui_text_115) else getString(com.binteum.app.R.string.ui_text_116), 18f).apply { gravity = Gravity.CENTER; setTextColor(muted) }
        control.addView(android.widget.Switch(this@MainActivity).apply {
            isChecked = checked
            isEnabled = enabled
            minHeight = dp(48); switchMinWidth = dp(68); showText = false; splitTrack = false
            contentDescription = "$title. $description"
            thumbTintList = null; trackTintList = null
            thumbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xFFF5F7FA.toInt())
                setSize(dp(28), dp(28)); setStroke(dp(1), 0xFFB6BEC8.toInt())
            }
            trackDrawable = android.graphics.drawable.StateListDrawable().apply {
                fun track(on: Boolean) = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat(); setSize(dp(68), dp(34))
                    setColor(if (on) 0xFF647382.toInt() else 0xFF262B32.toInt())
                    setStroke(dp(2), if (on) 0xFFBDCAD7.toInt() else 0xFF717C89.toInt())
                }
                addState(intArrayOf(android.R.attr.state_checked), track(true))
                addState(intArrayOf(), track(false))
            }
            setOnCheckedChangeListener { _, value ->
                if (!enabled) return@setOnCheckedChangeListener
                state.text = if (value) getString(com.binteum.app.R.string.ui_text_115) else getString(com.binteum.app.R.string.ui_text_116)
                change(value)
            }
        }, LinearLayout.LayoutParams(dp(80), dp(48)))
        control.addView(state)
        addView(control, LinearLayout.LayoutParams(dp(80), -2).apply { marginStart = dp(20) })
    }

    private fun changeTheme(mode: ThemeMode) {
        if (mode == themeMode) return
        themeMode = mode
        prefs.edit().putString("themeMode", mode.name).apply()
        palette = AppTheme.palette(this, mode)
        render(preserveScroll = true)
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (palette.dark) 0 else mask, mask)
        } else {
            window.decorView.systemUiVisibility = if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun switchSource() {
        if (!BuildConfig.DEBUG) return
        media.pauseForHost(); interruptActivity(getString(com.binteum.app.R.string.ui_text_117))
        recovery = null; completed = null; source.stop()
        demoMode = !demoMode; prefs.edit().putBoolean("demoMode", demoMode).apply()
        demoScenario = 0; prefs.edit().putInt("demoScenario", 0).apply()
        chargePlanner.reset()
        session = ChargingSession(target, targetEnabled = false); persistTarget()
        snapshot = null; status = SessionStatus.WAITING_FOR_DATA
        sourceStartedAt = System.currentTimeMillis(); source = makeSource()
        source.start { value -> runOnUiThread { if (started) acceptSnapshot(value) } }
        if (demoMode) emitDemoScenario()
        saveCheckpoint()
        render(preserveScroll = true)
    }

    private fun emitDemoScenario() {
        if (!BuildConfig.DEBUG || !demoMode) return
        val demo = source as? DemoVehicleDataSource ?: return
        when (demoScenario) {
            0 -> demo.emit(65)
            1 -> demo.emit(target, charging = false)
            2 -> demo.emit(65, charging = false)
            3 -> demo.emit(65, uxRestricted = true)
            else -> demo.emit(65, stale = true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 700 && !demoMode) {
            source.stop()
            snapshot = null; status = SessionStatus.WAITING_FOR_DATA
            sourceStartedAt = System.currentTimeMillis()
            message = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                getString(com.binteum.app.R.string.ui_text_118) else getString(com.binteum.app.R.string.ui_text_119)
            render()
            if (started) source.start { value -> runOnUiThread { if (started) acceptSnapshot(value) } }
        }
    }

    private fun interruptActivity(reason: String) {
        active?.let { finishChoice(it, false) }
        active = null; completed = null; message = reason; saveCheckpoint()
    }

    private fun resumeActivity() {
        val run = recovery ?: return
        if (!allowed()) return
        if (!validClock(run) || remainingActivitySeconds(run) <= 0) {
            finishChoice(run, false)
            recovery = null; message = getString(com.binteum.app.R.string.ui_text_120)
        } else { active = run; recovery = null; message = getString(com.binteum.app.R.string.ui_text_121) }
        saveCheckpoint(); render()
    }

    private fun validClock(run: Run): Boolean {
        val monotonic = SystemClock.elapsedRealtime() - run.startedElapsed
        val wall = System.currentTimeMillis() - run.startedWall
        return monotonic >= 0 && wall >= 0 && abs(monotonic - wall) <= 5_000
    }

    private fun saveCheckpoint() {
        val run = active ?: recovery
        val data = JSONObject().put("savedElapsed", SystemClock.elapsedRealtime()).put("savedWall", System.currentTimeMillis())
            .put("quizIndex", quizIndex).put("quizAnswer", quizAnswer ?: -1)
        if (run != null) data.put("run", JSONObject().put("id", run.activityId).put("attempt", run.attemptId)
            .put("elapsed", run.startedElapsed).put("wall", run.startedWall).put("bucket", run.bucket))
        prefs.edit().putString("chargeActivityCheckpoint", data.toString()).apply()
    }
    private fun restoreCheckpoint() {
        try {
            val data = JSONObject(prefs.getString("chargeActivityCheckpoint", "{}") ?: "{}")
            val deltaElapsed = SystemClock.elapsedRealtime() - data.optLong("savedElapsed")
            val deltaWall = System.currentTimeMillis() - data.optLong("savedWall")
            if (deltaElapsed < 0 || deltaWall < 0 || abs(deltaElapsed - deltaWall) > 5_000) return
            quizIndex = data.optInt("quizIndex").coerceAtLeast(0)
            quizAnswer = data.optInt("quizAnswer", -1).takeIf { it >= 0 }
            data.optJSONObject("run")?.let { item ->
                if (ActivityCatalog.find(item.getString("id")) != null) recovery = Run(item.getString("id"),
                    item.getString("attempt"), item.getLong("elapsed"), item.getLong("wall"), item.getString("bucket"))
            }
        } catch (_: Exception) { prefs.edit().remove("chargeActivityCheckpoint").apply() }
    }

    private fun persistTarget() {
        val checkpoint = session.checkpoint()
        prefs.edit().putInt("target", target).putBoolean("targetReached", checkpoint.targetReached)
            .putBoolean("targetNotified", checkpoint.notificationDelivered).apply()
    }

    private fun loadProfile(key: String): ActivityProfile = try {
        val data = JSONObject(prefs.getString("profile_$key", "{}") ?: "{}")
        fun strings(name: String): Set<String> = data.optJSONArray(name)?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        } ?: emptySet()
        val feedback = mutableMapOf<String, FeedbackCounts>()
        data.optJSONObject("counts")?.let { counts -> counts.keys().forEach { id ->
            val count = counts.getJSONObject(id)
            feedback[id] = FeedbackCounts(count.optInt("likes").coerceAtLeast(0), count.optInt("dislikes").coerceAtLeast(0))
        } }
        ActivityProfile(strings("preferred").mapNotNull { value -> ActivityKind.values().find { it.name == value } }.toSet(),
            feedback, strings("events"), strings("attempts"))
    } catch (_: Exception) { ActivityProfile() }

    private fun saveProfile(key: String, profile: ActivityProfile) {
        val counts = JSONObject()
        profile.feedback.forEach { (id, value) -> counts.put(id, JSONObject().put("likes", value.likes).put("dislikes", value.dislikes)) }
        val data = JSONObject().put("preferred", JSONArray(profile.preferredKinds.map { it.name }))
            .put("counts", counts).put("events", JSONArray(profile.processedFeedbackIds.toList()))
            .put("attempts", JSONArray(profile.ratedAttemptIds.toList()))
        prefs.edit().putString("profile_$key", data.toString()).apply()
    }

    private fun updateLabels() {
        val usable = snapshot?.takeIf { System.currentTimeMillis() - it.observedAtMillis in 0..30_000 && it.observedAtMillis >= sourceStartedAt }
        val percent = usable?.stateOfCharge?.takeIf { it in 0..100 }
        socLabel?.text = percent?.let { "$it%" } ?: "—"
        chargeGauge?.update(percent, palette)
        val estimate = chargeEstimate()
        estimateLabel?.text = minutes(estimate.seconds)
        estimateSourceLabel?.text = if (estimate.reason == ChargeEstimateReason.TARGET_DIFFERS &&
            usable?.vehicleTargetPercent?.let { target > it } == true)
            tx("차량 목표가 앱 목표보다 낮아요. 차량 목표를 적용하거나 앱 목표를 낮춰 주세요.",
                "The vehicle limit is below your app goal. Apply a higher vehicle limit or lower your app goal.")
            else sourceTitle(estimate.source)
        goalLabel?.text = if (followVehicleTarget && usable?.vehicleTargetPercent != null)
            tx("차량 목표 $target%", "Vehicle target $target%")
            else tx("앱 목표 $target% · 차량 목표 ${usable?.vehicleTargetPercent?.let { "$it%" } ?: "—"}", "App goal $target% · vehicle target ${usable?.vehicleTargetPercent?.let { "$it%" } ?: "—"}")
        statusLabel?.text = if (!allowed()) statusText() else departureAtMillis?.let {
            val seconds = ((it - System.currentTimeMillis()).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (seconds == 0) tx("설정한 출발 시각이 되었어요", "Your departure time has arrived")
            else tx("출발까지 ${clock(seconds)}", "Departure in ${clock(seconds)}")
        }.orEmpty()
        diagnosticsLabel?.text = (source as? PleosVehicleDataSource)?.diagnostics.orEmpty()
        active?.let { run ->
            val left = remainingActivitySeconds(run)
            timerLabel?.textSize = if (left == 0) 28f else 64f
            timerLabel?.text = if (left == 0) getString(com.binteum.app.R.string.ui_text_128) else clock(left)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun rounded(color: Int, radius: Int = 18, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun surfaceCard() = column().apply {
        setPadding(dp(28), dp(24), dp(28), dp(24))
        background = rounded(palette.surface, 26, palette.border)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(12)) }
    }
    private fun icon(glyph: AppIcon, color: Int = ink, size: Int = 28) = ImageView(this).apply {
        setImageDrawable(IconDrawable(glyph, color, dp(size)))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.CENTER
    }
    private fun sectionTitle(text: String, glyph: AppIcon) = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(glyph, muted, 24), LinearLayout.LayoutParams(dp(30), dp(34)))
        addView(label(text, 23f, true).apply { setTextColor(muted); setPadding(dp(8), dp(6), 0, dp(6)) }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private fun badge(text: String, backgroundColor: Int, foreground: Int) = label(text, 21f, true).apply {
        background = rounded(backgroundColor, 14)
        setTextColor(foreground); setPadding(dp(14), dp(9), dp(14), dp(9))
    }
    private fun activityIcon(kind: ActivityKind) = when (kind) {
        ActivityKind.REST -> AppIcon.LEAF
        ActivityKind.REFLECTION -> AppIcon.SPARKLES
        ActivityKind.QUIZ -> AppIcon.QUIZ
    }
    private fun Button.withIcon(glyph: AppIcon, color: Int = ink) {
        setCompoundDrawablesRelativeWithIntrinsicBounds(IconDrawable(glyph, color, dp(26)), null, null, null)
        compoundDrawablePadding = dp(10)
    }
    private fun iconButton(description: String, glyph: AppIcon, action: () -> Unit) = button("", action = action).apply {
        contentDescription = description; tooltipText = description
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setCompoundDrawablesRelativeWithIntrinsicBounds(IconDrawable(glyph, ink, dp(28)), null, null, null)
    }
    private fun label(value: String, size: Float = 24f, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ink); includeFontPadding = false
        setPadding(0, dp(7), 0, dp(7)); setLineSpacing(dp(3).toFloat(), 1f)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun button(value: String, selected: Boolean = false, enabled: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 24f; isAllCaps = false; minHeight = dp(64); minimumWidth = 0; minWidth = 0; isEnabled = enabled
        includeFontPadding = false; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, if (selected) palette.onAccent else ink)))
        backgroundTintList = null
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(palette.surfaceAlt, 16, palette.border))
            addState(intArrayOf(android.R.attr.state_focused), rounded(if (selected) mint else palette.surfaceAlt, 16, ink).apply { setStroke(dp(3), ink) })
            addState(intArrayOf(), rounded(if (selected) mint else palette.surfaceAlt, 16, if (selected) mint else palette.border))
        }
        background = RippleDrawable(ColorStateList.valueOf(if (palette.dark) 0x26FFFFFF else 0x16000000), states, null)
        stateListAnimator = null; elevation = 0f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
    }
}
