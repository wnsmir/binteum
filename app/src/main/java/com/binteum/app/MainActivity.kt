package com.binteum.app

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
    private var themeMode = ThemeMode.SYSTEM
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
    private var session = ChargingSession(80)
    private var status = SessionStatus.WAITING_FOR_DATA
    private var snapshot: VehicleSnapshot? = null
    private var sourceStartedAt = 0L
    private var started = false
    private var resumed = false
    private var demoMode = false
    private var demoScenario = 0
    private var tab = 0
    private var chargeControlsExpanded = false
    private var developerOptionsExpanded = false
    private var learningOn = true
    private var windowEndElapsed = 0L
    private var windowEndWall = 0L
    private var message = "차량 상태를 확인하고 있어요."
    private var active: Run? = null
    private var recovery: Run? = null
    private var completed: Run? = null
    private var quizIndex = 0
    private var quizAnswer: Int? = null
    private var socLabel: TextView? = null
    private var statusLabel: TextView? = null
    private var windowLabel: TextView? = null
    private var timerLabel: TextView? = null
    private var diagnosticsLabel: TextView? = null
    private var chargeGauge: ChargeGaugeView? = null
    private var leftScroll: ScrollView? = null
    private var contentScroll: ScrollView? = null
    private val timeButtons = mutableListOf<Button>()
    private var resetWindowButton: Button? = null
    private var lastWindowHasBudget = true
    private var lastWindowContentKey = ""
    private var lastReadinessKey = ""
    private var lastDebugObservation = ""
    private data class Run(val activityId: String, val attemptId: String, val startedElapsed: Long,
                           val startedWall: Long, val bucket: String)
    private val ticker = object : Runnable {
        override fun run() {
            if (!started) return
            if (demoMode && demoScenario != 4) emitDemoScenario()
            snapshot?.let(::acceptSnapshot)
            updateLabels()
            refreshMediaGate()
            val hasBudget = remainingWindowSeconds() > ActivityRecommender.DEFAULT_RESERVE_SECONDS
            if (lastWindowHasBudget && !hasBudget) {
                media.pauseForHost()
                message = "마무리할 시간이에요. 새 활동은 다음 대기 시간에 시작해 주세요."
                if (active == null) render()
            }
            lastWindowHasBudget = hasBudget
            if (tab != 2 && allowed() && active == null && recovery == null && completed == null &&
                windowContentKey() != lastWindowContentKey) render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "SYSTEM") ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
        palette = AppTheme.palette(this, themeMode)
        target = prefs.getInt("target", 80).takeIf { it in listOf(70, 80, 90) } ?: 80
        windowMinutes = prefs.getInt("windowMinutes", 20).takeIf { it in listOf(8, 20, 40, 60) } ?: 20
        learningOn = prefs.getBoolean("learningOn", true)
        demoMode = BuildConfig.DEBUG && prefs.getBoolean("demoMode", false)
        demoScenario = prefs.getInt("demoScenario", 0).coerceIn(0, 4)
        val reached = prefs.getBoolean("targetReached", false)
        session = ChargingSession(target, checkpoint = SessionCheckpoint(target, reached,
            reached && prefs.getBoolean("targetNotified", false)))
        media = WebMediaPanel(this)
        media.onContentOpen = {
            if (allowed() && remainingWindowSeconds() > 120 && active == null && recovery == null) {
                startWindowIfNeeded()
                true
            } else false
        }
        source = makeSource()
        restoreCheckpoint()
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true
        snapshot = null
        status = SessionStatus.WAITING_FOR_DATA
        sourceStartedAt = System.currentTimeMillis()
        media.setPlaybackAllowed(false, "새 차량 상태를 확인하고 있어요.")
        render()
        source.start { value -> runOnUiThread { if (started) acceptSnapshot(value) } }
        if (demoMode) emitDemoScenario()
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
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
        active?.let { recovery = it; active = null }
        saveCheckpoint()
        source.stop()
        super.onStop()
    }

    override fun onDestroy() { media.release(); super.onDestroy() }

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
                message = "${statusText()} 활동을 중단했어요. 취향 평가에는 반영하지 않아요."
                saveCheckpoint()
            }
            if (status != SessionStatus.WAITING_FOR_DATA && recovery != null) {
                recovery = null
                message = "차량 상태가 바뀌어 이전 활동을 중단했어요."
                saveCheckpoint()
            }
        }
        if (result.notifyTargetReached) {
            message = "설정한 ${target}%에 도달했어요. 차량 이동을 준비해 주세요."
            persistTarget()
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
            render()
        } else updateLabels()
    }

    private fun statusText() = when (status) {
        SessionStatus.CHARGING -> "충전 중 · 차 안 활동 가능"
        SessionStatus.TARGET_REACHED -> "목표 잔량에 도달했어요"
        SessionStatus.INTERRUPTED -> "충전 중이 아니에요 · 연결 확인"
        SessionStatus.DRIVING_LOCKED -> "주행 제한 · 활동을 멈췄어요"
        SessionStatus.SUSPENDED -> "화면 비활성 · 상태 확인 중단"
        SessionStatus.WAITING_FOR_DATA -> "차량·안전 상태 확인 필요"
    }

    private fun safeToConfigure(): Boolean = started && resumed && snapshot?.let {
        it.vehicleAwake && it.uxRestricted == false &&
            System.currentTimeMillis() - it.observedAtMillis in 0..30_000 && it.observedAtMillis >= sourceStartedAt
    } == true
    private fun allowed(): Boolean = safeToConfigure() && status == SessionStatus.CHARGING
    private fun refreshMediaGate() {
        media.setPlaybackAllowed(tab == 1 && allowed() && remainingWindowSeconds() > 120 && active == null && recovery == null,
            if (remainingWindowSeconds() <= 120) "마무리할 시간이에요. 새 대기 시간을 설정해 주세요." else statusText())
    }
    private fun startWindowIfNeeded() {
        if (windowEndElapsed == 0L) {
            windowEndElapsed = SystemClock.elapsedRealtime() + windowMinutes * 60_000L
            windowEndWall = System.currentTimeMillis() + windowMinutes * 60_000L
            lastWindowHasBudget = true
            saveCheckpoint()
            updateLabels()
        }
    }
    private fun remainingWindowSeconds(): Int = if (windowEndElapsed == 0L) windowMinutes * 60 else
        ((windowEndElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000).toInt()
    private fun windowContentKey(): String {
        val remaining = remainingWindowSeconds()
        val phase = when { remaining == 0 -> "ended"; remaining <= ActivityRecommender.DEFAULT_RESERVE_SECONDS -> "finishing"; else -> "available" }
        val eligible = ActivityCatalog.all.filter { it.durationSeconds + ActivityRecommender.DEFAULT_RESERVE_SECONDS <= remaining }
            .joinToString(",") { it.id }
        return if (tab == 1) phase else "$phase/$eligible"
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
        val previousLeftScroll = if (preserveScroll) leftScroll?.scrollY ?: 0 else 0
        val previousContentScroll = if (preserveScroll) contentScroll?.scrollY ?: 0 else 0
        lastWindowContentKey = windowContentKey()
        val preserveMedia = tab == 1 && allowed()
        if (preserveMedia) media.beginHostReparent()
        try {
            (media.parent as? ViewGroup)?.removeView(media)
            media.applyPalette(palette)
            timerLabel = null; diagnosticsLabel = null; chargeGauge = null
            timeButtons.clear()
            window.statusBarColor = paper
            window.navigationBarColor = palette.surface
            val root = column().apply {
                setBackgroundColor(paper)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            val top = row().apply { setPadding(dp(28), dp(12), dp(28), dp(12)); gravity = Gravity.CENTER_VERTICAL }
            top.addView(icon(AppIcon.SPARKLES, palette.text, 32), LinearLayout.LayoutParams(dp(40), dp(40)))
            top.addView(label("빈틈의실", 28f, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            val compactHeader = resources.configuration.screenWidthDp < 650
            top.addView(badge(if (demoMode) (if (compactHeader) "시연" else "시연 · 모의 데이터") else "차량 연결", if (demoMode) palette.warningSurface else palette.surfaceAlt,
                if (demoMode) palette.warning else muted))
            top.addView(iconButton(if (palette.dark) "라이트 모드" else "다크 모드", if (palette.dark) AppIcon.SUN else AppIcon.MOON) {
                changeTheme(if (palette.dark) ThemeMode.LIGHT else ThemeMode.DARK)
            }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(16) })
            root.addView(top)
            val wide = resources.configuration.screenWidthDp >= 1100
            val body = if (wide) row() else column()
            val left = buildLeft(!wide)
            val right = buildRight()
            if (wide) {
                val sideWidth = if (resources.configuration.screenWidthDp >= 1500) 350 else 320
                body.setPadding(dp(16), 0, dp(16), dp(12))
                leftScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(left) }
                contentScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(right) }
                body.addView(leftScroll,
                    LinearLayout.LayoutParams(dp(sideWidth), -1))
                body.addView(contentScroll,
                    LinearLayout.LayoutParams(0, -1, 1f))
                root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                body.setPadding(dp(16), 0, dp(16), dp(12))
                body.addView(left); body.addView(right)
                leftScroll = null
                contentScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
                root.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            val dock = row().apply {
                gravity = Gravity.CENTER; setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(palette.surface)
            }
            val tabs = listOf("대시보드" to AppIcon.DASHBOARD, "미디어" to AppIcon.PLAY, "설정" to AppIcon.SETTINGS)
            tabs.forEachIndexed { index, (name, glyph) ->
                dock.addView(button(if (compactHeader && index == 0) "홈" else name, tab == index) {
                    if (tab != index) {
                        media.pauseForHost()
                        if (active != null && index != 0) interruptActivity("활동을 중단했어요. 다른 시간을 골라 보세요.")
                        tab = index; render()
                    }
                }.apply {
                    minHeight = dp(72)
                    if (compactHeader) {
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                        setCompoundDrawablesRelativeWithIntrinsicBounds(null, IconDrawable(glyph, if (tab == index) palette.onAccent else muted, dp(26)), null, null)
                        compoundDrawablePadding = dp(6)
                    } else withIcon(glyph, if (tab == index) palette.onAccent else muted)
                    contentDescription = "$name${if (tab == index) ", 선택됨" else ""}"
                    isSelected = tab == index
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
            }
            root.addView(dock)
            setContentView(root)
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

    private fun buildLeft(compact: Boolean): LinearLayout = column().apply {
        val largeText = resources.configuration.fontScale >= 1.3f
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = rounded(palette.surface, 28)
        addView(sectionTitle("지금의 충전", AppIcon.BATTERY))
        if (compact || largeText) {
            val stacked = largeText || resources.configuration.screenWidthDp < 450
            val summary = (if (stacked) column() else row()).apply {
                gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            }
            socLabel = label("—", if (largeText) 48f else 56f, true).apply { setSingleLine(true) }.also {
                summary.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(20) })
            }
            statusLabel = label(statusText(), 24f).also {
                summary.addView(it, if (stacked) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(summary)
        } else {
            val gaugeFrame = FrameLayout(this@MainActivity)
            chargeGauge = ChargeGaugeView(this@MainActivity).also { gaugeFrame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
            val number = column().apply { gravity = Gravity.CENTER }
            socLabel = label("—", 64f, true).apply {
                gravity = Gravity.CENTER
                setSingleLine(true)
                setPadding(0, dp(4), 0, dp(4))
                setAutoSizeTextTypeUniformWithConfiguration(32, 64, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
            }.also { number.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            number.addView(label("현재 배터리", 23f).apply {
                setTextColor(muted); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
            gaugeFrame.addView(number, FrameLayout.LayoutParams(dp(168), -2, Gravity.CENTER))
            addView(gaugeFrame, LinearLayout.LayoutParams(-1, dp(230)))
            statusLabel = label(statusText(), 24f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16)) }.also { addView(it) }
        }
        val timeSummary = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(12)); background = rounded(palette.surfaceAlt)
            addView(sectionTitle("나의 대기시간", AppIcon.CLOCK))
            windowLabel = label("", if (compact) 30f else 36f, true).also { addView(it) }
            addView(label("마무리 여유 2분 포함", 22f).apply { setTextColor(muted) })
        }
        addView(timeSummary, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        if (compact) addView(button(if (chargeControlsExpanded) "목표·시간 접기" else "목표·시간 설정") {
            chargeControlsExpanded = !chargeControlsExpanded; render()
        }.apply { withIcon(AppIcon.SETTINGS) })
        if (!compact || chargeControlsExpanded) {
            addView(label("알림 목표", 24f, true))
            val targets = if (largeText) column() else row()
            listOf(70, 80, 90).forEach { value ->
                targets.addView(button("$value%", target == value, safeToConfigure()) {
                    if (safeToConfigure() && value != target) {
                        target = value; session = ChargingSession(target); persistTarget()
                        snapshot?.let(::acceptSnapshot); render()
                    }
                }, (if (largeText) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            addView(targets)
            addView(label("사용할 시간", 24f, true).apply { setPadding(0, dp(24), 0, dp(10)) })
            (if (largeText) listOf(listOf(8), listOf(20), listOf(40), listOf(60)) else listOf(listOf(8, 20), listOf(40, 60))).forEach { pair ->
                val times = row()
                pair.forEach { minutes ->
                    times.addView(button("${minutes}분", windowMinutes == minutes, allowed() && windowEndElapsed == 0L) {
                        if (!allowed() || windowEndElapsed != 0L) return@button
                        windowMinutes = minutes; prefs.edit().putInt("windowMinutes", minutes).apply(); render()
                    }.also { timeButtons.add(it) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
                }
                addView(times)
            }
            addView(label("선택한 시간이며 충전 완료 예상시간은 아니에요.", 22f).apply { setTextColor(muted) })
        }
        if (status == SessionStatus.TARGET_REACHED || status == SessionStatus.INTERRUPTED) {
            addView(button("새 충전 세션", false, safeToConfigure()) {
                if (safeToConfigure()) {
                    interruptActivity("새 충전 세션을 시작했어요.")
                    recovery = null; completed = null; windowEndElapsed = 0; windowEndWall = 0
                    session = ChargingSession(target); persistTarget(); saveCheckpoint()
                    snapshot?.let(::acceptSnapshot); render()
                }
            }.apply { withIcon(AppIcon.RESET) })
        }
        resetWindowButton = button("대기시간 다시 설정", false, allowed()) {
            resetWaitingWindow()
        }.apply { withIcon(AppIcon.RESET) }.also { addView(it) }
    }

    private fun resetWaitingWindow(minutes: Int? = null) {
        if (!allowed()) return
        media.pauseForHost()
        interruptActivity(if (minutes == null) "새 대기시간을 선택해 주세요." else "${minutes}분에 맞는 활동을 골라 보세요.")
        recovery = null; completed = null; windowEndElapsed = 0; windowEndWall = 0
        if (minutes != null && minutes in listOf(8, 20, 40, 60)) {
            windowMinutes = minutes
            prefs.edit().putInt("windowMinutes", minutes).apply()
        }
        chargeControlsExpanded = minutes == null
        lastWindowHasBudget = true
        saveCheckpoint(); render()
    }

    private fun buildRight(): LinearLayout = column().apply {
        setPadding(dp(32), dp(24), dp(16), dp(28))
        when (tab) {
            1 -> {
                addView(sectionTitle("미디어", AppIcon.PLAY))
                addView(label("보고, 쉬고, 다시 출발.", 40f, true))
                addView(label("잠깐의 여유에 어울리는 콘텐츠", 24f).apply { setTextColor(muted) })
                addView(space(16))
                when {
                    !allowed() -> buildBlockedState(this)
                    remainingWindowSeconds() <= ActivityRecommender.DEFAULT_RESERVE_SECONDS -> buildWaitingWindowEnd(this)
                    else -> addView(media)
                }
            }
            2 -> buildSettings(this)
            else -> {
                addView(sectionTitle("대시보드", AppIcon.DASHBOARD))
                addView(label(if (active != null) "지금은, 나에게 집중." else "잠깐의 충전, 충분한 여유.", 40f, true))
                val showingEndState = allowed() && remainingWindowSeconds() <= ActivityRecommender.DEFAULT_RESERVE_SECONDS &&
                    active == null && recovery == null && completed == null
                if (message.isNotBlank() && !showingEndState) addView(label(message, 24f).apply { setTextColor(muted) })
                addView(space(12))
                when {
                    !allowed() -> buildBlockedState(this)
                    recovery != null -> {
                        addView(label("이전 활동이 남아 있어요", 30f, true))
                        addView(label("남은 시간을 확인한 뒤 직접 이어가 주세요. 자동으로 다시 시작하지 않아요.", 24f))
                        addView(button("남은 시간으로 이어하기", true) { resumeActivity() })
                        addView(button("이전 활동 끝내기") { recovery = null; saveCheckpoint(); render() })
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
            }.onFailure { message = "앱 설정을 열 수 없어요. 차량 설정에서 빈틈의실의 권한을 확인해 주세요."; render() }
        } else {
            prefs.edit().putBoolean("askedEnergyPermission", true).apply()
            requestPermissions(arrayOf("android.car.permission.CAR_ENERGY"), 700)
        }
    }

    private fun reconnectVehicle() {
        if (demoMode) return
        media.pauseForHost()
        interruptActivity("차량 연결을 다시 확인하고 있어요.")
        recovery = null
        source.stop()
        snapshot = null; status = SessionStatus.WAITING_FOR_DATA
        sourceStartedAt = System.currentTimeMillis()
        render()
        if (started) source.start { value -> runOnUiThread { if (started) acceptSnapshot(value) } }
    }

    private fun buildBlockedState(container: LinearLayout) {
        val parent = surfaceCard()
        parent.addView(sectionTitle("연결 상태", AppIcon.SHIELD))
        container.addView(parent)
        val missingPermission = !demoMode && !hasEnergyPermission() && status == SessionStatus.WAITING_FOR_DATA
        val heading = when {
            missingPermission -> "충전 상태를 읽을 수 있도록 허용해 주세요"
            status == SessionStatus.TARGET_REACHED -> "설정한 목표 잔량에 도달했어요"
            status == SessionStatus.INTERRUPTED -> "충전이 진행되고 있지 않아요"
            else -> statusText()
        }
        val description = when {
            missingPermission -> "배터리 잔량과 실제 충전 상태는 별도로 확인해요.\n아래 버튼에서 충전 상태 읽기를 허용하면 연결을 다시 확인합니다."
            status == SessionStatus.TARGET_REACHED -> "이번 세션의 ${target}% 목표에 도달했어요. 다음 충전을 시작할 때 새 세션을 열어 주세요."
            status == SessionStatus.INTERRUPTED -> "충전기 연결과 충전 시작 여부를 확인해 주세요. 충전 상태가 확인되면 활동을 열어요."
            status == SessionStatus.DRIVING_LOCKED -> "안전하게 정차하고 차량의 화면 사용 제한이 해제되면 다시 이용할 수 있어요."
            status == SessionStatus.SUSPENDED -> "화면이 다시 활성화되면 최신 차량 상태를 확인할게요."
            else -> "차량 데이터가 아직 준비되지 않았어요. 잠시 기다리거나 연결을 다시 확인해 주세요."
        }
        parent.addView(label(heading, 32f, true))
        parent.addView(label(description, 25f))
        if (missingPermission) parent.addView(button("충전 상태 읽기 허용", true) { requestEnergyPermission() })
        if (!demoMode && status in listOf(SessionStatus.WAITING_FOR_DATA, SessionStatus.INTERRUPTED)) {
            if (!missingPermission) parent.addView(button("차량 연결 다시 확인") { reconnectVehicle() })
            val value = snapshot
            parent.addView(label("배터리 잔량 ${if (value?.stateOfCharge != null) "확인됨" else "확인 중"} · " +
                "충전 상태 ${if (value?.charging != null) "확인됨" else "확인 중"} · " +
                "화면 제한 ${if (value?.uxRestricted != null) "확인됨" else "확인 중"}", 23f))
        }
        if (BuildConfig.DEBUG && !demoMode && status != SessionStatus.DRIVING_LOCKED && status != SessionStatus.SUSPENDED) {
            parent.addView(button("개발용 모의 충전으로 둘러보기") { switchSource() })
        }
        parent.addView(label("목표 알림은 차량의 충전을 제어하지 않아요.", 23f))
    }

    private fun buildRecommendations(parent: LinearLayout) {
        val profile = loadProfile(bucket()).let { if (learningOn) it else it.copy(feedback = emptyMap()) }
        val choices = ActivityRecommender.recommend(remainingWindowSeconds(), status, profile)
        if (choices.isEmpty()) {
            buildWaitingWindowEnd(parent)
            return
        }
        parent.addView(label("남은 시간에 맞춘 추천", 26f, true))
        parent.addView(label("한 가지를 골라 가볍게 시작해 보세요.", 24f).apply { setTextColor(muted) })
        val width = resources.configuration.screenWidthDp
        val contentWidth = if (width >= 1100) width - (if (width >= 1500) 350 else 320) - 80 else width - 80
        val columns = when { contentWidth >= 1000 -> 3; contentWidth >= 660 -> 2; else -> 1 }
        choices.chunked(columns).forEachIndexed { rowIndex, choicesRow ->
            val cards = row()
            choicesRow.forEachIndexed { index, choice ->
                val featured = rowIndex == 0 && index == 0
                val color = if (featured) palette.onAccent else ink
                val glyph = activityIcon(choice.activity.kind)
                val card = column().apply {
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                    minimumHeight = dp(324)
                    background = rounded(if (featured) palette.accent else palette.surface, 26)
                    val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
                    heading.addView(icon(glyph, color, 34), LinearLayout.LayoutParams(dp(46), dp(46)))
                    heading.addView(label("${choice.activity.durationSeconds / 60}분", 24f, true).apply {
                        setTextColor(color); gravity = Gravity.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(heading)
                    addView(space(16))
                    addView(label(choice.activity.title.substringAfter("분 "), 30f, true).apply { setTextColor(color) })
                    val shortDescription = when (choice.activity.kind) {
                        ActivityKind.REST -> "화면에서 눈을 떼고\n잠시 편안하게 쉬어요."
                        ActivityKind.REFLECTION -> "복잡한 생각을 정리하고\n다음 한 걸음을 골라요."
                        ActivityKind.QUIZ -> "다섯 문제로 가볍게\n머리를 깨워 보세요."
                    }
                    addView(label(shortDescription, 24f).apply { setTextColor(if (featured) color else muted) },
                        LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(20) })
                    addView(button("시작하기", !featured) { startActivity(choice.activity) }.apply {
                        withIcon(AppIcon.ARROW_RIGHT, if (!featured) palette.onAccent else ink)
                        contentDescription = "${choice.activity.title} 시작하기"
                    })
                }
                cards.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) })
            }
            repeat(columns - choicesRow.size) {
                cards.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) })
            }
            parent.addView(cards, LinearLayout.LayoutParams(-1, -2))
        }
        parent.addView(space(16))
        parent.addView(sectionTitle("언제든 멈춰도 괜찮아요", AppIcon.SHIELD))
        parent.addView(label("활동 뒤에는 마무리할 여유를 남겨둘게요.", 23f).apply { setTextColor(muted) })
    }

    private fun buildWaitingWindowEnd(parent: LinearLayout) {
        val remaining = remainingWindowSeconds()
        parent.addView(surfaceCard().apply {
            addView(sectionTitle("대기시간", AppIcon.CLOCK))
            addView(label(if (remaining == 0) "이번 대기시간이 끝났어요." else "잠시 마무리할 시간이에요.", 34f, true))
            addView(label(if (remaining == 0) "더 머무를 예정이라면 새 시간을 선택해 주세요."
                else "새 활동을 시작하기에는 남은 시간이 짧아요. 더 기다린다면 시간을 다시 정할 수 있어요.", 24f).apply { setTextColor(muted) })
            addView(space(16))
            addView(button("${windowMinutes}분 대기시간 새로 설정", true, allowed()) {
                resetWaitingWindow(windowMinutes)
            }.apply { withIcon(AppIcon.RESET, palette.onAccent) })
            addView(button("다른 시간 선택", false, allowed()) { resetWaitingWindow() }.apply { withIcon(AppIcon.CLOCK) })
            addView(label("새 시간을 정해도 차량의 충전 설정은 바뀌지 않아요.", 22f).apply { setTextColor(muted) })
        })
    }

    private fun startActivity(activity: OwnActivity) {
        if (!allowed() || ActivityRecommender.recommend(remainingWindowSeconds(), status).none { it.activity.id == activity.id }) return
        startWindowIfNeeded()
        active = Run(activity.id, UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis(), bucket())
        completed = null; quizIndex = 0; quizAnswer = null
        message = "언제든 중단할 수 있어요. 중단은 취향 평가에 반영하지 않아요."
        saveCheckpoint(); render()
    }

    private fun buildActive(container: LinearLayout, run: Run) {
        val parent = surfaceCard()
        container.addView(parent)
        val activity = ActivityCatalog.find(run.activityId) ?: return
        parent.addView(label(activity.title, 32f, true))
        parent.addView(sectionTitle("남은 활동 시간", AppIcon.CLOCK))
        timerLabel = label(clock(remainingActivitySeconds(run)), 64f, true).also { parent.addView(it) }
        if (activity.kind == ActivityKind.QUIZ && quizIndex < activity.quizQuestions.size) {
            val question = activity.quizQuestions[quizIndex]
            parent.addView(label("${quizIndex + 1} / ${activity.quizQuestions.size}   ${question.prompt}", 28f, true))
            question.choices.forEachIndexed { index, answer -> parent.addView(button(answer, quizAnswer == index) {
                if (allowed() && quizAnswer == null) { quizAnswer = index; render() }
            }) }
            quizAnswer?.let { chosen ->
                parent.addView(label((if (chosen == question.correctAnswerIndex) "맞았어요. " else "괜찮아요. ") + question.explanation, 25f))
                parent.addView(button("다음 문제", true) { if (allowed()) { quizIndex++; quizAnswer = null; saveCheckpoint(); render() } })
            }
        } else activity.instructions.forEach { parent.addView(label("• $it", 26f)) }
        parent.addView(button("활동 완료", true) {
            if (allowed()) { completed = active; active = null; message = "수고했어요. 평가는 선택이에요."; saveCheckpoint(); render() }
        })
        parent.addView(button("중단하고 다른 활동 보기") { interruptActivity("활동을 중단했어요. 취향에는 반영하지 않아요."); render() })
    }

    private fun buildFeedback(container: LinearLayout, run: Run) {
        val parent = surfaceCard()
        parent.addView(sectionTitle("나의 취향", AppIcon.SPARKLES))
        container.addView(parent)
        parent.addView(label("어떤 시간이었나요?", 32f, true))
        parent.addView(label("내용에 대한 평가만 다음 같은 시간 구간의 추천에 반영해요.", 24f))
        listOf("좋았어요" to ActivityRating.LIKE, "내용이 아쉬웠어요" to ActivityRating.DISLIKE).forEach { (title, rating) ->
            parent.addView(button(title, rating == ActivityRating.LIKE, learningOn && allowed()) {
                if (allowed() && learningOn) {
                    val result = ActivityFeedbackReducer.apply(loadProfile(run.bucket), OwnActivityFeedback(
                        "rating-${run.attemptId}", run.attemptId, run.activityId, rating, true))
                    saveProfile(run.bucket, result.profile); completed = null
                    message = if (result.applied) "이 시간 구간의 취향에 반영했어요." else "이미 반영한 평가예요."
                    render()
                }
            })
        }
        if (!learningOn) parent.addView(label("취향 학습이 꺼져 있어 평가를 저장하지 않아요.", 24f))
        parent.addView(button("평가 없이 돌아가기") { completed = null; render() })
    }

    private fun buildSettings(parent: LinearLayout) {
        parent.addView(sectionTitle("설정", AppIcon.SETTINGS))
        parent.addView(label("나에게 편안한 방식으로.", 40f, true))
        parent.addView(label("화면과 취향을 이 기기에 맞춰 보세요.", 24f).apply { setTextColor(muted) })
        parent.addView(space(16))
        val appearance = surfaceCard()
        appearance.addView(sectionTitle("화면 모드", AppIcon.SUN))
        appearance.addView(label("시스템 설정을 따르거나 직접 선택하세요.", 23f).apply { setTextColor(muted) })
        val modes = if (resources.configuration.screenWidthDp < 650) column() else row()
        listOf(ThemeMode.SYSTEM to ("자동" to AppIcon.SYSTEM), ThemeMode.LIGHT to ("라이트" to AppIcon.SUN),
            ThemeMode.DARK to ("다크" to AppIcon.MOON)).forEach { (mode, item) ->
            val (name, glyph) = item
            modes.addView(button(name, mode == themeMode) { changeTheme(mode) }.apply {
                withIcon(glyph, if (mode == themeMode) palette.onAccent else ink)
                contentDescription = "$name 화면 모드${if (mode == themeMode) ", 선택됨" else ""}"
                isSelected = mode == themeMode
            }, if (modes.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
                else LinearLayout.LayoutParams(-1, -2))
        }
        appearance.addView(modes); parent.addView(appearance)
        val preferences = surfaceCard()
        preferences.addView(sectionTitle("좋아하는 시간", AppIcon.SPARKLES))
        preferences.addView(label("${windowMinutes}분 대기할 때 선호하는 활동 · 여러 개 선택 가능", 23f).apply { setTextColor(muted) })
        val profile = loadProfile(bucket())
        ActivityKind.values().forEach { kind ->
            val name = when (kind) { ActivityKind.REST -> "눈 쉬기"; ActivityKind.REFLECTION -> "생각 정리"; ActivityKind.QUIZ -> "가벼운 퀴즈" }
            val selected = kind in profile.preferredKinds
            preferences.addView(button(if (selected) "$name · 선택됨" else name, selected, allowed()) {
                if (!allowed()) return@button
                val kinds = profile.preferredKinds.toMutableSet()
                if (!kinds.add(kind)) kinds.remove(kind)
                saveProfile(bucket(), profile.copy(preferredKinds = kinds)); render()
            }.apply { withIcon(activityIcon(kind), if (selected) palette.onAccent else ink) })
        }
        preferences.addView(button("취향 기억하기 · ${if (learningOn) "켜짐" else "꺼짐"}", learningOn, allowed()) {
            if (!allowed()) return@button
            learningOn = !learningOn; prefs.edit().putBoolean("learningOn", learningOn).apply(); render()
        }.apply { withIcon(AppIcon.CHECK, if (learningOn) palette.onAccent else ink) })
        preferences.addView(label("자체 활동에 남긴 평가만 이 기기에 저장해요.", 22f).apply { setTextColor(muted) })
        preferences.addView(button("이 시간 구간의 기록 초기화", false, allowed()) {
            if (!allowed()) return@button
            saveProfile(bucket(), ActivityProfile(preferredKinds = profile.preferredKinds))
            completed = null; message = "이 구간의 학습 기록을 지웠어요."; render()
        }.apply { withIcon(AppIcon.RESET) })
        if (!allowed()) preferences.addView(label("차량 상태를 확인한 뒤 취향을 바꿀 수 있어요.", 23f).apply { setTextColor(muted) })
        parent.addView(preferences)
        val connection = surfaceCard()
        connection.addView(sectionTitle("차량 연결", AppIcon.SHIELD))
        connection.addView(label(if (demoMode) "시연 중 · 실제 차량 데이터가 아니에요" else statusText(), 24f))
        if (!demoMode && !hasEnergyPermission()) connection.addView(button("충전 상태 읽기 허용", true) { requestEnergyPermission() })
        if (!demoMode) connection.addView(button("연결 다시 확인") { reconnectVehicle() }.apply { withIcon(AppIcon.RESET) })
        if (BuildConfig.DEBUG) {
            connection.addView(button(if (developerOptionsExpanded) "개발용 설정 접기" else "개발용 설정 펼치기") {
                developerOptionsExpanded = !developerOptionsExpanded; render()
            }.apply { withIcon(AppIcon.SETTINGS) })
            if (developerOptionsExpanded) {
                if (!demoMode) diagnosticsLabel = label((source as? PleosVehicleDataSource)?.diagnostics.orEmpty(), 22f).apply {
                    setTextColor(muted)
                }.also { connection.addView(it) }
                connection.addView(button(if (demoMode) "실제 Pleos 연결로 전환" else "모의 충전 시연 켜기", demoMode) { switchSource() })
                if (demoMode) {
                    listOf("충전 중 65%", "목표 도달", "목표 전 충전 중단", "주행 제한", "데이터 지연").forEachIndexed { index, text ->
                        connection.addView(button(text, demoScenario == index) {
                            session = ChargingSession(target); persistTarget()
                            demoScenario = index; prefs.edit().putInt("demoScenario", index).apply(); emitDemoScenario(); render()
                        })
                    }
                }
            }
        }
        parent.addView(connection)
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
        media.pauseForHost(); interruptActivity("데이터 모드를 바꿨어요.")
        recovery = null; completed = null; source.stop()
        demoMode = !demoMode; prefs.edit().putBoolean("demoMode", demoMode).apply()
        demoScenario = 0; prefs.edit().putInt("demoScenario", 0).apply()
        session = ChargingSession(target); persistTarget()
        snapshot = null; status = SessionStatus.WAITING_FOR_DATA
        sourceStartedAt = System.currentTimeMillis(); source = makeSource()
        source.start { value -> runOnUiThread { if (started) acceptSnapshot(value) } }
        if (demoMode) emitDemoScenario()
        saveCheckpoint(); render()
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
                "권한을 확인했어요. 새 충전 상태를 확인합니다." else "충전 상태 읽기가 허용되지 않아 활동을 열 수 없어요."
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
            recovery = null; message = "이전 활동의 시간이 지나 새로 이어갈 수 없어요."
        } else { active = run; recovery = null; message = "남은 시간으로 이어가요." }
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
                prefs.edit().remove("checkpoint").apply(); message = "시계·기기 상태가 바뀌어 이전 활동을 중단했어요."; return
            }
            windowEndElapsed = data.optLong("endElapsed")
            windowEndWall = data.optLong("endWall")
            quizIndex = data.optInt("quizIndex").coerceAtLeast(0)
            quizAnswer = data.optInt("quizAnswer", -1).takeIf { it >= 0 }
            data.optJSONObject("run")?.let { item ->
                if (ActivityCatalog.find(item.getString("id")) != null) recovery = Run(item.getString("id"),
                    item.getString("attempt"), item.getLong("elapsed"), item.getLong("wall"), item.getString("bucket"))
            }
        } catch (_: Exception) { prefs.edit().remove("checkpoint").apply(); message = "이전 활동을 복구할 수 없어 중단했어요." }
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
        timeButtons.forEach { it.isEnabled = allowed() && windowEndElapsed == 0L }
        resetWindowButton?.apply {
            visibility = if (windowEndElapsed == 0L) View.GONE else View.VISIBLE
            isEnabled = allowed()
        }
        val usable = snapshot?.takeIf { System.currentTimeMillis() - it.observedAtMillis in 0..30_000 && it.observedAtMillis >= sourceStartedAt }
        val percent = usable?.stateOfCharge?.takeIf { it in 0..100 }
        socLabel?.text = percent?.let { "$it%" } ?: "—"
        chargeGauge?.update(percent, palette)
        statusLabel?.text = statusText()
        diagnosticsLabel?.text = (source as? PleosVehicleDataSource)?.diagnostics.orEmpty()
        windowLabel?.text = if (windowEndElapsed == 0L) "${windowMinutes}분" else clock(remainingWindowSeconds())
        active?.let { run ->
            val left = remainingActivitySeconds(run)
            timerLabel?.textSize = if (left == 0) 28f else 64f
            timerLabel?.text = if (left == 0) "시간이 되었어요. 완료를 확인해 주세요." else clock(left)
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
