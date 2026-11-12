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
import com.binteum.app.vehicle.ComfortController
import com.binteum.app.nearby.*
import com.binteum.app.music.*
import com.binteum.app.audio.NapSoundPlayer
import com.binteum.app.audio.NapSoundStatus
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
    private var napDurationMinutes = 15
    private var napWakeReserveMinutes = 3
    private var napBrightnessPercent = 12
    private var napSound = NapSound.OFF
    private var napSoundVolume = 20
    private var napWakeLabel: TextView? = null
    private var napSoundLabel: TextView? = null
    private var napAfterTemperature = false
    private var napTemperatureRequest = 0L
    private var musicEntry = UsageEntryPoint.MANUAL
    private val musicPreferences by lazy { MusicPreferences(this) }
    private val napSoundPlayer: NapSoundPlayer by lazy {
        NapSoundPlayer(this, { resumed && started && tab == 5 && allowed() }) {
            napSoundLabel?.text = napSoundStatusText()
        }
    }
    private var nap: NapSession? = null
    private var napChoiceId: String? = null
    private var napEntry = UsageEntryPoint.MANUAL
    private var napLabel: TextView? = null
    private var napPlanLabel: TextView? = null
    private var napStartButton: Button? = null
    private var napRemainingSeconds = 0
    private var nearbyEntry = UsageEntryPoint.MANUAL
    private var napNote = ""
    private var beforeNapBrightness = -1f
    private var selectedTemperature = 22f
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
            cancelUnsafeNapTemperatureStart()
            if (!safeToConfigure()) completionVoice.stop()
            updateLabels()
            refreshMediaGate()
            tickNap()
            updateNearbyPlans()
            if (tab == 0 && allowed() && active == null && recovery == null && completed == null &&
                windowContentKey() != lastWindowContentKey) render(preserveScroll = true)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab", 0) ?: 0
        savedInstanceState?.getIntArray("screenHistory")?.filter { it in 0..6 }?.takeLast(16)?.forEach(screenHistory::addLast)
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
        napDurationMinutes = prefs.getInt("napDurationMinutes", 15).takeIf { it in listOf(5, 10, 15, 20, 30) } ?: 15
        napWakeReserveMinutes = prefs.getInt("napWakeReserveMinutes", 3).takeIf { it in listOf(2, 3, 5) } ?: 3
        napBrightnessPercent = prefs.getInt("napBrightnessPercent", 12).takeIf { it in listOf(5, 12, 25) } ?: 12
        napSound = runCatching { NapSound.valueOf(prefs.getString("napSound", "OFF") ?: "OFF") }.getOrDefault(NapSound.OFF)
        napSoundVolume = prefs.getInt("napSoundVolume", 20).takeIf { it in listOf(0, 10, 20, 35) } ?: 20
        selectedTemperature = prefs.getFloat("napTemperature", 22f).takeIf { it.isFinite() && it in 20f..24f } ?: 22f
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
        locationReader = VehicleLocationReader(this)
        nearbyClient = NearbySuggestionsClient()
        comfort = ComfortController(this) { !demoMode && safeToConfigure() && tab == 5 && nap == null }
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
        comfort.start { state ->
            if (napAfterTemperature && !state.busy) {
                napAfterTemperature = false; napTemperatureRequest++
                if (state.status == com.binteum.app.vehicle.ComfortStatus.APPLIED && allowed() && tab == 5 && !demoMode) startNap()
                else napNote = tx("온도 적용을 확인하지 못해 낮잠을 시작하지 않았어요. 소리·화면만으로도 시작할 수 있어요.",
                    "The temperature change was not verified, so the nap did not start. You can still start with sound and dimming only.")
            }
            if (started && tab == 5) render(preserveScroll = true)
        }
        handler.post(ticker)
    }

    override fun onPause() {
        cancelNapTemperatureStart()
        if (nap != null) endNap(false, tx("화면을 벗어나 휴식을 종료했어요. 기상 알림도 중지됐어요.", "Rest ended when this screen was left. The wake cue was stopped."))
        napChime.stop()
        napSoundPlayer.stop()
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
        if (tab == 5 && !demoMode && nap == null) comfort.refresh()
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

    override fun onDestroy() { napSoundPlayer.close(); nearbyClient.close(); completionVoice.stop(); ambientBackground.release(); media.release(); usageStore.close(); super.onDestroy() }

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
        cancelUnsafeNapTemperatureStart()
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
            timerLabel = null; diagnosticsLabel = null; napLabel = null; nearbyPlanLabels.clear()
            napPlanLabel = null; napStartButton = null; napWakeLabel = null; napSoundLabel = null
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
        tab == 4 -> nearbyKind?.let(::nearbyTitle) ?: tx("주변", "Nearby")
        tab == 5 -> tx("낮잠", "Nap")
        tab == 6 -> tx("음악", "Music")
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

    private fun buildNavigation() = ResponsiveCardGrid(this, minCardWidthDp = 1, maxCardWidthDp = 1000, maxColumns = 1, gapDp = 0, centerContent = true).apply {
        setPadding(dp(16), dp(6), dp(16), dp(8))
        addView(row().apply {
            listOf(Triple(0, tx("대시보드", "Dashboard"), AppIcon.DASHBOARD),
                Triple(1, tx("영상", "Videos"), AppIcon.PLAY),
                Triple(6, tx("음악", "Music"), AppIcon.MUSIC),
                Triple(3, tx("활동", "Activities"), AppIcon.LEAF),
                Triple(4, tx("주변", "Nearby"), AppIcon.MAP),
                Triple(5, tx("낮잠", "Nap"), AppIcon.BED)).forEach { (destination, title, glyph) ->
                addView(button(title, tab == destination) { navigateTo(destination) }.apply {
                    textSize = 18f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    if (resources.configuration.screenWidthDp < 1100) {
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
            tab == 5 && safeToConfigure() -> buildNap(this)
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
            tab == 4 -> buildNearby(this)
            tab == 5 -> buildNap(this)
            tab == 6 -> buildMusic(this)
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
        if (nap != null) { endNap(false, tx("휴식을 마쳤어요", "Rest ended")); render(); return true }
        if (active != null || recovery != null || completed != null) {
            leaveActivityScreen()
            pendingScrollRestore = (leftScroll?.scrollY ?: 0) to activityReturnScroll
            render()
            return true
        }
        if (tab == 4 && nearbyKind != null) {
            cancelNearbyWork(); nearbyKind = null; nearbyResumeKind = null; nearbyNote = ""; render(); return true
        }
        if (tab == 1 && media.closeOpenContent()) return true
        val destination = if (screenHistory.isNotEmpty()) screenHistory.removeLast() else if (tab != 0) 0 else return false
        navigateTo(destination, remember = false)
        return true
    }

    private fun navigateTo(destination: Int, remember: Boolean = true) {
        if (tab == destination) return
        cancelNapTemperatureStart()
        if (tab == 4) { cancelNearbyWork(); nearbyResumeKind = null }
        if (nap != null) endNap(false, tx("다른 화면으로 이동해 휴식을 마쳤어요", "Rest ended when you changed screens"))
        napChime.stop()
        napSoundPlayer.stop()
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
        if (destination == 5 && !demoMode) comfort.refresh()
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
        UsageCategory.NAP -> tx("낮잠", "Nap")
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
            val categories = listOf(UsageCategory.MUSIC) + (if (videos.isNotEmpty()) listOf(UsageCategory.VIDEO) else emptyList()) + available.map { category(it.kind) }.distinct() +
                (if (budget >= 420) listOf(UsageCategory.NAP) else emptyList()) +
                (if (budget >= 1200) listOf(UsageCategory.FOOD, UsageCategory.WALK) else emptyList())
            val defaultOrder = when {
                budget >= 1800 -> listOf(UsageCategory.WALK, UsageCategory.FOOD, UsageCategory.MUSIC, UsageCategory.NAP, UsageCategory.VIDEO)
                budget >= 900 -> listOf(UsageCategory.MUSIC, UsageCategory.NAP, UsageCategory.VIDEO, UsageCategory.FOOD, UsageCategory.WALK)
                else -> listOf(UsageCategory.MUSIC, UsageCategory.VIDEO, UsageCategory.REST)
            }
            val ordered = categories.sortedBy { defaultOrder.indexOf(it).takeIf { index -> index >= 0 } ?: defaultOrder.size }
            val ranked = if (learningOn) UsagePreferences.rank(usageRecords, chargeEstimate().seconds, snapshot?.stateOfCharge, ordered) else ordered
            ranked.take(3).forEach { kind ->
                val count = UsagePreferences.matchingChoices(usageRecords, kind, chargeEstimate().seconds)
                val reason = if (learningOn && count >= 2) tx("비슷한 남은 시간에 ${count}번 선택했어요", "Chosen $count times with similar time remaining")
                    else tx("충전량과 남은 시간에 맞는 활동", "Fits your charge level and remaining time")
                if (kind == UsageCategory.MUSIC) {
                    val mood = MusicCatalog.recommend(budget).first()
                    cards.addView(choiceCard(tx("음악과 함께 쉬기", "Rest with music"),
                        "${mood.title(isEnglish())} · $reason", AppIcon.MUSIC) {
                        navigateTo(6); musicEntry = UsageEntryPoint.RECOMMENDED
                    })
                } else if (kind == UsageCategory.VIDEO) {
                    val sample = videos.first()
                    cards.addView(choiceCard(tx("짧은 영상 · ${sample.seconds / 60}분", "Short video · ${sample.seconds / 60} min"),
                        "${sample.title} · $reason", AppIcon.PLAY) {
                        if (allowed() && recommendationSeconds()?.let { sample.seconds + 120 <= it } == true) {
                            navigateTo(1); pendingVideoEntry = UsageEntryPoint.RECOMMENDED
                            if (!media.selectVideo(sample.id)) pendingVideoEntry = UsageEntryPoint.MANUAL
                        }
                    })
                } else if (kind == UsageCategory.NAP) {
                    cards.addView(choiceCard(tx("잠깐 낮잠", "A short nap"), tx("충전 여유에 맞춰 기상 · $reason", "Wake within your charging window · $reason"), AppIcon.BED) {
                        navigateTo(5); napEntry = UsageEntryPoint.RECOMMENDED
                    })
                } else if (kind == UsageCategory.FOOD || kind == UsageCategory.WALK) {
                    cards.addView(choiceCard(categoryTitle(kind), tx("차량 현재 위치로 장소와 왕복시간을 바로 찾아요", "Find places and round-trip times from your vehicle"), if (kind == UsageCategory.WALK) AppIcon.LEAF else AppIcon.COFFEE) {
                        navigateTo(4); nearbyEntry = UsageEntryPoint.RECOMMENDED
                        requestNearby(if (kind == UsageCategory.WALK) NearbyKind.PARK else if (budget < 1800) NearbyKind.CONVENIENCE else NearbyKind.CAFE)
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

    private fun isEnglish() = resources.configuration.locales[0].language == "en"

    private fun buildMusic(parent: LinearLayout) {
        parent.addView(label(tx("이 틈에, 좋아하는 음악", "Your music, your moment"), 32f, true))
        parent.addView(label(MusicCatalog.windowLabel(recommendationSeconds(), isEnglish()), 23f).apply { setTextColor(muted) })
        val provider = musicPreferences.provider
        val services = addCardGrid(parent, 220, 340, 3)
        MusicProvider.entries.forEach { item ->
            val card = surfaceCard()
            card.addView(sectionTitle(item.displayName, AppIcon.MUSIC))
            card.addView(button(if (item == provider) tx("기본 서비스 ✓", "Default service ✓") else tx("기본으로 선택", "Use by default"), item == provider) {
                musicPreferences.provider = item; render(preserveScroll = true)
            })
            card.addView(button(tx("서비스 열기 · 로그인", "Open service · sign in")) { openMusic(item, null, UsageEntryPoint.MANUAL) })
            services.addView(card)
        }
        parent.addView(label(tx("처음에는 공식 앱·브라우저에서 로그인하세요. 다음에도 같은 서비스의 로그인 상태를 사용하며, 만료되면 다시 로그인할 수 있어요.",
            "Sign in through the official app or browser. Its session is reused and may need renewal when it expires."), 19f).apply { setTextColor(muted) })
        parent.addView(sectionTitle(tx("남은 여유에 어울리는 음악", "Music for this charging break"), AppIcon.SPARKLES))
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

    private fun openMusic(provider: MusicProvider, mood: MusicMood?, entry: UsageEntryPoint) {
        if (!allowed()) return
        media.pauseForHost(); napChime.stop(); napSoundPlayer.stop(); completionVoice.stop()
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
        val content = column().apply { setPadding(dp(24), dp(16), dp(24), dp(16)) }
        content.addView(label(tx("이 차량에는 서비스를 여는 앱·브라우저가 없어요. 휴대폰으로 이어서 열 수 있어요.",
            "No compatible app or browser is installed here. Continue on your phone."), 22f))
        val bitmap = runCatching {
            val bits = com.google.zxing.qrcode.QRCodeWriter().encode(uri.toString(), com.google.zxing.BarcodeFormat.QR_CODE, 480, 480,
                mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8", com.google.zxing.EncodeHintType.MARGIN to 4))
            android.graphics.Bitmap.createBitmap(IntArray(480 * 480) { if (bits[it % 480, it / 480]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },
                480, 480, android.graphics.Bitmap.Config.ARGB_8888)
        }.getOrNull()
        bitmap?.let { content.addView(ImageView(this).apply { setImageBitmap(it); contentDescription = tx("음악 서비스 QR", "Music service QR") },
            LinearLayout.LayoutParams(dp(250), dp(250)).apply { gravity = Gravity.CENTER_HORIZONTAL }) }
        content.addView(label(uri.toString(), 17f))
        transientDialog = AlertDialog.Builder(this, dialogTheme()).setTitle(provider.displayName).setView(content)
            .setPositiveButton(tx("닫기", "Close"), null).show()
    }

    private fun nearbyTitle(kind: NearbyKind) = when (kind) {
        NearbyKind.CAFE -> tx("근처 카페", "Nearby cafes")
        NearbyKind.FOOD -> tx("근처 식사", "Nearby meals")
        NearbyKind.CONVENIENCE -> tx("근처 간식", "Nearby snacks")
        NearbyKind.PARK -> tx("근처 산책", "Nearby walks")
    }

    private fun buildNearby(parent: LinearLayout) {
        parent.addView(label(tx("지금 있는 곳에서, 잠깐", "A short break, right nearby"), 32f, true))
        val kind = nearbyKind
        if (kind == null) {
            parent.addView(label(tx("누르면 차량의 현재 위치로 가까운 장소와 도보 왕복시간을 찾아요.", "Choose a category to find places and round-trip walking times from your vehicle."), 22f))
            val cards = addCardGrid(parent)
            NearbyKind.entries.forEach { category ->
                cards.addView(choiceCard(nearbyTitle(category), tx("현재 위치로 자동 추천", "Automatic suggestions from your location"),
                    if (category == NearbyKind.PARK) AppIcon.LEAF else AppIcon.COFFEE) { requestNearby(category) })
            }
            parent.addView(label(tx("선택하면 현재 좌표를 지도 서비스에 보내요. 위치와 검색 결과는 이력에 저장하지 않아요.",
                "Choosing a category sends your current coordinates to the map service. Locations and search results are not saved in your history."), 18f).apply { setTextColor(muted) })
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
        actions.addView(button(tx("다른 종류", "Other categories")) { cancelNearbyWork(); nearbyKind = null; nearbyResumeKind = null; nearbyNote = ""; render() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        parent.addView(actions)
    }

    private fun requestNearby(kind: NearbyKind, record: Boolean = true) {
        if (!allowed() || tab != 4) return
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
            val asked = prefs.getBoolean("askedNearbyLocation", false)
            prefs.edit().putBoolean("askedNearbyLocation", true).apply()
            if (asked && !shouldShowRequestPermissionRationale(VehicleLocationReader.PERMISSION)) {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                    .onFailure { nearbyResumeKind = null; toast(tx("앱 설정에서 위치 권한을 허용해 주세요.", "Allow location in the app settings.")) }
            } else requestPermissions(arrayOf(VehicleLocationReader.PERMISSION), 701)
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

    private fun nearbyRequestCurrent(requestId: Long) = started && resumed && tab == 4 && allowed() &&
        locationReader.hasPermission() && requestId == nearbyGeneration

    private fun resumeNearbyPermission() {
        val kind = nearbyResumeKind ?: return
        if (!resumed || tab != 4 || !allowed() || !locationReader.hasPermission()) return
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
            if (tab == 4) render(preserveScroll = true)
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
        parent.addView(label(tx("잠깐 쉬어도 괜찮아요", "Time to recharge yourself"), 32f, true))
        val cards = addCardGrid(parent, 300, 450, 2)
        val rest = surfaceCard()
        rest.addView(sectionTitle(tx("나의 낮잠", "Your nap"), AppIcon.BED))
        val current = nap
        if (current != null) {
            napLabel = label(clock(napRemainingSeconds), 64f, true).also { rest.addView(it) }
            napWakeLabel = label(napWakeTimeText(), 23f, true).also { rest.addView(it) }
            rest.addView(label(tx("화면 밝기를 낮췄어요. 충전 여유가 줄면 먼저 깨워드려요.", "The screen is dimmed. A shorter charge window brings the wake cue forward."), 22f))
            rest.addView(button(tx("일어났어요 · 휴식 마치기", "I'm awake · end rest"), true) { endNap(false, tx("휴식을 마쳤어요", "Rest ended")); render(preserveScroll = true) })
        } else {
            rest.addView(button(tx("휴식 ${napDurationMinutes}분 ▾", "$napDurationMinutes-minute rest ▾"), enabled = !napAfterTemperature && !comfort.state.busy) {
                val minutes = listOf(5, 10, 15, 20, 30)
                chooseSetting(tx("휴식 길이", "Rest duration"), minutes.map { tx("${it}분", "$it min") }, minutes.indexOf(napDurationMinutes)) {
                    napDurationMinutes = minutes[it]; saveNapPreferences(); render(preserveScroll = true)
                }
            })
            napPlanLabel = label("", 22f).also { rest.addView(it) }
            rest.addView(button(tx("기상음 미리 듣기", "Preview wake sound")) {
                napSoundPlayer.stop()
                if (allowed() && !napChime.play(true)) toast(tx("기상음을 재생하지 못했어요. 알람 볼륨을 확인하세요.", "Could not play the wake cue. Check alarm volume."))
            })
            napStartButton = button(tx("낮잠 시작", "Start nap"), true) { startNap() }.also { rest.addView(it) }
        }
        rest.addView(label(tx("이 화면이 열려 있는 동안 기상음을 울려요. 화면을 벗어나면 휴식과 알림이 종료돼요.", "The wake cue works while this screen stays open. Leaving ends the rest and cue."), 18f).apply { setTextColor(muted) })
        if (napNote.isNotBlank()) rest.addView(label(napNote, 22f, true))
        rest.addView(button(tx("기상음 끄기", "Silence wake sound")) { napChime.stop() })
        cards.addView(rest)
        buildNapSound(cards)
        buildNapSettings(cards)
        val climate = surfaceCard()
        climate.addView(sectionTitle(tx("편안한 실내", "Cabin comfort"), AppIcon.SUN))
        val state = comfort.state
        climate.addView(label(if (demoMode) tx("시연 모드 · 실제 차량을 제어하지 않아요", "Demo mode · no vehicle controls") else comfortStatusText(), 20f))
        if (!demoMode) {
            state.cabinCelsius?.let { climate.addView(label(tx("현재 실내 ${it}°C", "Cabin temperature ${it}°C"), 26f, true)) }
            state.climatePowerOn?.let { climate.addView(label(if (it) tx("공조 켜짐", "Climate on") else tx("공조 꺼짐 · 차량에서 켜 주세요", "Climate off · switch it on in the vehicle"), 19f)) }
            state.currentCelsius?.let { climate.addView(label(tx("${state.zoneLabel.orEmpty()} · 설정 온도 ${it}°C", "${state.zoneLabel.orEmpty()} · setpoint ${it}°C"), 23f, true)) }
        }
        if (state.supportedCelsius.isNotEmpty()) {
            if (selectedTemperature !in state.supportedCelsius) selectedTemperature = state.supportedCelsius.minBy { abs(it - 22f) }
            climate.addView(button(tx("원하는 온도 ${selectedTemperature}°C ▾", "Preferred temperature ${selectedTemperature}°C ▾"), enabled = !demoMode && nap == null && !state.busy && !napAfterTemperature) {
                chooseSetting(tx("원하는 온도", "Preferred temperature"), state.supportedCelsius.map { "$it°C" }, state.supportedCelsius.indexOf(selectedTemperature)) {
                    selectedTemperature = state.supportedCelsius[it]; prefs.edit().putFloat("napTemperature", selectedTemperature).apply(); render(preserveScroll = true)
                }
            })
        }
        climate.addView(button(tx("선택한 온도 적용", "Apply temperature"), enabled = !demoMode && state.canSet && !state.busy && nap == null) { comfort.setTemperature(selectedTemperature) })
        climate.addView(button(tx("온도 적용 후 낮잠 시작", "Apply temperature and start nap"), true,
            enabled = !demoMode && allowed() && state.canSet && !state.busy && nap == null &&
                NapSession.start(napDurationMinutes * 60, recommendationSeconds(), SystemClock.elapsedRealtime(), napWakeReserveMinutes * 60) != null) {
            startNapWithTemperature()
        })
        climate.addView(button(tx("변경 전 온도로 복원", "Restore previous temperature"), enabled = !demoMode && state.canRestore && !state.busy && nap == null) { comfort.restoreTemperature() })
        climate.addView(button(tx("차량 지원 상태 확인", "Check vehicle support"), enabled = !demoMode && !state.busy && nap == null) {
            if (safeToConfigure()) {
                if (checkSelfPermission("android.car.permission.CAR_SPEED") != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf("android.car.permission.CAR_SPEED"), 702)
                else comfort.refresh()
            }
        })
        climate.addView(label(tx("주차 P·정지 상태에서 지원되는 공조 구역에 적용해요. 공조 전원은 차량에서 켜 주세요. 종료 시 온도는 자동 복원하지 않아요.",
            "Applies to the supported climate zone only while parked in P. Switch climate power on in the vehicle. Ending rest does not restore temperature automatically."), 18f).apply { setTextColor(muted) })
        climate.addView(sectionTitle(tx("편안한 자세", "Get comfortable"), AppIcon.BED))
        climate.addView(label(seatSupportText(), 20f))
        climate.addView(label(tx("자세는 차량 시트 버튼으로 맞춰 주세요. 앱의 자동 젖힘·원위치 복원은 아직 사용할 수 없어요.",
            "Adjust your posture with the vehicle's seat buttons. Automatic recline and seat restoration are not available in this app yet."), 18f).apply { setTextColor(muted) })
        cards.addView(climate)
        updateNapPlan()
    }

    private fun buildNapSound(cards: ViewGroup) {
        val sound = surfaceCard()
        sound.addView(sectionTitle(tx("잠드는 소리", "Rest sounds"), AppIcon.SOUND))
        sound.addView(label(tx("인터넷 없이, 부드럽게 이어져요", "A soft, offline soundscape"), 20f))
        sound.addView(button("${napSoundTitle(napSound)} ▾") {
            val choices = NapSound.entries
            chooseSetting(tx("배경 소리", "Background sound"), choices.map(::napSoundTitle), napSound.ordinal) {
                napSound = choices[it]; saveNapPreferences()
                napSoundPlayer.stop()
                if (nap != null) { napChime.stop(); napSoundPlayer.play(napSound, napSoundVolume) }
                render(preserveScroll = true)
            }
        })
        sound.addView(label(when (napSound) {
            NapSound.WHITE -> tx("고른 ‘쉬—’ 소리", "An even, airy hiss")
            NapSound.PINK -> tx("고음이 덜한, 부드러운 소리", "A softer sound with less high-frequency energy")
            NapSound.BROWN -> tx("낮고 묵직하게 깔리는 소리", "A deeper, low-pitched sound")
            NapSound.OFF -> tx("소리 없이 조용히 쉬어요", "Rest quietly without background sound")
        }, 20f))
        sound.addView(button(tx("배경음 크기 ${napSoundVolume}% ▾", "Sound level ${napSoundVolume}% ▾")) {
            val volumes = listOf(0, 10, 20, 35)
            chooseSetting(tx("배경음 크기", "Sound level"), volumes.map { if (it == 0) tx("음소거", "Mute") else "$it%" }, volumes.indexOf(napSoundVolume)) {
                napSoundVolume = volumes[it]; saveNapPreferences(); napSoundPlayer.setVolume(napSoundVolume)
                render(preserveScroll = true)
            }
        })
        napSoundLabel = label(napSoundStatusText(), 18f).apply { setTextColor(muted) }.also { sound.addView(it) }
        sound.addView(button(if (nap == null) tx("8초 미리 듣기", "Preview for 8 seconds") else tx("배경 소리 켜기", "Start rest sound"),
            enabled = allowed() && napSound != NapSound.OFF && napSoundVolume > 0) {
            media.pauseForHost(); completionVoice.stop(); napChime.stop()
            napSoundPlayer.play(napSound, napSoundVolume, preview = nap == null)
        })
        sound.addView(button(tx("배경 소리 끄기", "Stop rest sound")) { napSoundPlayer.stop(immediate = false) })
        cards.addView(sound)
    }

    private fun buildNapSettings(cards: ViewGroup) {
        val settings = surfaceCard()
        settings.addView(sectionTitle(tx("나의 기상 준비", "Wake-up preferences"), AppIcon.CLOCK))
        settings.addView(button(tx("기상 여유 ${napWakeReserveMinutes}분 ▾", "Wake buffer $napWakeReserveMinutes min ▾"), enabled = nap == null && !napAfterTemperature && !comfort.state.busy) {
            val choices = listOf(2, 3, 5)
            chooseSetting(tx("일어나 준비할 시간", "Time to get ready"), choices.map { tx("${it}분", "$it min") }, choices.indexOf(napWakeReserveMinutes)) {
                napWakeReserveMinutes = choices[it]; saveNapPreferences(); render(preserveScroll = true)
            }
        })
        settings.addView(button(tx("휴식 화면 밝기 ${napBrightnessPercent}% ▾", "Rest screen brightness $napBrightnessPercent% ▾")) {
            val choices = listOf(5, 12, 25)
            chooseSetting(tx("휴식 중 밝기", "Brightness during rest"), choices.map { "$it%" }, choices.indexOf(napBrightnessPercent)) {
                napBrightnessPercent = choices[it]; saveNapPreferences()
                if (nap != null) window.attributes = window.attributes.apply { screenBrightness = napBrightnessPercent / 100f }
                render(preserveScroll = true)
            }
        })
        settings.addView(label(tx("선택한 소리·밝기·기상 여유를 기억해요. 소리는 ‘낮잠 시작’이나 미리 듣기를 눌렀을 때만 켜져요.",
            "Your sound, brightness and wake buffer are saved. Sound starts only when you start a nap or preview it."), 19f))
        settings.addView(label(tx("끝나면 배경음을 멈추고 화면 밝기를 돌려놓아요. 온도 복원은 아래 버튼에서 직접 선택해 주세요.",
            "At the end, background sound stops and brightness returns. Restore the climate temperature using its button."), 19f).apply { setTextColor(muted) })
        cards.addView(settings)
    }

    private fun saveNapPreferences() {
        prefs.edit().putInt("napDurationMinutes", napDurationMinutes).putInt("napWakeReserveMinutes", napWakeReserveMinutes)
            .putInt("napBrightnessPercent", napBrightnessPercent).putString("napSound", napSound.name)
            .putInt("napSoundVolume", napSoundVolume).apply()
    }

    private fun napSoundTitle(sound: NapSound) = when (sound) {
        NapSound.OFF -> tx("소리 없음", "No sound")
        NapSound.WHITE -> tx("화이트 노이즈", "White noise")
        NapSound.PINK -> tx("핑크 노이즈", "Pink noise")
        NapSound.BROWN -> tx("브라운 노이즈", "Brown noise")
    }

    private fun napSoundStatusText(): String = when (napSoundPlayer.state.status) {
        NapSoundStatus.STARTING -> tx("소리를 준비하고 있어요", "Preparing sound")
        NapSoundStatus.PLAYING -> tx("${napSoundTitle(napSoundPlayer.state.sound)} 재생 중", "${napSoundTitle(napSoundPlayer.state.sound)} playing")
        NapSoundStatus.MUTED -> tx("배경음 음소거", "Background sound muted")
        NapSoundStatus.FOCUS_LOST -> tx("다른 소리가 시작되어 멈췄어요. 다시 켤 수 있어요.", "Stopped for other audio. You can start it again.")
        NapSoundStatus.FOCUS_UNAVAILABLE -> tx("다른 앱이 소리를 사용하고 있어요", "Another app is using audio")
        NapSoundStatus.UNAVAILABLE -> tx("소리 출력을 확인하지 못했어요", "Sound output is unavailable")
        else -> tx("배경 소리 꺼짐", "Background sound off")
    }

    private fun napWakeTimeText(): String {
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(System.currentTimeMillis() + napRemainingSeconds * 1000L))
        return tx("기상 예정 $time · 여유 ${napWakeReserveMinutes}분", "Wake around $time · $napWakeReserveMinutes-min buffer")
    }

    private fun seatSupportText(): String {
        if (demoMode) return tx("시연 중에는 시트를 조절하지 않아요", "Seat controls are not used in demo mode")
        val support = comfort.state.seatSupport
        return when (support.status) {
            com.binteum.app.vehicle.ComfortReadStatus.CHECKING -> tx("시트 지원 상태 확인 중", "Checking seat support")
            com.binteum.app.vehicle.ComfortReadStatus.PERMISSION_REQUIRED -> tx("시트 지원을 읽을 권한이 없어요", "Seat capability permission is unavailable")
            com.binteum.app.vehicle.ComfortReadStatus.AVAILABLE -> {
                val names = support.movements.map { when (it) {
                    com.binteum.app.vehicle.SeatAdjustment.BACKREST -> tx("등받이", "backrest")
                    com.binteum.app.vehicle.SeatAdjustment.FORE_AFT -> tx("앞뒤", "fore/aft")
                    com.binteum.app.vehicle.SeatAdjustment.HEIGHT -> tx("높이", "height")
                    com.binteum.app.vehicle.SeatAdjustment.TILT -> tx("방석 기울기", "cushion tilt")
                } }.joinToString(" · ")
                if (names.isBlank()) tx("앱에서 확인된 시트 이동 기능이 없어요", "No seat movement capability was reported")
                else tx("차량이 제공한 기능: $names", "Vehicle-reported support: $names")
            }
            else -> tx("차량 지원 상태 확인을 눌러 확인해 주세요", "Use Check vehicle support to inspect the seat")
        }
    }

    private fun comfortStatusText(): String = tx(when (comfort.state.status) {
        com.binteum.app.vehicle.ComfortStatus.READY -> "지원 상태를 확인했어요"
        com.binteum.app.vehicle.ComfortStatus.APPLIED -> "차량의 변경된 온도를 확인했어요"
        com.binteum.app.vehicle.ComfortStatus.RESTORED -> "변경 전 온도로 복원됐어요"
        com.binteum.app.vehicle.ComfortStatus.PERMISSION_REQUIRED -> "차량 지원 상태 확인을 눌러 권한을 허용해 주세요"
        com.binteum.app.vehicle.ComfortStatus.PARK_REQUIRED -> "주차 P·속도 0 확인이 필요해요"
        com.binteum.app.vehicle.ComfortStatus.CHANGED_ELSEWHERE -> "차량에서 온도가 바뀌었어요. 이전 값으로 덮어쓰지 않아요"
        com.binteum.app.vehicle.ComfortStatus.UNCONFIRMED -> "변경 결과를 확인하지 못했어요. 차량 화면을 확인하세요"
        com.binteum.app.vehicle.ComfortStatus.CONNECTING, com.binteum.app.vehicle.ComfortStatus.CHECKING -> "차량 지원 상태를 확인하고 있어요"
        else -> "온도 제어가 아직 준비되지 않았어요"
    }, when (comfort.state.status) {
        com.binteum.app.vehicle.ComfortStatus.READY -> "Climate controls available"
        com.binteum.app.vehicle.ComfortStatus.APPLIED -> "Changed temperature verified"
        com.binteum.app.vehicle.ComfortStatus.RESTORED -> "Previous temperature restored"
        com.binteum.app.vehicle.ComfortStatus.PERMISSION_REQUIRED -> "Use Check vehicle support to grant permission"
        com.binteum.app.vehicle.ComfortStatus.PARK_REQUIRED -> "Park P and zero speed must be verified"
        com.binteum.app.vehicle.ComfortStatus.CHANGED_ELSEWHERE -> "Temperature changed elsewhere; not overwritten"
        com.binteum.app.vehicle.ComfortStatus.UNCONFIRMED -> "Change unconfirmed; check the vehicle display"
        com.binteum.app.vehicle.ComfortStatus.CONNECTING, com.binteum.app.vehicle.ComfortStatus.CHECKING -> "Checking vehicle support"
        else -> "Climate controls are not ready"
    })

    private fun startNap() {
        if (!allowed() || nap != null || comfort.state.busy || napAfterTemperature) return
        val next = NapSession.start(napDurationMinutes * 60, recommendationSeconds(), SystemClock.elapsedRealtime(), napWakeReserveMinutes * 60) ?: return
        media.pauseForHost(); completionVoice.stop(); napChime.stop(); napSoundPlayer.stop()
        napChoiceId = recordChoice(UsageCategory.NAP, napEntry); napEntry = UsageEntryPoint.MANUAL
        nap = next; napRemainingSeconds = next.plannedDurationSeconds; napNote = ""
        beforeNapBrightness = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = napBrightnessPercent / 100f }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        napSoundPlayer.play(napSound, napSoundVolume)
        render(preserveScroll = true)
    }

    private fun startNapWithTemperature() {
        if (!allowed() || demoMode || nap != null || !comfort.state.canSet || comfort.state.busy) return
        if (NapSession.start(napDurationMinutes * 60, recommendationSeconds(), SystemClock.elapsedRealtime(), napWakeReserveMinutes * 60) == null) return
        val request = ++napTemperatureRequest
        napAfterTemperature = true
        napNote = tx("선택한 온도가 확인되면 낮잠을 시작해요.", "The nap starts after the chosen temperature is verified.")
        comfort.setTemperature(selectedTemperature)
        handler.postDelayed({
            if (napAfterTemperature && request == napTemperatureRequest) {
                cancelNapTemperatureStart()
                napNote = tx("온도 확인이 지연되어 낮잠을 시작하지 않았어요.", "Temperature verification timed out; the nap was not started.")
                if (started && resumed && tab == 5) render(preserveScroll = true)
            }
        }, 12_000)
    }

    private fun cancelNapTemperatureStart() { napAfterTemperature = false; napTemperatureRequest++ }

    private fun cancelUnsafeNapTemperatureStart() {
        if (napAfterTemperature && !allowed()) {
            cancelNapTemperatureStart()
            napNote = tx("충전·차량 상태가 바뀌어 낮잠 시작을 취소했어요.", "Nap start cancelled because the charging or vehicle state changed.")
        }
    }

    private fun tickNap() {
        updateNapPlan()
        val current = nap ?: return
        val progress = current.tick(SystemClock.elapsedRealtime(), if (allowed()) recommendationSeconds() else null)
        napRemainingSeconds = progress.remainingSeconds
        napLabel?.text = clock(progress.remainingSeconds)
        napWakeLabel?.text = napWakeTimeText()
        if (progress.finished) {
            val completedNormally = progress.finishReason == NapFinishReason.REQUESTED_DURATION_COMPLETE || progress.finishReason == NapFinishReason.CHARGE_WINDOW_ENDING
            endNap(completedNormally, if (completedNormally) tx("일어날 시간이에요. 차량 상태를 확인해 주세요.", "Time to wake up. Check your vehicle.")
                else tx("충전·차량 상태를 확인할 수 없어 휴식을 마쳤어요.", "Rest ended because charging or vehicle status is unavailable."), progress.elapsedSeconds)
            if (progress.shouldWake && !napChime.play()) napNote += tx(" 기상음 재생 불가 · 알람 볼륨을 확인하세요.", " Wake sound unavailable; check alarm volume.")
            render(preserveScroll = true)
        }
    }

    private fun endNap(finished: Boolean, note: String, elapsed: Int? = null) {
        val current = nap ?: return
        napSoundPlayer.stop()
        val duration = elapsed ?: current.cancel(SystemClock.elapsedRealtime()).elapsedSeconds
        napChoiceId?.let { id -> usageRecords.firstOrNull { it.id == id }?.let { event ->
            val updated = event.copy(durationSeconds = duration, completed = finished)
            usageStore.save(updated); usageRecords = UsagePreferences.record(usageRecords, updated, 4000)
        } }
        nap = null; napChoiceId = null; napNote = note
        window.attributes = window.attributes.apply { screenBrightness = beforeNapBrightness }
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun updateNapPlan() {
        if (nap != null) return
        val planned = NapSession.start(napDurationMinutes * 60, recommendationSeconds(), SystemClock.elapsedRealtime(), napWakeReserveMinutes * 60)
        napPlanLabel?.text = if (planned == null) tx("남은 여유가 ${napWakeReserveMinutes}분을 넘어야 해요. 충전 예상시간이나 출발 시각을 확인해 주세요.",
            "More than $napWakeReserveMinutes minutes are needed. Check the charge estimate or departure time.")
            else tx("지금은 최대 ${(planned.plannedDurationSeconds + 59) / 60}분 휴식 · 기상 여유 ${napWakeReserveMinutes}분", "Up to ${(planned.plannedDurationSeconds + 59) / 60} min now · $napWakeReserveMinutes-minute wake buffer")
        napStartButton?.isEnabled = planned != null && allowed() && !comfort.state.busy
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
        history.addView(label(tx("영상·음악의 제목이나 재생 이력은 저장하지 않아요. 음악 서비스 열기는 재생 완료로 기록하지 않아요. 시연 기록은 실제 선호도 학습에서 제외해요.",
            "Video and music titles or playback history are not stored. Opening music does not record playback completion. Demo choices do not train preferences."), 19f).apply { setTextColor(muted) })
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
        cancelNapTemperatureStart()
        if (nap != null) endNap(false, tx("데이터 모드가 바뀌어 휴식을 마쳤어요", "Rest ended after changing the data mode"))
        napSoundPlayer.stop(); napChime.stop()
        cancelNearbyWork(); nearbyResumeKind = null
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
        if (requestCode == 701) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) resumeNearbyPermission()
            else {
                nearbyResumeKind = null; nearbyLoading = false
                nearbyNote = tx("현재 위치를 사용하려면 위치 권한이 필요해요. 다시 검색을 눌러 허용해 주세요.", "Location permission is needed for nearby places. Use Search again to allow it.")
                render(preserveScroll = true)
            }
        }
        if (requestCode == 702 && !demoMode) comfort.refresh()
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
