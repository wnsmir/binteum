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
import com.binteum.app.media.VideoProvider
import com.binteum.app.media.VideoProviderIcon
import com.binteum.app.media.VideoLinks
import com.binteum.app.media.VideoLauncher
import com.binteum.app.media.VideoOpenResult
import com.binteum.app.vehicle.PleosVehicleDataSource
import com.binteum.app.vehicle.ComfortController
import com.binteum.app.vehicle.SeatMemoryStatus
import com.binteum.app.nearby.*
import com.binteum.app.music.*
import com.binteum.app.ui.*
import com.binteum.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

class MainActivity : Activity() {
    private companion object {
        val PROCESS_SESSION_TOKEN = UUID.randomUUID().toString()
    }
    override fun attachBaseContext(base: android.content.Context) {
        val preferences = base.getSharedPreferences("binteum_experience", MODE_PRIVATE)
        val config = Configuration(base.resources.configuration)
        val scale = preferences.getFloat("textScale", 1.3f).takeIf { it in .85f..1.3f } ?: 1.3f
        config.fontScale = config.fontScale * scale
        val language = preferences.getString("language", "ko").takeIf { it == "ko" || it == "en" } ?: "ko"
        config.setLocale(java.util.Locale.forLanguageTag(language))
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        outState.putString("videoProvider", videoProvider.name)
        outState.putIntArray("screenHistory", screenHistory.toIntArray())
        outState.putInt("activityReturnScroll", activityReturnScroll)
        outState.putBoolean("targetMode", targetMode)
        outState.putInt("waitMinutes", waitMinutes)
        waitingPlan?.let {
            outState.putString("waitProcessToken", PROCESS_SESSION_TOKEN)
            outState.putLong("waitStarted", it.startedElapsed)
            outState.putInt("waitDuration", it.durationSeconds ?: -1)
            outState.putInt("waitInitialSoc", it.initialSoc ?: -1)
        }
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
        BackgroundMode.SYSTEM -> R.string.sky_system
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
            completionVoice.speak(tx("웨이팅 음성 안내입니다.", "This is a Waiting voice preview."),
                if (resources.configuration.locales[0].language == "en") java.util.Locale.US else java.util.Locale.KOREA)
        }
    }
    private var themeMode = ThemeMode.DARK
    private var backgroundMode = BackgroundMode.SYSTEM
    private var backgroundMotion = true
    private var panelOnRight = false
    private var tocOnRight = true
    private var sectionDock: SectionDockView? = null
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
    private var videoProvider = VideoProvider.YOUTUBE
    private var waitingPlan: WaitingPlan? = null
    private var targetMode = false
    private var waitMinutes = 20
    private var waitDial: ReminderDialView? = null
    private var budgetLabel: TextView? = null
    private val sectionAnchors = mutableMapOf<Int, View>()
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
    private var nearbyFix: VehicleLocationFix? = null
    private var nearbyNote = ""
    private lateinit var locationReader: VehicleLocationReader
    private lateinit var comfort: ComfortController
    private lateinit var nearbyClient: NearbySuggestionsClient
    private var nearbyKind: NearbyKind? = null
    private var nearbyResult: NearbyLookupResult? = null
    private var nearbyLoading = false
    private var nearbyGeneration = 0L
    private var nearbyResumeKind: NearbyKind? = null
    private val nearbyPlanLabels = mutableMapOf<NearbyPlace, TextView>()
    private var musicEntry = UsageEntryPoint.MANUAL
    private val musicPreferences by lazy { MusicPreferences(this) }
    private var nap: NapSession? = null
    private var napChoiceId: String? = null
    private var napEntry = UsageEntryPoint.MANUAL
    private var napLabel: TextView? = null
    private var napStartButton: Button? = null
    private var napRemainingSeconds = 0
    private var nearbyEntry = UsageEntryPoint.MANUAL
    private var napNote = ""
    private var beforeNapBrightness = -1f
    private var sleepReturnScroll: Pair<Int, Int>? = null
    private val napChime by lazy { com.binteum.app.audio.NapChime(this) { resumed && started } }
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
    private var seatMemoryLabel: TextView? = null
    private var seatMemoryChoice: Button? = null
    private var seatMemoryApply: Button? = null
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
            ambientBackground.setActive(nap == null && resumed && safeToConfigure())
            if (demoMode && demoScenario != 4) emitDemoScenario()
            snapshot?.let(::acceptSnapshot)
            if (!safeToConfigure()) completionVoice.stop()
            updateLabels()
            refreshMediaGate()
            tickNap()
            updateNearbyPlans()
            if (nap == null && tab == 0 && allowed() && active == null && recovery == null && completed == null &&
                windowContentKey() != lastWindowContentKey) render(preserveScroll = true)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = canonicalTab(savedInstanceState?.getInt("tab", 0) ?: 0)
        videoProvider = VideoProvider.entries.firstOrNull { it.name == savedInstanceState?.getString("videoProvider") }
            ?: VideoProvider.YOUTUBE
        savedInstanceState?.getIntArray("screenHistory")?.filter { it in 0..6 }?.map(::canonicalTab)
            ?.takeLast(16)?.forEach { if (screenHistory.lastOrNull() != it) screenHistory.addLast(it) }
        while (screenHistory.lastOrNull() == tab) screenHistory.removeLast()
        targetMode = savedInstanceState?.getBoolean("targetMode") ?: false
        waitMinutes = savedInstanceState?.getInt("waitMinutes", 20) ?: 20
        if (savedInstanceState?.containsKey("waitStarted") == true) waitingPlan = WaitingPlan.restore(
            savedInstanceState.getString("waitProcessToken"), PROCESS_SESSION_TOKEN,
            savedInstanceState.getLong("waitStarted"), savedInstanceState.getInt("waitDuration", -1).takeUnless { it == -1 },
            savedInstanceState.getInt("waitInitialSoc", -1).takeUnless { it == -1 }, SystemClock.elapsedRealtime())
        activityReturnScroll = savedInstanceState?.getInt("activityReturnScroll", 0) ?: 0
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "DARK") ?: "DARK") }.getOrDefault(ThemeMode.SYSTEM)
        palette = AppTheme.palette(this, themeMode)
        backgroundMode = runCatching {
            BackgroundMode.valueOf(prefs.getString("backgroundMode", "SYSTEM") ?: "SYSTEM")
        }.getOrDefault(BackgroundMode.SYSTEM)
        backgroundMotion = prefs.getBoolean("backgroundMotion", true)
        panelOnRight = prefs.getBoolean("panelOnRight", false)
        tocOnRight = prefs.getBoolean("tocOnRight", true)
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
        media.setBrowseMode(false)
        media.onContentOpen = { videoProvider == VideoProvider.YOUTUBE && nap == null && tab == 0 && allowed() && active == null && recovery == null }
        media.onVideoSelected = {
            recordChoice(UsageCategory.VIDEO, pendingVideoEntry)
            pendingVideoEntry = UsageEntryPoint.MANUAL
        }
        source = makeSource()
        locationReader = VehicleLocationReader(this)
        nearbyClient = NearbySuggestionsClient()
        comfort = ComfortController(this) { !demoMode && safeToConfigure() && tab == 0 && nap == null }
        // Retired guided activities must not reappear after an app update or process restore.
        prefs.edit().remove("chargeActivityCheckpoint").apply()
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
        comfort.start(inspectClimate = false) {
            if (started && tab == 0 && nap == null) render(preserveScroll = true)
            updateSeatMemoryControls()
        }
        handler.post(ticker)
    }

    override fun onPause() {
        if (nap != null) endNap(false, tx("화면을 벗어나 휴식을 종료했어요. 기상 알림도 중지됐어요.", "Rest ended when this screen was left. The wake cue was stopped."))
        napChime.stop()
        cancelNearbyWork()
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
        if (tab == 0 && !demoMode && nap == null) comfort.refreshSeatMemory()
        resumeNearbyPermission()
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(connectionRefresh)
        active?.let { recovery = it; active = null }
        saveCheckpoint()
        source.stop()
        comfort.stop()
        cancelNearbyWork()
        super.onStop()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!goBack()) super.onBackPressed()
    }

    override fun onDestroy() { nearbyClient.close(); completionVoice.stop(); ambientBackground.release(); media.release(); usageStore.close(); super.onDestroy() }

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
        if (!allowed() && (nearbyLoading || nearbyResult != null)) {
            cancelNearbyWork()
            nearbyNote = tx("차량 상태가 바뀌어 검색을 멈췄어요. 다시 검색해 주세요.", "Vehicle status changed. Search again to refresh nearby places.")
        }
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
        resumeNearbyPermission()
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
    private fun recommendationSeconds(): Int? {
        val available = ChargeWindow.forRecommendations(chargeEstimate(), departureAtMillis, System.currentTimeMillis()).seconds
        return waitingPlan?.budget(available, SystemClock.elapsedRealtime()) ?: available
    }
    private fun refreshMediaGate() {
        media.setBrowseMode(true)
        media.updateRecommendations(recommendationSeconds())
        media.setPlaybackAllowed(videoProvider == VideoProvider.YOUTUBE && nap == null && tab == 0 && allowed() && active == null && recovery == null && completed == null, statusText())
    }
    private fun remainingWindowSeconds(): Int = recommendationSeconds() ?: Int.MAX_VALUE
    private fun windowContentKey(): String = listOf(allowed(), chargeEstimate().source, target,
        UsagePreferences.timeBucket(recommendationSeconds()), recommendationSeconds()?.let { listOf(it >= 420, it >= 1200, it >= 1800) },
        VideoCatalog.recommend(recommendationSeconds()).map { it.id },
        MusicCatalog.recommend(recommendationSeconds()).map { it.name }).joinToString("/")
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
        if (nap != null) { renderSleepMode(); return }
        palette = AppTheme.palette(this, themeMode, backgroundMode)
        handler.removeCallbacks(connectionRefresh)
        animateConnectionRefresh = false
        connectionCard = null
        val scrollRestore = pendingScrollRestore
        pendingScrollRestore = null
        val previousLeftScroll = scrollRestore?.first ?: if (preserveScroll) leftScroll?.scrollY ?: 0 else 0
        val previousContentScroll = scrollRestore?.second ?: if (preserveScroll) contentScroll?.scrollY ?: 0 else 0
        lastWindowContentKey = windowContentKey()
        val preserveMedia = videoProvider == VideoProvider.YOUTUBE && tab == 0 && allowed() && active == null && recovery == null && completed == null
        if (preserveMedia) media.beginHostReparent()
        try {
            (media.parent as? ViewGroup)?.removeView(media)
            media.applyPalette(palette)
            budgetLabel = null; chargeGauge = null; estimateLabel = null; goalLabel = null; timerLabel = null; diagnosticsLabel = null; napLabel = null; nearbyPlanLabels.clear()
            napStartButton = null
            window.statusBarColor = paper
            window.navigationBarColor = palette.surface
            // The same drawable survives UI rebuilds so its motion never restarts on tab/theme changes.
            ambientBackground.configure(backgroundMode, palette.dark, backgroundMotion)
            val root = column().apply {
                background = ambientBackground
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }

            val wide = resources.configuration.screenWidthDp >= 840
            val body = if (wide) row() else column()
            sectionDock = null
            val left = buildLeft(!wide)
            val right = buildRight()
            if (wide) {
                val sideWidth = if (resources.configuration.screenWidthDp >= 1500) 350 else 320
                body.setPadding(dp(16), dp(16), dp(16), dp(16))
                leftScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(left) }
                contentScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(right) }
                val panelParams = LinearLayout.LayoutParams(dp(sideWidth), -1)
                val contentParams = LinearLayout.LayoutParams(0, -1, 1f)
                if (panelOnRight) {
                    body.addView(withSectionDock(contentScroll!!), contentParams)
                    body.addView(leftScroll, panelParams)
                } else {
                    body.addView(leftScroll, panelParams)
                    body.addView(withSectionDock(contentScroll!!), contentParams)
                }
                root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                body.setPadding(dp(16), dp(16), dp(16), dp(16))
                body.addView(left); body.addView(right)
                leftScroll = null
                contentScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
                root.addView(withSectionDock(contentScroll!!), LinearLayout.LayoutParams(-1, 0, 1f))
            }

            setContentView(root)
            ambientBackground.callback = root
            ambientBackground.setActive(nap == null && resumed && safeToConfigure())
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

    private fun movePanel(toRight: Boolean) {
        if (panelOnRight == toRight) return
        fun screenX(view: View?): Int? = view?.let {
            val position = IntArray(2); it.getLocationOnScreen(position); position[0]
        }
        val previousPanelX = screenX(leftScroll)
        fun contentHost(): View? = (contentScroll?.parent as? DockContentLayout) ?: contentScroll
        val previousContentX = screenX(contentHost())
        panelOnRight = toRight
        prefs.edit().putBoolean("panelOnRight", toRight).apply()
        render(preserveScroll = true)
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || leftScroll == null) return
        listOf(leftScroll to previousPanelX, contentHost() to previousContentX).forEach { (view, oldX) ->
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

    private fun withSectionDock(scroll: ScrollView): View {
        if (tab == 2 || sectionAnchors.isEmpty()) return scroll
        val frame = DockContentLayout(this)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val dock = SectionDockView(this, palette, tocOnRight, listOf(
            SectionDockView.Item(1, tx("영상", "Videos"), AppIcon.PLAY),
            SectionDockView.Item(6, tx("음악", "Music"), AppIcon.MUSIC),
            SectionDockView.Item(4, tx("주변 장소", "Nearby"), AppIcon.MAP),
            SectionDockView.Item(5, tx("수면", "Sleep"), AppIcon.BED)), ::jumpToSection)
        sectionDock = dock
        frame.addView(dock, FrameLayout.LayoutParams(-2, -2,
            Gravity.CENTER_VERTICAL or if (tocOnRight) Gravity.RIGHT else Gravity.LEFT))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateSectionDock() }
        frame.post { updateSectionDock() }
        return frame
    }

    private fun sectionTop(view: View): Int {
        val rect = android.graphics.Rect(0, 0, view.width, view.height)
        contentScroll?.offsetDescendantRectToMyCoords(view, rect)
        return rect.top
    }

    private fun updateSectionDock() {
        val scroll = contentScroll ?: return
        val line = scroll.scrollY + scroll.height / 3
        val current = sectionAnchors.entries.lastOrNull { sectionTop(it.value) <= line }?.key ?: sectionAnchors.keys.firstOrNull()
        val atEnd = scroll.scrollY > 0 && !scroll.canScrollVertically(1)
        sectionDock?.setActive(if (atEnd) sectionAnchors.keys.lastOrNull() else current)
    }

    private fun jumpToSection(id: Int) {
        if (tab == 2) { tab = 0; render() }
        contentScroll?.post {
            val anchor = sectionAnchors[id] ?: return@post
            val rect = android.graphics.Rect(0, 0, anchor.width, anchor.height)
            contentScroll?.offsetDescendantRectToMyCoords(anchor, rect)
            contentScroll?.smoothScrollTo(0, rect.top)
        }
    }

    private fun configureWaitDial() {
        waitDial?.configure(if (targetMode) target else waitMinutes, 1, if (targetMode) 100 else 120,
            if (targetMode) "%" else tx("분", " min"), palette, safeToConfigure())
        waitDial?.updateTint(palette.text)
        if (waitingPlan == null) waitDial?.progress(if (targetMode) "$target%" else tx("${waitMinutes}분", "$waitMinutes min"),
            tx("눌러서 설정", "Tap to set"), 0f)
    }

    private fun primaryButton(title: String, action: () -> Unit): Button = button(title, action = action).apply {
        setTextColor(palette.background)
        background = RippleDrawable(ColorStateList.valueOf(palette.border), rounded(palette.text, 18), null)
    }

    private fun showWaitSettings() {
        if (!safeToConfigure()) return
        var draftMode = targetMode
        var draftMinutes = waitMinutes
        var draftTarget = target
        val content = column()
        val dial = ReminderDialView(this)
        fun updateDraft() {
            dial.configure(if (draftMode) draftTarget else draftMinutes, 1, if (draftMode) 100 else 120,
                if (draftMode) "%" else tx("분", " min"), palette, true)
            dial.updateTint(palette.text)
        }
        content.addView(ReminderModeToggle(this, palette, draftMode) { draftMode = it; updateDraft() }, LinearLayout.LayoutParams(-1, dp(72)))
        content.addView(dial, LinearLayout.LayoutParams(-1, dp(340)))
        dial.onValueChanged = { if (draftMode) draftTarget = it else draftMinutes = it }
        updateDraft()
        content.addView(label(tx("목표 잔량은 예상 시간과 추천에만 사용합니다.", "The charge target is used for estimates and recommendations."), 20f))
        content.addView(primaryButton(tx("시작", "Start")) {
            if (!safeToConfigure()) return@primaryButton
            if (nap != null) endNap(false, tx("대기 설정 변경", "Waiting plan changed"))
            targetMode = draftMode; waitMinutes = draftMinutes; target = draftTarget; followVehicleTarget = false
            prefs.edit().putInt("target", target).putBoolean("followVehicleTarget", false).apply()
            waitingPlan = WaitingPlan(SystemClock.elapsedRealtime(), if (targetMode) null else waitMinutes * 60, snapshot?.stateOfCharge)
            transientDialog?.dismiss(); render(preserveScroll = true)
        })
        if (waitingPlan != null) content.addView(button(tx("초기화", "Reset")) {
            if (!safeToConfigure()) return@button
            if (nap != null) endNap(false, tx("대기 종료", "Waiting ended"))
            cancelNearbyWork(); waitingPlan = null
            transientDialog?.dismiss(); render(preserveScroll = true)
        })
        showPanel(tx("대기 설정", "Waiting settings"), content)
    }

    private fun buildLeft(compact: Boolean): LinearLayout = column().apply {
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = rounded(palette.surface, 28, palette.border)
        addView(label(getString(R.string.app_name), 24f, true).apply { gravity = Gravity.CENTER })
        addView(space(16))
        socLabel = label("", 32f, true).apply { gravity = Gravity.CENTER }.also { addView(it) }
        if (demoMode) addView(label(tx("모의 충전", "Simulated charging"), 18f).apply { gravity = Gravity.CENTER; setTextColor(palette.warning) })
        addView(space(20))
        val timer = FrameLayout(this@MainActivity).apply {
            isClickable = true; isFocusable = true
            contentDescription = tx("대기 타이머 설정", "Configure waiting timer")
            setOnClickListener { showWaitSettings() }
        }
        waitDial = ReminderDialView(this@MainActivity).also { dial ->
            timer.addView(dial, FrameLayout.LayoutParams(-1, -1))
            dial.isFocusable = false; dial.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(timer, LinearLayout.LayoutParams(-1, dp(if (compact) 250 else 270)))
        configureWaitDial()
        estimateSourceLabel = label("", 19f).apply { gravity = Gravity.CENTER; setTextColor(muted) }.also { addView(it) }
        addView(View(this@MainActivity).apply { minimumHeight = dp(28) }, LinearLayout.LayoutParams(1, 0, 1f))
        addView(button(if (tab == 2) getString(R.string.settings_apply) else tx("설정", "Settings")) {
            if (tab == 2) goBack() else navigateTo(2)
        }.apply { gravity = Gravity.CENTER })
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
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = departureAtMillis?.takeIf { it > System.currentTimeMillis() } ?: System.currentTimeMillis() + 30 * 60_000
        }
        val panel = column()
        panel.addView(label(tx("위아래로 밀어 시각을 선택하세요. 지난 시각은 내일로 설정됩니다.",
            "Swipe up or down. Earlier times are set for tomorrow."), 22f))
        val wheels = row().apply { gravity = Gravity.CENTER }
        val hour = TimeWheelView(this, 24, calendar.get(java.util.Calendar.HOUR_OF_DAY), palette, tx("시", "Hour"))
        val minute = TimeWheelView(this, 60, calendar.get(java.util.Calendar.MINUTE), palette, tx("분", "Minute"))
        wheels.addView(hour, LinearLayout.LayoutParams(0, dp(240), 1f))
        wheels.addView(label(":", 48f, true))
        wheels.addView(minute, LinearLayout.LayoutParams(0, dp(240), 1f))
        panel.addView(wheels)
        panel.addView(button(tx("적용", "Apply"), true) {
            if (!safeToConfigure()) return@button
            val chosen = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour.value); set(java.util.Calendar.MINUTE, minute.value)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            departureAtMillis = chosen.timeInMillis
            prefs.edit().putLong("departureAtMillis", chosen.timeInMillis).apply()
            transientDialog?.dismiss(); render(preserveScroll = true)
        })
        panel.addView(button(tx("출발 시각 해제", "Clear departure")) {
            departureAtMillis = null; prefs.edit().remove("departureAtMillis").apply()
            transientDialog?.dismiss(); render(preserveScroll = true)
        })
        showPanel(tx("출발 시각", "Departure time"), panel)
    }

    private fun showPanel(title: String, content: View) {
        transientDialog?.dismiss()
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = column().apply {
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = rounded(palette.surface, 28, palette.border)
        }
        panel.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 30f, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(tx("닫기", "Close"), AppIcon.CLOSE) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(64), dp(64)))
        })
        panel.addView(space(20)); panel.addView(content)
        val scroll = ScrollView(this).apply { addView(panel) }
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        transientDialog = dialog; dialog.show()
        val width = minOf(dp(720), resources.displayMetrics.widthPixels - dp(48))
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        dialog.window?.setLayout(width, minOf(panel.measuredHeight, (resources.displayMetrics.heightPixels * .88f).toInt()))
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            panel.alpha = 0f; panel.scaleX = .86f; panel.scaleY = .86f
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun buildRight(): LinearLayout = column().apply {
        setPadding(dp(24), dp(8), dp(24), dp(28))
        sectionAnchors.clear()
        when {
            tab == 2 -> buildSettings(this)
            !allowed() -> buildBlockedState(addReadingColumn(this))
            else -> {
                budgetLabel = label("", 26f, true).also { addView(it) }
                fun section(id: Int, build: (LinearLayout) -> Unit) {
                    val section = column().apply { setPadding(0, dp(40), 0, dp(40)) }
                    if (learningOn) {
                        val categories = when (id) {
                            1 -> listOf(UsageCategory.VIDEO)
                            6 -> listOf(UsageCategory.MUSIC)
                            4 -> listOf(UsageCategory.FOOD, UsageCategory.WALK)
                            else -> listOf(UsageCategory.NAP)
                        }
                        if (categories.sumOf { UsagePreferences.matchingChoices(usageRecords, it, recommendationSeconds()) } >= 2)
                            section.addView(label(tx("이 시간대에 자주 선택", "Often chosen for this wait length"), 18f).apply { setTextColor(muted) })
                    }
                    sectionAnchors[id] = section
                    addView(section, LinearLayout.LayoutParams(-1, -2))
                    build(section)
                    addView(View(this@MainActivity).apply { setBackgroundColor(palette.border) }, LinearLayout.LayoutParams(-1, dp(1)))
                }
                section(1, ::buildMedia)
                section(6, ::buildMusic)
                section(4, ::buildNearby)
                section(5, ::buildNap)
            }
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
        if (nap != null) { endNap(false, tx("휴식을 마쳤어요", "Rest ended")); render(); return true }
        if (active != null || recovery != null || completed != null) {
            leaveActivityScreen()
            pendingScrollRestore = (leftScroll?.scrollY ?: 0) to activityReturnScroll
            render()
            return true
        }
        if (tab == 0 && media.closeOpenContent()) return true
        if (tab == 0 && nearbyKind != null) {
            clearNearbySelection(); return true
        }
        val destination = if (screenHistory.isNotEmpty()) screenHistory.removeLast() else if (tab != 0) 0 else return false
        navigateTo(destination, remember = false)
        return true
    }

    private fun canonicalTab(value: Int): Int = if (value == 2) 2 else 0

    private fun navigateTo(requestedDestination: Int, remember: Boolean = true) {
        if (requestedDestination in listOf(1, 4, 5, 6)) { jumpToSection(requestedDestination); return }
        val destination = canonicalTab(requestedDestination)
        if (tab == destination) return
        if (tab == 0) { cancelNearbyWork(); nearbyResumeKind = null }
        if (nap != null) endNap(false, tx("다른 화면으로 이동해 휴식을 마쳤어요", "Rest ended when you changed screens"))
        napChime.stop()
        screenScrolls[tab] = (leftScroll?.scrollY ?: 0) to (contentScroll?.scrollY ?: 0)
        if (remember) {
            screenHistory.addLast(tab)
            while (screenHistory.size > 16) screenHistory.removeFirst()
        }
        media.pauseForHost()
        leaveActivityScreen()
        pendingVideoEntry = UsageEntryPoint.MANUAL
        napEntry = UsageEntryPoint.MANUAL; nearbyEntry = UsageEntryPoint.MANUAL; musicEntry = UsageEntryPoint.MANUAL
        tab = destination
        pendingScrollRestore = screenScrolls[destination] ?: (0 to 0)
        render()
        if (destination == 5 && !demoMode) comfort.refreshSeatMemory()
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
        UsageCategory.FOOD -> tx("주변 먹거리", "Nearby food")
        UsageCategory.WALK -> tx("산책", "Walk")
        UsageCategory.NAP -> tx("수면", "Sleep")
        UsageCategory.MUSIC -> tx("음악", "Music")
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
        setPadding(dp(28), dp(28), dp(28), dp(28))
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
        ResponsiveCardGrid(this, minWidth, maxWidth, columns, gapDp = 24).also {
            parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(24) })
        }
    private fun addReadingColumn(parent: LinearLayout, width: Int = 720): LinearLayout = column().also { content ->
        addCardGrid(parent, 280, width, 1).addView(content)
    }
    private fun buildMedia(parent: LinearLayout) {
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; tag = "video-header" }
        heading.addView(label(tx("영상", "Videos"), 32f, true), LinearLayout.LayoutParams(-2, -2))
        val actions = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        VideoProvider.entries.forEach { provider ->
            actions.addView(button(tx("${provider.displayName}로 이동 ↗", "Open ${provider.displayName} ↗")) {
                openVideoService(provider)
            }.apply {
                tag = "video-open-${provider.name}"
                textSize = 18f; minHeight = dp(56); minimumHeight = dp(56); setSingleLine(true)
                background = RippleDrawable(ColorStateList.valueOf(palette.border), rounded(palette.surfaceAlt, 16), null)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        // Both service links stay on the title row; a very narrow window can pan the actions.
        heading.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(actions)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        parent.addView(heading, LinearLayout.LayoutParams(-1, -2))

        val selectors = row().apply { tag = "video-selectors" }
        VideoProvider.entries.forEach { provider ->
            val selected = provider == videoProvider
            selectors.addView(button(provider.displayName) { selectVideoProvider(provider) }.apply {
                tag = "video-select-${provider.name}"
                textSize = 22f; isSelected = selected
                contentDescription = provider.displayName + if (selected) tx(" 선택됨", " selected") else ""
                if (Build.VERSION.SDK_INT >= 30) stateDescription = if (selected) tx("선택됨", "Selected") else null
                val brand = VideoProviderIcon.load(this@MainActivity, provider)
                brand.setBounds(0, 0, dp(32), dp(32))
                setCompoundDrawablesRelative(brand, null, null, null); compoundDrawablePadding = dp(12)
                background = RippleDrawable(ColorStateList.valueOf(palette.border),
                    rounded(if (selected) palette.surfaceAlt else palette.surface, 18, if (selected) ink else palette.border), null)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        }
        parent.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(selectors)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(14) })

        if (videoProvider == VideoProvider.YOUTUBE) {
            parent.addView(media)
        } else {
            val content = surfaceCard().apply { tag = "netflix-preview" }
            content.addView(label(tx("Netflix에서 이어 보세요", "Continue watching on Netflix"), 27f, true))
            content.addView(label(tx("위의 ‘Netflix로 이동’을 눌러 앱이나 브라우저에서 작품을 골라보세요. 이 기기에서 열 수 없으면 휴대폰 QR을 제공해요.",
                "Use ‘Open Netflix’ above to choose a title in the app or browser. A phone QR is available if this device cannot open it."), 21f))
            content.addView(label(tx("Netflix 로그인과 멤버십이 필요해요. 재생은 Netflix에서 관리합니다.",
                "Netflix sign-in and membership are required. Netflix manages playback."), 18f).apply { setTextColor(muted) })
            addCardGrid(parent, 280, 720, 1).addView(content)
        }
    }

    private fun selectVideoProvider(provider: VideoProvider) {
        if (!allowed() || videoProvider == provider) return
        media.pauseForHost()
        videoProvider = provider
        render(preserveScroll = true)
    }

    private fun isEnglish() = resources.configuration.locales[0].language == "en"

    private fun buildMusic(parent: LinearLayout) {
        parent.addView(label(tx("음악", "Music"), 32f, true))
        val provider = musicPreferences.provider
        parent.addView(settingRow(tx("음악 서비스", "Music service"),
            musicAvailabilityText(provider), provider.displayName) { chooseMusicService() })
        val recommendations = MusicCatalog.recommend(recommendationSeconds())
        val cards = addCardGrid(parent)
        recommendations.forEach { mood ->
            cards.addView(choiceCard(mood.title(isEnglish()), "${mood.description(isEnglish())}\n${provider.displayName}", AppIcon.MUSIC) {
                openMusic(provider, mood, musicEntry)
            })
        }
        parent.addView(button(tx("다른 분위기 고르기", "Choose another mood")) {
            val moods = MusicCatalog.all
            chooseSetting(tx("듣고 싶은 분위기", "Choose a mood"), moods.map { it.title(isEnglish()) }, -1) {
                openMusic(musicPreferences.provider, moods[it], UsageEntryPoint.MANUAL)
            }
        })
        parent.addView(label(tx("분위기를 추천하고 서비스에서 곡을 골라요. 곡 길이·구독·재생은 서비스에 따라 달라지며, 외부 음악은 충전 종료 시 자동으로 멈추지 않아요.",
            "Choose songs in the service. Track length, membership and playback vary; external music is not stopped automatically when charging ends."), 19f).apply { setTextColor(muted) })
    }

    private fun chooseMusicService() {
        val content = column()
        MusicProvider.entries.forEach { provider ->
            content.addView(button(provider.displayName, provider == musicPreferences.provider) {
                musicPreferences.provider = provider
                render(preserveScroll = true); chooseMusicService()
            }.apply {
                val serviceIcon = MusicProviderIcon.load(this@MainActivity, provider)
                serviceIcon.setBounds(0, 0, dp(40), dp(40))
                setCompoundDrawablesRelative(serviceIcon, null, null, null)
                compoundDrawablePadding = dp(20)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            })
        }
        content.addView(space(16))
        content.addView(label(musicAvailabilityText(musicPreferences.provider), 21f))
        content.addView(button(if (MusicAvailability.route(this, musicPreferences.provider) == MusicRoute.PHONE)
            tx("휴대폰으로 열기 · QR", "Open on phone · QR") else tx("서비스 열기 · 로그인", "Open service · sign in"), true) {
            transientDialog?.dismiss(); openMusic(musicPreferences.provider, null, UsageEntryPoint.MANUAL)
        })
        content.addView(label(tx("로그인과 구독은 해당 서비스에서 관리합니다.", "Sign-in and subscriptions are managed by the service."), 20f))
        showPanel(tx("음악 서비스", "Music service"), content)
    }

    private fun musicAvailabilityText(provider: MusicProvider): String = when (MusicAvailability.route(this, provider)) {
        MusicRoute.APP -> tx("설치된 ${provider.displayName} 앱에서 로그인·재생합니다.", "Sign in and play in the installed ${provider.displayName} app.")
        MusicRoute.BROWSER -> tx("브라우저에서 ${provider.displayName}을 엽니다.", "Open ${provider.displayName} in the browser.")
        MusicRoute.PHONE -> tx("이 기기에 음악 앱·브라우저가 없습니다. QR로 휴대폰에서 열 수 있습니다.", "No music app or browser on this device. Use QR to open it on your phone.")
    }

    private fun openMusic(provider: MusicProvider, mood: MusicMood?, entry: UsageEntryPoint) {
        if (!allowed()) return
        media.pauseForHost(); napChime.stop(); completionVoice.stop()
        val result = MusicLauncher(this) { allowed() }.open(provider, mood)
        if (result.opened) recordChoice(UsageCategory.MUSIC, entry)
        else if (result == MusicOpenResult.NO_HANDLER) {
            recordChoice(UsageCategory.MUSIC, entry)
            showMusicHandoff(provider, mood)
        }
        musicEntry = UsageEntryPoint.MANUAL
    }

    private fun showMusicHandoff(provider: MusicProvider, mood: MusicMood?) {
        if (!allowed()) return
        val uri = mood?.let { MusicLinks.search(provider, it) } ?: MusicLinks.home(provider)
        showServiceHandoff(uri, provider.displayName, tx("음악 서비스 QR", "Music service QR"))
    }

    private fun showServiceHandoff(uri: Uri, title: String, qrDescription: String) {
        if (!allowed()) return
        val content = column().apply { setPadding(dp(24), dp(16), dp(24), dp(16)) }
        content.addView(label(tx("이 차량에는 서비스를 여는 앱·브라우저가 없어요. 휴대폰으로 이어서 열 수 있어요.",
            "No compatible app or browser is installed here. Continue on your phone."), 22f))
        val bitmap = runCatching {
            val bits = com.google.zxing.qrcode.QRCodeWriter().encode(uri.toString(), com.google.zxing.BarcodeFormat.QR_CODE, 480, 480,
                mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8", com.google.zxing.EncodeHintType.MARGIN to 4))
            android.graphics.Bitmap.createBitmap(IntArray(480 * 480) { if (bits[it % 480, it / 480]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },
                480, 480, android.graphics.Bitmap.Config.ARGB_8888)
        }.getOrNull()
        bitmap?.let { content.addView(ImageView(this).apply { setImageBitmap(it); contentDescription = qrDescription },
            LinearLayout.LayoutParams(dp(250), dp(250)).apply { gravity = Gravity.CENTER_HORIZONTAL }) }
        content.addView(label(uri.toString(), 17f))
        transientDialog?.dismiss()
        transientDialog = AlertDialog.Builder(this, dialogTheme()).setTitle(title).setView(content)
            .setPositiveButton(tx("닫기", "Close"), null).show()
    }

    private fun nearbyTitle(kind: NearbyKind) = when (kind) {
        NearbyKind.CAFE -> tx("근처 카페", "Nearby cafes")
        NearbyKind.FOOD -> tx("근처 식사", "Nearby meals")
        NearbyKind.CONVENIENCE -> tx("근처 간식", "Nearby snacks")
        NearbyKind.PARK -> tx("근처 산책", "Nearby walks")
    }

    private fun buildNearby(parent: LinearLayout) {
        parent.addView(label(tx("주변 장소", "Nearby places"), 32f, true))
        if (!locationReader.hasPermission()) {
            parent.addView(settingRow(tx("차량 위치 권한", "Vehicle location permission"),
                tx("주변 검색에 필요한 권한이 꺼져 있습니다.", "Location permission is off; nearby search needs it."),
                tx("허용", "Allow")) { requestNearbyLocationPermission() })
        }
        val kind = nearbyKind
        if (kind == null) {
            parent.addView(label(tx("누르면 차량의 현재 위치로 가까운 장소와 도보 왕복시간을 찾아요.", "Choose a category to find places and round-trip walking times from your vehicle."), 22f))
            val cards = addCardGrid(parent)
            NearbyKind.entries.forEach { category ->
                cards.addView(choiceCard(nearbyTitle(category), tx("현재 위치로 자동 추천", "Automatic suggestions from your location"),
                    if (category == NearbyKind.PARK) AppIcon.LEAF else AppIcon.COFFEE) { requestNearby(category) })
            }
            return
        }
        parent.addView(sectionTitle(nearbyTitle(kind), if (kind == NearbyKind.PARK) AppIcon.LEAF else AppIcon.COFFEE))
        if (nearbyLoading) {
            val loading = surfaceCard()
            loading.addView(android.widget.ProgressBar(this), LinearLayout.LayoutParams(dp(44), dp(44)))
            loading.addView(label(nearbyNote, 24f, true))
            addCardGrid(parent, 280, 600, 1).addView(loading)
            return
        }
        if (nearbyNote.isNotBlank()) parent.addView(label(nearbyNote, 21f).apply { setTextColor(muted) })
        val result = nearbyResult
        if (result != null && result.failure == null) {
            if (result.places.isEmpty()) {
                parent.addView(label(if (result.coverage == NearbyCoverage.OUTSIDE_COVERAGE)
                    tx("현재 위치는 지원 지역 밖이에요. 지금은 대한민국의 주변 장소를 제공해요.", "This location is outside the supported area. Nearby places are currently available in South Korea.")
                    else tx("이 주변에서 확인된 장소가 없어요. 다른 종류를 골라보세요.", "No matching places were found here. Try another category."), 25f, true))
                if (result.coverage == NearbyCoverage.OUTSIDE_COVERAGE &&
                    (Build.HARDWARE in listOf("ranchu", "goldfish") || Build.MODEL.contains("emulator", true))) {
                    parent.addView(label(tx("에뮬레이터에 설정된 차량 위치를 확인해 주세요.", "Check the vehicle location configured in the emulator."), 20f))
                }
            } else {
                parent.addView(label(tx("왕복 도보 + 머무는 시간 + 복귀 여유 5분으로 비교했어요.", "Compared using both walking legs, time at the place, and a 5-minute return buffer."), 20f))
                val cards = addCardGrid(parent)
                result.places.sortedBy { when (it.fitFor(recommendationSeconds())) {
                    NearbyFit.FITS -> 0; NearbyFit.UNKNOWN -> 1; NearbyFit.ROUTE_UNAVAILABLE -> 2; NearbyFit.TOO_LONG -> 3
                } }.forEach { place ->
                    val card = surfaceCard()
                    card.addView(icon(if (kind == NearbyKind.PARK) AppIcon.LEAF else AppIcon.COFFEE, size = 32), LinearLayout.LayoutParams(dp(48), dp(48)))
                    card.addView(label(place.name, 25f, true))
                    if (place.address.isNotBlank()) card.addView(label(place.address, 18f).apply { setTextColor(muted) })
                    val out = place.outboundSeconds; val back = place.returnSeconds
                    card.addView(label(if (out != null && back != null)
                        tx("가는 길 ${(out + 59) / 60}분 · 오는 길 ${(back + 59) / 60}분", "Walk out ${(out + 59) / 60} min · return ${(back + 59) / 60} min")
                        else tx("보행 경로 시간을 확인하지 못했어요", "Walking times are unavailable"), 20f))
                    card.addView(label(tx("${place.staySeconds / 60}분 ${if (kind == NearbyKind.PARK) "산책" else "머무르기"}", "${place.staySeconds / 60} minutes ${if (kind == NearbyKind.PARK) "walking in the park" else "at the place"}"), 20f))
                    nearbyPlanLabels[place] = label(nearbyPlanText(place), 21f, true).also { card.addView(it) }
                    card.addView(button(tx("지도·가는 길", "Map and outward route")) { openNearbyPlace(place, false) })
                    if (place.returnUrl != null) card.addView(button(tx("차량으로 돌아오는 길", "Route back to the vehicle")) { openNearbyPlace(place, true) })
                    cards.addView(card)
                }
                if (Build.HARDWARE in listOf("ranchu", "goldfish") || Build.MODEL.contains("emulator", true)) {
                    parent.addView(label(tx("에뮬레이터에 설정된 차량 위치 기준이에요.", "Based on the vehicle location configured in the emulator."), 18f).apply { setTextColor(muted) })
                }
                parent.addView(label(tx("영업 여부와 현장 보행 여건은 달라질 수 있어요. 휴대폰에 복귀 알람을 설정해 주세요.",
                    "Opening hours and walking conditions can change. Set a return alarm on your phone."), 18f).apply { setTextColor(muted) })
            }
            if (result.attribution.isNotBlank()) parent.addView(label(result.attribution, 16f).apply {
                setTextColor(muted)
                if (result.provider == NearbyProvider.OPENSTREETMAP) {
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    isFocusable = true; isClickable = true
                    setOnClickListener { showMapHandoff(Uri.parse("https://www.openstreetmap.org/copyright"), "OpenStreetMap contributors") }
                }
            })
        }
        val actions = row()
        actions.addView(button(tx("다시 검색", "Search again")) { requestNearby(kind, record = false) }, LinearLayout.LayoutParams(-2, -2))
        actions.addView(button(tx("다른 종류", "Other categories")) { clearNearbySelection() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        parent.addView(actions)
    }

    private fun clearNearbySelection() {
        cancelNearbyWork(); nearbyKind = null; nearbyResumeKind = null; nearbyNote = ""
        render(preserveScroll = true)
        jumpToSection(4)
    }

    private fun requestNearby(kind: NearbyKind, record: Boolean = true) {
        if (!allowed() || tab != 0) return
        cancelNearbyWork()
        nearbyKind = kind
        if (record) {
            recordChoice(if (kind == NearbyKind.PARK) UsageCategory.WALK else UsageCategory.FOOD, nearbyEntry)
            nearbyEntry = UsageEntryPoint.MANUAL
        }
        if (!locationReader.hasPermission()) {
            nearbyResumeKind = kind
            nearbyNote = tx("주변 장소를 찾으려면 차량 위치 권한을 허용해 주세요.", "Allow vehicle location to find nearby places.")
            render(preserveScroll = true)
            requestNearbyLocationPermission()
            return
        }
        nearbyResumeKind = null
        val requestId = nearbyGeneration
        nearbyLoading = true
        nearbyNote = tx("차량의 현재 위치를 확인하고 있어요…", "Getting your vehicle's current location…")
        render(preserveScroll = true)
        locationReader.requestOnce { location ->
            if (!nearbyRequestCurrent(requestId)) return@requestOnce
            val fix = location.fix
            if (fix == null) {
                nearbyLoading = false
                nearbyNote = when (location.failure) {
                    LocationFailure.PERMISSION_REQUIRED -> tx("위치 권한이 필요해요. 다시 검색을 눌러 허용해 주세요.", "Location permission is needed. Search again to allow it.")
                    LocationFailure.UNSUPPORTED -> tx("이 차량에서 위치 서비스를 사용할 수 없어요.", "Vehicle location is unavailable on this device.")
                    else -> tx("현재 차량 위치를 받지 못했어요. 차량 위치 서비스가 켜져 있는지 확인하고 다시 검색해 주세요.", "No current vehicle location. Check that vehicle location is on, then search again.")
                }
                render(preserveScroll = true)
                return@requestOnce
            }
            nearbyFix = fix
            nearbyNote = tx("가까운 장소와 도보 왕복시간을 찾고 있어요…", "Finding nearby places and walking times…")
            render(preserveScroll = true)
            nearbyClient.lookup(fix, kind, recommendationSeconds()) { result ->
                if (!nearbyRequestCurrent(requestId)) return@lookup
                nearbyLoading = false; nearbyResult = result
                nearbyNote = result.failure?.let(::nearbyFailureText) ?: tx("현재 차량 위치에서 가까운 순서로 살펴봤어요.", "Places near your current vehicle location.")
                render(preserveScroll = true)
            }
        }
    }

    private fun requestNearbyLocationPermission() {
        if (!safeToConfigure()) return
        if (locationReader.hasPermission()) { resumeNearbyPermission(); return }
        if (!locationReader.isSupported()) {
            toast(tx("이 기기는 Pleos 위치 서비스를 지원하지 않습니다.", "This device does not support Pleos location."))
            return
        }
        val group = runCatching { packageManager.getPermissionInfo(VehicleLocationReader.PERMISSION, 0).group }.getOrNull()
        if (LocationPermissionPolicy.hasBrokenEmulatorDialog(Build.FINGERPRINT, group)) {
            val body = column()
            body.addView(label(tx("이 Pleos 에뮬레이터의 위치 권한 승인 창이 정상 표시되지 않습니다. 개발 환경에서 위치 권한을 설정한 뒤 다시 검색해 주세요.",
                "This Pleos emulator cannot display the location permission dialog correctly. Set the permission in the development environment, then search again."), 22f))
            body.addView(button(tx("닫기", "Close")) { transientDialog?.dismiss() })
            showPanel(tx("위치 권한 설정 필요", "Location permission required"), body)
            return
        }
        requestPermissions(arrayOf(VehicleLocationReader.PERMISSION), 701)
    }

    private fun nearbyRequestCurrent(requestId: Long) = started && resumed && tab == 0 && allowed() &&
        locationReader.hasPermission() && requestId == nearbyGeneration

    private fun resumeNearbyPermission() {
        val kind = nearbyResumeKind ?: return
        if (!resumed || tab != 0 || !allowed() || !locationReader.hasPermission()) return
        nearbyResumeKind = null
        requestNearby(kind, record = false)
    }

    private fun cancelNearbyWork() {
        if (nearbyLoading || nearbyResult != null) nearbyNote = tx("다시 검색하면 현재 위치부터 확인해요.", "Search again to refresh your current location.")
        nearbyGeneration++
        if (::locationReader.isInitialized) locationReader.cancel()
        if (::nearbyClient.isInitialized) nearbyClient.cancel()
        nearbyLoading = false; nearbyResult = null; nearbyFix = null
        nearbyPlanLabels.clear()
    }

    private fun nearbyFailureText(failure: NearbyFailure): String = when (failure) {
        NearbyFailure.NOT_CONFIGURED -> tx("주변 검색 서비스 연결이 아직 준비되지 않았어요.", "The nearby service is not configured yet.")
        NearbyFailure.LOCATION_STALE -> tx("차량 위치가 오래됐어요. 다시 검색하면 현재 위치부터 확인해요.", "The vehicle location is old. Search again to refresh it.")
        NearbyFailure.RATE_LIMITED -> tx("검색이 몰리고 있어요. 잠시 뒤 다시 검색해 주세요.", "The map service is busy. Try again shortly.")
        NearbyFailure.PROVIDER_AUTH -> tx("지도 서비스 인증을 확인해야 해요.", "Map service authorization needs attention.")
        NearbyFailure.PROVIDER_TIMEOUT -> tx("지도 응답이 늦어지고 있어요. 잠시 뒤 다시 검색해 주세요.", "The map service took too long. Try again shortly.")
        NearbyFailure.NETWORK_ERROR -> tx("주변 검색 서비스에 연결하지 못했어요. 연결을 확인하고 다시 검색해 주세요.", "Could not connect to the nearby service. Check the connection and try again.")
        else -> tx("장소 정보를 확인하지 못했어요. 잠시 뒤 다시 검색해 주세요.", "Could not verify nearby places. Try again shortly.")
    }

    private fun nearbyResultFresh(): Boolean = nearbyFix?.isFresh() == true && nearbyResult?.generatedAtMillis?.let {
        System.currentTimeMillis() - it in -10_000..120_000
    } == true

    private fun nearbyPlanText(place: NearbyPlace): String {
        if (!nearbyResultFresh()) return tx("현재 위치와 시간을 다시 확인해 주세요", "Refresh the location and time estimate")
        return when (place.fitFor(recommendationSeconds())) {
            NearbyFit.FITS -> tx("총 ${(place.totalSeconds!! + 59) / 60}분 · 지금 다녀올 여유가 있어요", "Total ${(place.totalSeconds!! + 59) / 60} min · fits the current time window")
            NearbyFit.TOO_LONG -> tx("총 ${(place.totalSeconds!! + 59) / 60}분 · 남은 시간보다 길어요", "Total ${(place.totalSeconds!! + 59) / 60} min · exceeds your time window")
            NearbyFit.UNKNOWN -> tx("남은 충전시간 미확인 · 복귀 가능 여부 미확정", "Charge time unknown · return fit unconfirmed")
            NearbyFit.ROUTE_UNAVAILABLE -> tx("왕복시간 미확인 · 시간에 맞는 추천에서 제외", "Walking time unknown · not a time-matched recommendation")
        }
    }

    private fun updateNearbyPlans() {
        if ((nearbyLoading || nearbyResult != null) && !locationReader.hasPermission()) {
            cancelNearbyWork()
            nearbyNote = tx("위치 권한을 다시 허용해야 주변 장소를 찾을 수 있어요.", "Allow location again to find nearby places.")
            if (tab == 0) render(preserveScroll = true)
            return
        }
        nearbyPlanLabels.forEach { (place, text) -> text.text = nearbyPlanText(place) }
    }

    private fun openNearbyPlace(place: NearbyPlace, returning: Boolean) {
        if (!allowed() || !nearbyResultFresh()) {
            if (allowed()) toast(tx("다시 검색을 눌러 현재 위치와 시간을 갱신해 주세요.", "Search again to update location and time."))
            return
        }
        showMapHandoff((if (returning) place.returnUrl else place.outboundUrl) ?: place.url,
            if (returning) tx("차량으로 복귀", "Return to vehicle") else place.name)
    }

    private fun showMapHandoff(uri: Uri, title: String) {
        if (!allowed()) return
        val content = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val bitmap = runCatching {
            val size = 640
            val bits = com.google.zxing.qrcode.QRCodeWriter().encode(uri.toString(), com.google.zxing.BarcodeFormat.QR_CODE, size, size,
                mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8", com.google.zxing.EncodeHintType.MARGIN to 4))
            android.graphics.Bitmap.createBitmap(IntArray(size * size) { index -> if (bits[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },
                size, size, android.graphics.Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap != null) content.addView(ImageView(this).apply { setImageBitmap(bitmap); contentDescription = tx("휴대폰 지도 연결 QR", "QR to open the map on your phone") },
            LinearLayout.LayoutParams(dp(280), dp(280)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(label(if (uri.scheme == "nmap") tx("휴대폰 카메라로 스캔하세요. 네이버 지도 앱이 필요해요.", "Scan with your phone. The Naver Map app is required.")
            else tx("휴대폰 카메라로 스캔해 지도를 여세요.", "Scan with your phone to open the map."), 20f))
        content.addView(label(tx("장소·경로 확인용이며 복귀 알람은 전달되지 않아요.", "This shares a map link, not a return alarm."), 18f))
        val dialog = AlertDialog.Builder(this, dialogTheme()).setTitle(title).setView(content)
            .setPositiveButton(tx("차량에서 열기", "Open on this device"), null).setNegativeButton(tx("닫기", "Close"), null).create()
        transientDialog = dialog; dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!allowed()) { dialog.dismiss(); return@setOnClickListener }
            if (NearbyMapLauncher(this) { allowed() }.open(uri)) dialog.dismiss()
            else toast(tx("연결할 지도 앱이 없어요. 휴대폰 QR을 사용해 주세요.", "No compatible map app. Use the phone QR."))
        }
    }

    private fun buildNap(parent: LinearLayout) {
        parent.addView(label(tx("수면", "Sleep"), 32f, true))
        val card = surfaceCard()
        napStartButton = primaryButton(tx("남은 시간 동안 수면", "Sleep for the remaining time")) { startNap() }.also(card::addView)
        card.addView(button(tx("시트 위치 설정", "Seat position")) { showSeatSettings() })
        parent.addView(card)
        updateNapPlan()
    }

    private fun showSeatSettings() {
        val options = column()
        buildSeatSettings(options)
        showPanel(tx("시트 위치 설정", "Seat position"), options)
        if (!demoMode && !comfort.state.busy) comfort.refreshSeatMemory()
    }

    private fun buildSeatSettings(cards: ViewGroup) {
        val seatCard = surfaceCard()
        seatCard.addView(sectionTitle(tx("차량 시트 메모리", "Vehicle seat memory"), AppIcon.BED))
        seatCard.addView(label(tx("앞좌석 왼쪽 · 차량에 저장된 자세", "Front-left seat · positions saved in the vehicle"), 22f, true))
        seatMemoryLabel = label("", 20f).also(seatCard::addView)
        seatMemoryChoice = button("") {
            val slots = comfort.state.memorySlots
            if (slots.isNotEmpty()) chooseSetting(tx("시트 메모리 선택", "Choose seat memory"),
                slots.map { tx("메모리 $it", "Memory $it") }, slots.indexOf(selectedSeatMemory())) { index ->
                // Remember only the chosen existing slot; this does not save or move the seat.
                prefs.edit().putInt("seatMemorySlot.ROW_1_LEFT", slots[index]).apply()
                showSeatSettings()
            }
        }.also(seatCard::addView)
        seatCard.addView(label(tx("선택한 번호를 앱에 기억합니다. 새 자세 저장은 차량에서 해 주세요.",
            "The app remembers this number. Save a new position using the vehicle's controls."), 19f).apply { setTextColor(muted) })
        seatMemoryApply = button("") {
            if (checkSelfPermission("android.car.permission.CAR_SPEED") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("android.car.permission.CAR_SPEED"), 702)
            } else selectedSeatMemory()?.let(comfort::applySeatMemory)
        }.also(seatCard::addView)
        seatCard.addView(label(tx("P단·정지 상태에서 직접 적용합니다. 수면 시작·종료 시 시트는 자동으로 움직이지 않습니다.",
            "Apply explicitly while parked in P. Starting or ending sleep does not move the seat automatically."), 19f).apply { setTextColor(muted) })
        cards.addView(seatCard)
        updateSeatMemoryControls()
    }

    private fun selectedSeatMemory(): Int? {
        val slots = comfort.state.memorySlots
        return prefs.getInt("seatMemorySlot.ROW_1_LEFT", -1).takeIf { it in slots } ?: slots.firstOrNull()
    }

    private fun updateSeatMemoryControls() {
        val state = comfort.state
        val slot = selectedSeatMemory()
        seatMemoryLabel?.text = if (demoMode) tx("모의 충전에서는 시트를 제어하지 않습니다.", "Seat control is unavailable in demo mode.") else when (state.memoryStatus) {
            SeatMemoryStatus.NOT_CHECKED, SeatMemoryStatus.CHECKING -> tx("시트 지원을 확인하고 있습니다.", "Checking seat support.")
            SeatMemoryStatus.READY -> tx("차량에 저장된 메모리를 선택해 적용할 수 있습니다.", "Choose an existing vehicle memory to apply.")
            SeatMemoryStatus.UNAVAILABLE -> tx("이 차량에서 지원되는 메모리를 확인하지 못했습니다.", "No supported seat memories were found on this vehicle.")
            SeatMemoryStatus.PERMISSION_REQUIRED -> tx("정지 상태 확인 권한이 필요합니다. 적용을 눌러 허용한 뒤 다시 적용해 주세요.", "Allow the speed permission, then press Apply again.")
            SeatMemoryStatus.PARK_REQUIRED -> tx("P단과 최신 속도 0을 확인해야 적용할 수 있습니다.", "A fresh zero-speed reading and gear P are required.")
            SeatMemoryStatus.REQUEST_SENT -> tx("적용 요청을 보냈습니다. 시트 이동 완료는 차량에서 확인해 주세요.", "Request sent. Check the vehicle to confirm the seat position.")
            SeatMemoryStatus.FAILED -> tx("적용을 확인할 수 없습니다. 차량 상태를 확인해 주세요.", "The request could not be confirmed. Check the vehicle.")
        }
        seatMemoryChoice?.text = slot?.let { tx("메모리 $it ▾", "Memory $it ▾") } ?: tx("지원 메모리 없음", "No supported memory")
        seatMemoryChoice?.isEnabled = !demoMode && !state.busy && nap == null && slot != null
        seatMemoryApply?.text = slot?.let { tx("메모리 $it 적용", "Apply memory $it") } ?: tx("메모리 적용", "Apply memory")
        seatMemoryApply?.isEnabled = !demoMode && safeToConfigure() && !state.busy && nap == null && slot != null
    }

    private fun startNap() {
        if (waitingPlan == null || !allowed() || nap != null || comfort.state.busy) return
        val next = plannedSleep() ?: return
        media.pauseForHost(); completionVoice.stop(); napChime.stop()
        napChoiceId = recordChoice(UsageCategory.NAP, napEntry); napEntry = UsageEntryPoint.MANUAL
        nap = next; napRemainingSeconds = next.plannedDurationSeconds; napNote = ""
        sleepReturnScroll = (leftScroll?.scrollY ?: 0) to (contentScroll?.scrollY ?: 0)
        cancelNearbyWork()
        beforeNapBrightness = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = .12f }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render(preserveScroll = true)
    }

    private fun tickNap() {
        updateNapPlan()
        val current = nap ?: return
        val progress = current.tick(SystemClock.elapsedRealtime(), if (allowed()) recommendationSeconds() else null)
        napRemainingSeconds = progress.remainingSeconds
        napLabel?.text = clock(progress.remainingSeconds)
        if (progress.finished) {
            val completedNormally = progress.finishReason == NapFinishReason.REQUESTED_DURATION_COMPLETE || progress.finishReason == NapFinishReason.CHARGE_WINDOW_ENDING
            endNap(completedNormally, if (completedNormally) tx("일어날 시간이에요. 차량 상태를 확인해 주세요.", "Time to wake up. Check your vehicle.")
                else tx("충전·차량 상태를 확인할 수 없어 휴식을 마쳤어요.", "Rest ended because charging or vehicle status is unavailable."), progress.elapsedSeconds)
            if (progress.shouldWake) {
                napChime.play()
                toast(napNote)
            }
            render(preserveScroll = true)
        }
    }

    private fun endNap(finished: Boolean, note: String, elapsed: Int? = null) {
        val current = nap ?: return
        val duration = elapsed ?: current.cancel(SystemClock.elapsedRealtime()).elapsedSeconds
        napChoiceId?.let { id -> usageRecords.firstOrNull { it.id == id }?.let { event ->
            val updated = event.copy(durationSeconds = duration, completed = finished)
            usageStore.save(updated); usageRecords = UsagePreferences.record(usageRecords, updated, 4000)
        } }
        nap = null; napChoiceId = null; napNote = note
        pendingScrollRestore = sleepReturnScroll; sleepReturnScroll = null
        napLabel = null
        window.attributes = window.attributes.apply { screenBrightness = beforeNapBrightness }
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun plannedSleep(): NapSession? = if (waitingPlan == null) null else
        NapSession.forRemainingTime(recommendationSeconds(), SystemClock.elapsedRealtime())

    private fun updateNapPlan() {
        if (nap != null) return
        val planned = plannedSleep()
        napStartButton?.isEnabled = planned != null && allowed() && !comfort.state.busy
        napStartButton?.contentDescription = if (planned == null)
            tx("남은 시간 동안 수면. 타이머를 먼저 설정해 주세요.", "Sleep for the remaining time. Set a timer first.")
        else tx("남은 ${clock(planned.plannedDurationSeconds)} 동안 수면", "Sleep for the remaining ${clock(planned.plannedDurationSeconds)}")
    }

    private fun renderSleepMode() {
        lastWindowContentKey = windowContentKey()
        ambientBackground.setActive(false)
        media.pauseForHost()
        napStartButton = null
        val black = android.graphics.Color.BLACK
        val white = android.graphics.Color.WHITE
        window.statusBarColor = black; window.navigationBarColor = black
        val content = FrameLayout(this).apply {
            setBackgroundColor(black)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }
        val controls = column().apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        napLabel = label(clock(napRemainingSeconds), 72f, true).apply {
            setTextColor(white); gravity = Gravity.CENTER
            contentDescription = tx("수면 남은 시간", "Sleep time remaining")
        }.also(controls::addView)
        controls.addView(space(40))
        controls.addView(button(tx("종료", "End sleep")) {
            napChime.stop()
            endNap(false, "")
            render(preserveScroll = true)
        }.apply {
            setTextColor(white)
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), rounded(0xFF181818.toInt(), 18, 0xFF555555.toInt()), null)
            minimumHeight = dp(72)
        }, LinearLayout.LayoutParams(dp(300), dp(80)))
        content.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.insetsController?.setSystemBarsAppearance(0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    private fun recordChoice(kind: UsageCategory, entry: UsageEntryPoint, id: String = UUID.randomUUID().toString()): String {
        val estimate = chargeEstimate()
        val event = UsageEvent(id, System.currentTimeMillis(), kind, entry, estimate.seconds, estimate.source,
            snapshot?.stateOfCharge, target, demoMode, availableSeconds = recommendationSeconds())
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

    private fun openVideoService(provider: VideoProvider) {
        if (!allowed()) return
        media.pauseForHost(); napChime.stop(); completionVoice.stop()
        val result = VideoLauncher(this) { allowed() }.open(provider)
        if (result.opened || result == VideoOpenResult.NO_HANDLER) {
            recordChoice(UsageCategory.EXTERNAL_MEDIA, UsageEntryPoint.MANUAL)
        }
        if (result == VideoOpenResult.NO_HANDLER) {
            showServiceHandoff(VideoLinks.home(provider), provider.displayName,
                tx("${provider.displayName} 연결 QR", "${provider.displayName} link QR"))
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
        val cards = column().also { parent.addView(it, LinearLayout.LayoutParams(-1, -2)) }
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
        display.addView(settingRow(tx("콘텐츠 목차 위치", "Content shortcuts"),
            tx("콘텐츠 옆 아이콘 목차의 위치를 선택합니다.", "Choose which side shows section shortcuts."),
            if (tocOnRight) tx("오른쪽", "Right") else tx("왼쪽", "Left")) {
            chooseSetting(tx("콘텐츠 목차 위치", "Content shortcuts"), listOf(tx("왼쪽", "Left"), tx("오른쪽", "Right")), if (tocOnRight) 1 else 0) {
                tocOnRight = it == 1; prefs.edit().putBoolean("tocOnRight", tocOnRight).apply(); render(preserveScroll = true)
            }
        })
        val sizeLabels = listOf(R.string.text_small, R.string.text_default, R.string.text_large, R.string.text_largest).map { getString(it) }
        val sizes = listOf(.85f, 1f, 1.15f, 1.3f)
        val currentSize = sizes.indexOf(prefs.getFloat("textScale", 1.3f)).coerceAtLeast(0)
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
        val waiting = surfaceCard()
        waiting.addView(sectionTitle(tx("대기와 충전", "Waiting and charging"), AppIcon.CLOCK))
        waiting.addView(settingRow(tx("출발 시각", "Departure"), tx("콘텐츠 추천 시간을 출발 전까지로 제한합니다.", "Limit recommendations to the time before departure."),
            departureAtMillis?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) } ?: tx("설정 안 함", "Not set")) { chooseDeparture() })
        waiting.addView(settingRow(tx("차량 충전 목표", "Vehicle charge limit"), tx("지원 차량에서만 실제 충전 목표를 변경합니다.", "Changes the actual limit only on supported vehicles."), tx("변경", "Change")) { applyVehicleTarget() })
        cards.addView(waiting)
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
        history.addView(label(tx("선택할 때의 사용 가능 시간과 충전 예상시간·출처·충전량·목표를 이 기기에 저장해요. 사용 가능 시간에는 직접 정한 대기시간과 출발 시각도 반영해요.",
            "Each choice saves available time, the separate charge estimate and source, battery level and goal on this device. Available time also reflects your waiting duration and departure time."), 21f))
        history.addView(label(tx("영상·음악의 제목이나 재생 이력은 저장하지 않아요. 음악 서비스 열기는 재생 완료로 기록하지 않아요. 시연 기록은 실제 선호도 학습에서 제외해요.",
            "Video and music titles or playback history are not stored. Opening music does not record playback completion. Demo choices do not train preferences."), 19f).apply { setTextColor(muted) })
        history.addView(settingSwitch(tx("선택 기록으로 선호 표시", "Show preferences from choice history"),
            tx("비슷한 남은 시간에 자주 선택한 기능을 표시해요. 꺼도 선택 기록은 계속 저장해요.", "Highlight features you often choose with similar time remaining. Choices keep being saved when this is off."), learningOn) {
            learningOn = it; prefs.edit().putBoolean("learningOn", it).apply()
        })
        history.addView(label(tx("저장된 선택 ${usageStore.count()}개 · 최근 기록", "${usageStore.count()} saved choices · recent history"), 24f, true))
        usageRecords.takeLast(5).asReversed().forEach { event ->
            val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(event.atMillis))
            val left = event.availableSeconds?.let { tx("${(it + 59) / 60}분 사용 가능", "${(it + 59) / 60} min available") } ?: tx("시간 미확인", "Time unknown")
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
        addView(label(description, 20f).apply { setTextColor(muted); setPadding(0, dp(6), 0, 0) })
    }

    private fun settingRow(title: String, description: String, value: String, action: () -> Unit) = row().apply {
        transitionName = "setting-row:$title"
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        minimumHeight = dp(96)
        addView(settingContent(title, description), LinearLayout.LayoutParams(0, -2, 1f))
        addView(button("$value ▾", action = action).apply {
            textSize = 22f; contentDescription = "$title, $value"
        }, LinearLayout.LayoutParams(dp(220), -2).apply { marginStart = dp(24) })
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
        if (nap != null) endNap(false, tx("데이터 모드가 바뀌어 휴식을 마쳤어요", "Rest ended after changing the data mode"))
        napChime.stop()
        cancelNearbyWork(); nearbyResumeKind = null
        media.pauseForHost(); waitingPlan = null; interruptActivity(getString(com.binteum.app.R.string.ui_text_117))
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
        if (requestCode == 701) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) resumeNearbyPermission()
            else {
                nearbyResumeKind = null; nearbyLoading = false
                nearbyNote = tx("Pleos 위치 권한이 허용되지 않았습니다. 승인 창이 표시되지 않으면 이 에뮬레이터의 권한 처리를 확인해야 합니다.", "Pleos location permission was not granted. If no approval dialog appears, this emulator needs permission troubleshooting.")
                render(preserveScroll = true)
            }
        }
        if (requestCode == 702 && !demoMode) comfort.refreshSeatMemory()
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
        // Keep old saved guided sessions retired without deleting the user's choice history.
        if (prefs.contains("chargeActivityCheckpoint")) prefs.edit().remove("chargeActivityCheckpoint").apply()
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
        socLabel?.text = tx("배터리 ", "Battery ") + (percent?.let { "$it%" } ?: "—")
        chargeGauge?.update(percent, palette)
        val estimate = chargeEstimate()
        estimateLabel?.text = minutes(estimate.seconds)
        val plan = waitingPlan
        if (plan != null) {
            val seconds = plan.remaining(SystemClock.elapsedRealtime(), estimate.seconds)
            val subtitle = if (plan.durationSeconds != null) tx("남은 시간", "Remaining") else tx("충전 예상 시간", "Charge estimate")
            waitDial?.progress(seconds?.let(::clock) ?: "—", if (seconds == 0) tx("완료", "Complete") else subtitle,
                plan.progress(SystemClock.elapsedRealtime(), percent, target))
            estimateSourceLabel?.text = if (plan.durationSeconds != null) tx("${waitMinutes}분 대기", "$waitMinutes-minute wait") else "$target% · ${sourceTitle(estimate.source)}"
        }
        estimateSourceLabel?.visibility = if (plan == null) View.GONE else View.VISIBLE
        budgetLabel?.text = if (waitingPlan == null) tx("콘텐츠 둘러보기 · 타이머로 대기 설정", "Browse content · set your wait with the timer") else recommendationSeconds()?.let { tx("남은 ${clock(it)}에 맞춘 콘텐츠", "Content for your remaining ${clock(it)}") }
            ?: tx("충전 예상 시간 확인 중 · 콘텐츠를 직접 선택하세요.", "Waiting for an estimate · browse content below.")
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
        setPadding(dp(28), dp(28), dp(28), dp(28))
        background = rounded(palette.surface, 26, palette.border)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, dp(24)) }
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
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, dp(10)) }
    }
}
