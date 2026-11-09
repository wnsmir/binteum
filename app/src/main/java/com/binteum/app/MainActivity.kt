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
            completionVoice.speak(getString(R.string.voice_complete),
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
    private var windowMinutes = 20
    private var targetMode = false
    private var reminderStart = 0L
    private var reminderFinish = 0L
    private var reminderDial: ReminderDialView? = null
    private var modeToggle: ReminderModeToggle? = null
    private var reminderSubtitle: TextView? = null
    private var confirmReminder: Button? = null
    private var session = ChargingSession(80, targetEnabled = false)
    private var status = SessionStatus.WAITING_FOR_DATA
    private var snapshot: VehicleSnapshot? = null
    private var sourceStartedAt = 0L
    private var started = false
    private var resumed = false
    private var demoMode = false
    private var demoScenario = 0
    private var tab = 0
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
    private var windowEndElapsed = 0L
    private var windowEndWall = 0L
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
    private var lastWindowHasBudget = true
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
            if (TimerCompletion.isDue(SystemClock.elapsedRealtime(), windowEndElapsed, reminderStart, reminderFinish, targetMode)) {
                reminderFinish = windowEndElapsed
                media.pauseForHost(); active = null; recovery = null; completed = null
                message = getString(com.binteum.app.R.string.ui_text_001)
                saveCheckpoint(); render(preserveScroll = true)
                // Do not announce an old expiry when returning from another app or suspend.
                if (TimerCompletion.canAnnounce(SystemClock.elapsedRealtime(), windowEndElapsed, resumed)) announceCompletion()
            }
            if (!safeToConfigure()) completionVoice.stop()
            updateLabels()
            refreshMediaGate()
            val hasBudget = remainingWindowSeconds() > ActivityRecommender.DEFAULT_RESERVE_SECONDS
            if (lastWindowHasBudget && !hasBudget) {
                media.pauseForHost()
                message = getString(com.binteum.app.R.string.ui_text_002)
                if (active == null) render()
            }
            lastWindowHasBudget = hasBudget
            if (tab != 2 && allowed() && active == null && recovery == null && completed == null &&
                windowContentKey() != lastWindowContentKey) render(preserveScroll = true)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab", 0) ?: 0
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "DARK") ?: "DARK") }.getOrDefault(ThemeMode.SYSTEM)
        palette = AppTheme.palette(this, themeMode)
        backgroundMode = runCatching {
            BackgroundMode.valueOf(prefs.getString("backgroundMode", "AUTO") ?: "AUTO")
        }.getOrDefault(BackgroundMode.AUTO)
        backgroundMotion = prefs.getBoolean("backgroundMotion", true)
        panelOnRight = prefs.getBoolean("panelOnRight", false)
        target = prefs.getInt("target", 80).takeIf { it in 1..100 } ?: 80
        windowMinutes = prefs.getInt("windowMinutes", 20).takeIf { it in 1..120 } ?: 20
        learningOn = prefs.getBoolean("learningOn", true)
        demoMode = BuildConfig.DEBUG && prefs.getBoolean("demoMode", false)
        demoScenario = prefs.getInt("demoScenario", 0).coerceIn(0, 4)
        targetMode = prefs.getBoolean("targetMode", false)
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
        media.onContentOpen = {
            if (allowed() && remainingWindowSeconds() > 120 && active == null && recovery == null) {
                startWindowIfNeeded()
                true
            } else false
        }
        source = makeSource()
        restoreCheckpoint()
        // Activities are withdrawn from the UI. Keep stored preferences, but never resume an old activity.
        active = null; recovery = null; completed = null
        val reached = reminderStart > 0 && targetMode && prefs.getBoolean("targetReached", false)
        session = ChargingSession(target, checkpoint = SessionCheckpoint(target, reached,
            reached && prefs.getBoolean("targetNotified", false)), targetEnabled = targetMode && reminderStart > 0)
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true
        snapshot = null
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
        if (tab == 2) navigateTo(0) else super.onBackPressed()
    }

    override fun onDestroy() { completionVoice.stop(); ambientBackground.release(); media.release(); super.onDestroy() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        palette = AppTheme.palette(this, themeMode)
        render(preserveScroll = true)
    }

    private fun makeSource(): VehicleDataSource = if (demoMode) DemoVehicleDataSource() else PleosVehicleDataSource(this)

    private fun acceptSnapshot(value: VehicleSnapshot) {
        snapshot = value
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
                active = null; completed = null
                message = getString(R.string.activity_interrupted, statusText())
                saveCheckpoint()
            }
            if (status != SessionStatus.WAITING_FOR_DATA && recovery != null) {
                recovery = null
                message = getString(com.binteum.app.R.string.ui_text_004)
                saveCheckpoint()
            }
        }
        if (result.notifyTargetReached) {
            if (reminderStart > 0 && reminderFinish == 0L) reminderFinish = SystemClock.elapsedRealtime()
            message = getString(R.string.target_reached_percent, target)
            persistTarget(); saveCheckpoint()
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
            if (tab == 2 && connectionCard?.isAttachedToWindow == true) refreshConnectionSettings(false)
            else render()
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
    private fun allowed(): Boolean = reminderStart > 0 && reminderFinish == 0L && safeToConfigure() && status == SessionStatus.CHARGING
    private fun refreshMediaGate() {
        media.updateRecommendations(if (targetMode) null else remainingWindowSeconds())
        media.setPlaybackAllowed(tab == 0 && allowed() && remainingWindowSeconds() > 120 && active == null && recovery == null && completed == null,
            if (remainingWindowSeconds() <= 120) getString(com.binteum.app.R.string.ui_text_011) else statusText())
    }
    private fun startWindowIfNeeded() { /* The user starts the clock with 확인, never by opening content. */ }
    private fun remainingWindowSeconds(): Int = if (targetMode) Int.MAX_VALUE
        else if (windowEndElapsed == 0L) windowMinutes * 60
        else ((windowEndElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000).toInt()
    private fun windowContentKey(): String {
        val remaining = remainingWindowSeconds()
        val phase = when { remaining == 0 -> "ended"; remaining <= ActivityRecommender.DEFAULT_RESERVE_SECONDS -> "finishing"; else -> "available" }
        val eligible = (ActivityCatalog.all + ActivityCatalog.guided).filter { it.durationSeconds + ActivityRecommender.DEFAULT_RESERVE_SECONDS <= remaining }
            .joinToString(",") { it.id }
        return if (media.hasOpenContent) phase else "$phase/$eligible"
    }
    private fun remainingActivitySeconds(run: Run): Int {
        val duration = ActivityCatalog.find(run.activityId)?.durationSeconds ?: 0
        val elapsed = (SystemClock.elapsedRealtime() - run.startedElapsed).coerceAtLeast(0)
        return ((duration * 1000L - elapsed).coerceAtLeast(0).plus(999) / 1000).toInt()
    }
    private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)
    private fun bucket() = when (windowMinutes) { 8 -> "short"; 20 -> "medium"; 40 -> "long"; else -> "extended" }

    private fun render(preserveScroll: Boolean = false) {
        if (!::media.isInitialized) return
        palette = AppTheme.palette(this, themeMode, backgroundMode)
        handler.removeCallbacks(connectionRefresh)
        animateConnectionRefresh = false
        connectionCard = null
        val previousLeftScroll = if (preserveScroll) leftScroll?.scrollY ?: 0 else 0
        val previousContentScroll = if (preserveScroll) contentScroll?.scrollY ?: 0 else 0
        lastWindowContentKey = windowContentKey()
        val preserveMedia = tab == 0 && allowed() && active == null && recovery == null && completed == null
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
            setContentView(root)
            ambientBackground.callback = root
            ambientBackground.setActive(resumed && safeToConfigure())
            updateSystemBars()
            if (preserveScroll) {
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
        if (tab == 2) navigateTo(0) else movePanel(!panelOnRight)
    }

    private fun buildPanelActionButton(): Button {
        val confirming = tab == 2
        val title = if (confirming) getString(R.string.settings_apply) else if (panelOnRight) "←" else "→"
        return button(title, selected = confirming) { performPanelAction() }.apply {
            contentDescription = if (confirming) getString(R.string.settings_confirm)
                else getString(if (panelOnRight) R.string.panel_move_left else R.string.panel_move_right)
            tooltipText = contentDescription
            textSize = if (confirming) 24f else 32f
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

    private fun buildLeft(compact: Boolean): LinearLayout = column().apply {
        setPadding(dp(20), dp(20), dp(20), dp(24))
        background = rounded(palette.surface, 32, palette.border)
        gravity = Gravity.CENTER_HORIZONTAL
        val utilityWidth = 64
        val utilities = row().apply { gravity = Gravity.CENTER_VERTICAL }
        if (tab == 2) {
            utilities.addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(64), dp(64)))
        } else {
            utilities.addView(iconButton(getString(com.binteum.app.R.string.ui_text_014), AppIcon.SETTINGS) { navigateTo(2) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        }
        utilities.addView(label(if (tab == 2) getString(com.binteum.app.R.string.ui_text_014) else getString(com.binteum.app.R.string.ui_text_015), 24f, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(16, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // Match the leading control's width so the title shares the dial's centre line.
        utilities.addView(View(this@MainActivity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(utilityWidth), 1))
        addView(utilities, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        if (demoMode) addView(label(getString(com.binteum.app.R.string.ui_text_016), 20f).apply {
            gravity = Gravity.CENTER; setTextColor(palette.warning)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        socLabel = label("", 26f, true).apply { gravity = Gravity.CENTER }.also {
            addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        }
        val choosing = reminderStart == 0L
        modeToggle = null
        if (choosing) {
            modeToggle = ReminderModeToggle(this@MainActivity, palette, targetMode) { mode ->
                if (safeToConfigure() && reminderStart == 0L) {
                    targetMode = mode
                    prefs.edit().putBoolean("targetMode", mode).apply()
                    session = ChargingSession(target, targetEnabled = false)
                    persistTarget()
                    reminderDial?.configure(if (mode) target else windowMinutes, 1, if (mode) 100 else 120,
                        if (mode) "%" else getString(com.binteum.app.R.string.ui_text_017), palette, true)
                    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        reminderDial?.animate()?.cancel()
                        reminderDial?.alpha = .55f
                        reminderDial?.animate()?.alpha(1f)?.setDuration(220)?.start()
                    }
                    updateLabels()
                }
            }.also { addView(it, LinearLayout.LayoutParams(-1, dp((68 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt())).apply { bottomMargin = dp(20) }) }
        }
        reminderDial = ReminderDialView(this@MainActivity).apply {
            configure(if (targetMode) target else windowMinutes, 1, if (targetMode) 100 else 120,
                if (targetMode) "%" else getString(com.binteum.app.R.string.ui_text_017), palette, choosing && safeToConfigure())
            onValueChanged = { value ->
                if (targetMode) target = value else windowMinutes = value
                prefs.edit().putInt(if (targetMode) "target" else "windowMinutes", value).apply()
            }
        }.also { addView(it, LinearLayout.LayoutParams(-1, dp(if (compact) 250 else 280))) }
        reminderSubtitle = label("", 24f).apply { gravity = Gravity.CENTER; setTextColor(muted) }.also { addView(it) }
        confirmReminder = button(if (choosing) getString(com.binteum.app.R.string.ui_text_018) else getString(com.binteum.app.R.string.ui_text_019), true, safeToConfigure()) {
            if (!safeToConfigure()) return@button
            if (reminderStart > 0) resetWaitingWindow() else confirmReminder()
        }.also { addView(it) }
        statusLabel = label("", 21f).apply { gravity = Gravity.CENTER; setTextColor(muted) }.also { addView(it) }
        if (!compact || tab == 2) {
            if (!compact) addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(buildPanelActionButton())
        }
    }

    private fun confirmReminder() {
        if (!safeToConfigure() || snapshot?.charging != true) return
        media.pauseForHost(); active = null; recovery = null; completed = null
        reminderStart = SystemClock.elapsedRealtime(); reminderFinish = 0L
        windowEndElapsed = if (targetMode) 0 else reminderStart + windowMinutes * 60_000L
        windowEndWall = if (targetMode) 0 else System.currentTimeMillis() + windowMinutes * 60_000L
        session = ChargingSession(target, targetEnabled = targetMode)
        message = ""; lastWindowHasBudget = true
        prefs.edit().putBoolean("targetMode", targetMode).putInt("windowMinutes", windowMinutes).apply()
        persistTarget(); saveCheckpoint(); snapshot?.let(::acceptSnapshot); render()
    }

    private fun resetWaitingWindow(minutes: Int? = null) {
        if (!safeToConfigure()) return
        media.pauseForHost(); active = null; recovery = null; completed = null
        reminderStart = 0; reminderFinish = 0; windowEndElapsed = 0; windowEndWall = 0
        if (minutes != null) windowMinutes = minutes.coerceIn(1, 120)
        session = ChargingSession(target, targetEnabled = false)
        message = ""; lastWindowHasBudget = true
        persistTarget(); saveCheckpoint(); snapshot?.let(::acceptSnapshot); render()
    }

    private fun buildRight(): LinearLayout = column().apply {
        setPadding(dp(if (panelOnRight) 16 else 32), dp(24), dp(if (panelOnRight) 32 else 16), dp(28))
        when (tab) {
            2 -> buildSettings(this)
            else -> {
                val showingEndState = allowed() && remainingWindowSeconds() <= ActivityRecommender.DEFAULT_RESERVE_SECONDS &&
                    active == null && recovery == null && completed == null
                if (message.isNotBlank() && !showingEndState && reminderFinish == 0L) addView(label(message, 24f).apply { setTextColor(muted) })
                addView(space(12))
                when {
                    reminderFinish > 0 -> addView(label(if (targetMode) getString(R.string.target_reached_percent, target) else getString(com.binteum.app.R.string.ui_text_001), 30f, true))
                    reminderStart == 0L && status == SessionStatus.CHARGING -> {
                        addView(label(getString(com.binteum.app.R.string.ui_text_020), 30f, true))
                        addView(label(getString(com.binteum.app.R.string.ui_text_021), 24f).apply { setTextColor(muted) })
                    }
                    !allowed() -> buildBlockedState(this)
                    recovery != null -> {
                        addView(label(getString(com.binteum.app.R.string.ui_text_022), 30f, true))
                        addView(label(getString(com.binteum.app.R.string.ui_text_023), 24f))
                        addView(button(getString(com.binteum.app.R.string.ui_text_024), true) { resumeActivity() })
                        addView(button(getString(com.binteum.app.R.string.ui_text_025)) { recovery = null; saveCheckpoint(); render() })
                    }
                    active != null -> buildActive(this, active!!)
                    completed != null -> buildFeedback(this, completed!!)
                    else -> buildRecommendations(this)
                }
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

    private fun navigateTo(destination: Int) {
        if (tab == destination) return
        media.pauseForHost()
        if (active != null && destination != 0) interruptActivity(getString(com.binteum.app.R.string.ui_text_043))
        tab = destination
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

    private fun showMoodPicker() {
        if (!allowed() || remainingWindowSeconds() <= ActivityRecommender.DEFAULT_RESERVE_SECONDS) return
        transientDialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle(getString(com.binteum.app.R.string.ui_text_048))
            .setItems(arrayOf(getString(com.binteum.app.R.string.ui_text_049), getString(com.binteum.app.R.string.ui_text_050), getString(com.binteum.app.R.string.ui_text_051))) { _, index ->
                if (allowed() && remainingWindowSeconds() > ActivityRecommender.DEFAULT_RESERVE_SECONDS) {
                    val mood = listOf(getString(com.binteum.app.R.string.ui_text_049), getString(com.binteum.app.R.string.ui_text_050), getString(com.binteum.app.R.string.ui_text_051))[index]
                    prefs.edit().putString("lastMood", mood).putString("moodDate", java.time.LocalDate.now().toString()).apply()
                    render(preserveScroll = true)
                }
            }
            .setNeutralButton(getString(com.binteum.app.R.string.ui_text_052)) { _, _ ->
                if (allowed()) { prefs.edit().remove("lastMood").remove("moodDate").apply(); render(preserveScroll = true) }
            }
            .setNegativeButton(getString(com.binteum.app.R.string.ui_text_053), null).show()
    }

    private fun feedCard(title: String, detail: String, art: Int, enabled: Boolean = true, action: () -> Unit) = column().apply {
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = rounded(palette.surface, 28, palette.border)
        addView(WellnessArtView(this@MainActivity).apply { kind = art }, LinearLayout.LayoutParams(-1, dp(100)))
        addView(label(title, 28f, true))
        addView(label(detail, 22f).apply { setTextColor(muted) })
        isEnabled = enabled; alpha = if (enabled) 1f else 0.5f
        isClickable = true; isFocusable = true
        contentDescription = "$title, $detail${if (enabled) "" else getString(com.binteum.app.R.string.ui_text_054)}"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        foreground = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, rounded(android.graphics.Color.WHITE, 28))
        setOnClickListener { if (enabled) action() }
    }

    private fun buildRecommendations(parent: LinearLayout) {
        if (remainingWindowSeconds() <= ActivityRecommender.DEFAULT_RESERVE_SECONDS) {
            buildWaitingWindowEnd(parent); return
        }
        parent.addView(media)
        parent.addView(space(20))
        parent.addView(surfaceCard().apply {
            val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(label("N", 40f, true).apply { setTextColor(0xFFE50914.toInt()); setPadding(0, 0, dp(20), 0) })
            heading.addView(label("Netflix", 30f, true), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(icon(AppIcon.CHEVRON_RIGHT, ink, 26), LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(heading)
            addView(label(getString(R.string.netflix_external), 23f).apply { setTextColor(muted) })
            addView(label(getString(R.string.netflix_external_detail), 20f).apply { setTextColor(muted) })
            isClickable = true; isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            contentDescription = getString(R.string.netflix_external_accessibility)
            foreground = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, rounded(android.graphics.Color.WHITE, 28))
            setOnClickListener { openNetflix() }
        })
    }

    private fun openNetflix() {
        if (!allowed() || remainingWindowSeconds() <= ActivityRecommender.DEFAULT_RESERVE_SECONDS) return
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

    private fun buildWaitingWindowEnd(parent: LinearLayout) {
        val remaining = remainingWindowSeconds()
        parent.addView(surfaceCard().apply {
            addView(sectionTitle(getString(com.binteum.app.R.string.ui_text_055), AppIcon.CLOCK))
            addView(label(if (remaining == 0) getString(com.binteum.app.R.string.ui_text_001) else getString(com.binteum.app.R.string.ui_text_056), 34f, true))
            addView(label(if (remaining == 0) getString(com.binteum.app.R.string.ui_text_057)
                else getString(com.binteum.app.R.string.ui_text_058), 24f).apply { setTextColor(muted) })
            addView(space(16))
            addView(button(getString(R.string.reset_minutes, windowMinutes), true, allowed()) {
                resetWaitingWindow(windowMinutes)
            }.apply { withIcon(AppIcon.RESET, palette.onAccent) })
            addView(button(getString(com.binteum.app.R.string.ui_text_059), false, allowed()) { resetWaitingWindow() }.apply { withIcon(AppIcon.CLOCK) })
            addView(label(getString(com.binteum.app.R.string.ui_text_060), 22f).apply { setTextColor(muted) })
        })
    }

    private fun startActivity(activity: OwnActivity) {
        if (!allowed() || !ActivityCatalog.canStart(activity.id, remainingWindowSeconds(), status)) return
        media.pauseForHost()
        startWindowIfNeeded()
        active = Run(activity.id, UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis(), bucket())
        completed = null; quizIndex = 0; quizAnswer = null
        message = ""
        saveCheckpoint(); render()
    }

    private fun buildActive(container: LinearLayout, run: Run) {
        val parent = surfaceCard()
        container.addView(parent)
        val activity = ActivityCatalog.find(run.activityId) ?: return
        parent.addView(label(activity.title, 32f, true))
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
            if (allowed()) { completed = active; active = null; message = ""; saveCheckpoint(); render() }
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
                    saveProfile(run.bucket, result.profile); completed = null
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
        parent.addView(space(12))
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
        parent.addView(display)
        val voice = surfaceCard()
        voice.addView(sectionTitle(getString(R.string.voice_heading), AppIcon.INFO))
        voice.addView(settingSwitch(getString(R.string.voice_enabled), getString(R.string.voice_detail), prefs.getBoolean("completionVoice", true)) {
            prefs.edit().putBoolean("completionVoice", it).apply()
            if (!it) completionVoice.stop()
        })
        voice.addView(settingRow(getString(R.string.voice_test), getString(R.string.voice_test_detail), getString(R.string.voice_play)) {
            if (prefs.getBoolean("completionVoice", true) && safeToConfigure()) { media.pauseForHost(); announceCompletion() }
            else android.widget.Toast.makeText(this, R.string.voice_test_blocked, android.widget.Toast.LENGTH_LONG).show()
        })
        parent.addView(voice)
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
        parent.addView(background)
        val connection = surfaceCard()
        connectionCard = connection
        populateConnectionSettings(connection)
        parent.addView(connection)
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
                            session = ChargingSession(target, targetEnabled = targetMode && reminderStart > 0)
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
        reminderStart = 0; reminderFinish = 0; windowEndElapsed = 0; windowEndWall = 0
        session = ChargingSession(target, targetEnabled = false); persistTarget()
        snapshot = null; status = SessionStatus.WAITING_FOR_DATA
        sourceStartedAt = System.currentTimeMillis(); source = makeSource()
        source.start { value -> runOnUiThread { if (started) acceptSnapshot(value) } }
        if (demoMode) emitDemoScenario()
        saveCheckpoint()
        if (tab == 2 && connectionCard?.isAttachedToWindow == true) refreshConnectionSettings() else render()
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
        active = null; completed = null; message = reason; saveCheckpoint()
    }

    private fun resumeActivity() {
        val run = recovery ?: return
        if (!allowed()) return
        if (!validClock(run) || remainingActivitySeconds(run) <= 0 || remainingWindowSeconds() <= 120) {
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
        val data = JSONObject().put("endElapsed", windowEndElapsed).put("endWall", windowEndWall)
            .put("savedElapsed", SystemClock.elapsedRealtime()).put("savedWall", System.currentTimeMillis())
            .put("reminderStart", reminderStart).put("reminderFinish", reminderFinish)
            .put("quizIndex", quizIndex).put("quizAnswer", quizAnswer ?: -1)
        if (run != null) data.put("run", JSONObject().put("id", run.activityId).put("attempt", run.attemptId)
            .put("elapsed", run.startedElapsed).put("wall", run.startedWall).put("bucket", run.bucket))
        prefs.edit().putString("checkpoint", data.toString()).apply()
    }

    private fun restoreCheckpoint() {
        try {
            val raw = prefs.getString("checkpoint", null) ?: return
            val data = JSONObject(raw)
            val deltaElapsed = SystemClock.elapsedRealtime() - data.getLong("savedElapsed")
            val deltaWall = System.currentTimeMillis() - data.getLong("savedWall")
            if (deltaElapsed < 0 || deltaWall < 0 || abs(deltaElapsed - deltaWall) > 5_000) {
                prefs.edit().remove("checkpoint").apply(); message = getString(com.binteum.app.R.string.ui_text_122); return
            }
            reminderStart = data.optLong("reminderStart")
            reminderFinish = data.optLong("reminderFinish")
            if (reminderStart <= 0) return // Older auto-start checkpoints need explicit confirmation.
            windowEndElapsed = data.optLong("endElapsed")
            windowEndWall = data.optLong("endWall")
            quizIndex = data.optInt("quizIndex").coerceAtLeast(0)
            quizAnswer = data.optInt("quizAnswer", -1).takeIf { it >= 0 }
            data.optJSONObject("run")?.let { item ->
                if (ActivityCatalog.find(item.getString("id")) != null) recovery = Run(item.getString("id"),
                    item.getString("attempt"), item.getLong("elapsed"), item.getLong("wall"), item.getString("bucket"))
            }
        } catch (_: Exception) { prefs.edit().remove("checkpoint").apply(); message = getString(com.binteum.app.R.string.ui_text_123) }
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
        modeToggle?.isEnabled = safeToConfigure() && reminderStart == 0L
        modeToggle?.alpha = if (safeToConfigure()) 1f else .45f
        val localTime = java.time.LocalTime.now()
        val sky = AmbientSky.colors(backgroundMode, localTime.hour * 60 + localTime.minute)
        reminderDial?.updateTint(if (backgroundMode.isFlat) palette.text else AmbientSky.blend(sky.middle, 0xFFE2E5E9.toInt(), .55f))
        val usable = snapshot?.takeIf { System.currentTimeMillis() - it.observedAtMillis in 0..30_000 && it.observedAtMillis >= sourceStartedAt }
        val percent = usable?.stateOfCharge?.takeIf { it in 0..100 }
        socLabel?.text = if (percent != null) {
            val text = getString(R.string.battery_percent, percent)
            android.text.SpannableString(text).apply {
                val number = "$percent%"
                val start = text.indexOf(number)
                if (start >= 0) setSpan(android.text.style.RelativeSizeSpan(48f / 26f), start, start + number.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else getString(R.string.battery_unknown)
        diagnosticsLabel?.text = (source as? PleosVehicleDataSource)?.diagnostics.orEmpty()
        val running = reminderStart > 0
        if (running) {
            val elapsed = (((if (reminderFinish > 0) reminderFinish else SystemClock.elapsedRealtime()) - reminderStart).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            reminderDial?.progress(if (targetMode) "${target}%" else clock(remainingWindowSeconds()),
                if (reminderFinish > 0) getString(com.binteum.app.R.string.ui_text_124) else if (targetMode) getString(com.binteum.app.R.string.ui_text_125) else getString(com.binteum.app.R.string.ui_text_127),
                if (targetMode) (percent ?: 0).toFloat() / target else 1f - remainingWindowSeconds().toFloat() / (windowMinutes * 60))
            reminderSubtitle?.text = if (targetMode) clock(elapsed) else getString(R.string.minute_reminder, windowMinutes)
        } else {
            reminderDial?.isEnabled = safeToConfigure()
            reminderSubtitle?.text = ""
        }
        reminderSubtitle?.visibility = if (reminderSubtitle?.text.isNullOrBlank()) View.GONE else View.VISIBLE
        confirmReminder?.isEnabled = safeToConfigure() && (running || snapshot?.charging == true)
        statusLabel?.text = when {
            reminderFinish > 0 -> ""
            !safeToConfigure() -> getString(com.binteum.app.R.string.ui_text_000)
            snapshot?.charging != true -> getString(com.binteum.app.R.string.ui_text_007)
            else -> ""
        }
        statusLabel?.visibility = if (statusLabel?.text.isNullOrBlank()) View.GONE else View.VISIBLE
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
