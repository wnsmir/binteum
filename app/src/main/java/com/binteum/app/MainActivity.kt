package com.binteum.app

import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.binteum.app.media.WebMediaPanel
import com.binteum.app.vehicle.PleosVehicleDataSource
import com.binteum.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

class MainActivity : Activity() {
    private val ink = Color.rgb(27, 43, 38)
    private val muted = Color.rgb(91, 108, 101)
    private val mint = Color.rgb(219, 239, 229)
    private val paper = Color.rgb(249, 250, 248)
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
    private val timeButtons = mutableListOf<Button>()
    private var resetWindowButton: Button? = null
    private var lastWindowHasBudget = true
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
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun makeSource(): VehicleDataSource = if (demoMode) DemoVehicleDataSource() else PleosVehicleDataSource(this)

    private fun acceptSnapshot(value: VehicleSnapshot) {
        snapshot = value
        val previous = status
        val result = if (value.observedAtMillis < sourceStartedAt) SessionResult(SessionStatus.WAITING_FOR_DATA)
            else session.update(value, System.currentTimeMillis())
        status = result.status
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
    private fun remainingActivitySeconds(run: Run): Int {
        val duration = ActivityCatalog.find(run.activityId)?.durationSeconds ?: 0
        val elapsed = (SystemClock.elapsedRealtime() - run.startedElapsed).coerceAtLeast(0)
        return ((duration * 1000L - elapsed).coerceAtLeast(0).plus(999) / 1000).toInt()
    }
    private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)
    private fun bucket() = when (windowMinutes) { 8 -> "short"; 20 -> "medium"; 40 -> "long"; else -> "extended" }

    private fun render() {
        if (!::media.isInitialized) return
        val oldParent = media.parent as? ViewGroup
        oldParent?.removeView(media)
        timerLabel = null
        diagnosticsLabel = null
        timeButtons.clear()
        val root = column().apply { setBackgroundColor(paper) }
        val top = row().apply { setPadding(dp(28), dp(16), dp(28), dp(12)); gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("빈틈의실", 30f, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label(if (demoMode) "시연 모드 · 실제 차량 데이터 아님" else "PLEOS CONNECT · 차량 상태 읽기", 23f).apply {
            setTextColor(if (demoMode) Color.rgb(146, 75, 25) else muted)
        })
        root.addView(top)
        val wide = resources.configuration.screenWidthDp >= 900
        val body = if (wide) row() else column()
        val left = buildLeft()
        val right = buildRight()
        if (wide) {
            body.addView(ScrollView(this).apply { isFillViewport = true; addView(left) }, LinearLayout.LayoutParams(0, -1, 29f))
            body.addView(ScrollView(this).apply { isFillViewport = true; addView(right) }, LinearLayout.LayoutParams(0, -1, 71f))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            body.addView(left); body.addView(right)
            root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val tabs = row().apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(Color.WHITE) }
        listOf("충전의 틈", "웹·미디어", "나의 설정").forEachIndexed { index, name ->
            tabs.addView(button(name, tab == index) {
                if (tab != index) {
                    media.pauseForHost()
                    if (active != null && index != 0) interruptActivity("화면을 바꿔 활동을 중단했어요.")
                    tab = index; render()
                }
            }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        root.addView(tabs)
        setContentView(root)
        refreshMediaGate()
        updateLabels()
    }

    private fun buildLeft(): LinearLayout = column().apply {
        setPadding(dp(32), dp(28), dp(32), dp(28))
        setBackgroundColor(Color.rgb(239, 242, 239))
        addView(label("지금의 충전", 24f))
        socLabel = label("—", 64f, true).also { addView(it) }
        statusLabel = label(statusText(), 25f).also { addView(it) }
        addView(CarSilhouetteView(this@MainActivity), LinearLayout.LayoutParams(-1, dp(180)))
        addView(label("알림 받을 목표", 24f, true))
        val targets = row()
        listOf(70, 80, 90).forEach { value -> targets.addView(button("$value%", target == value, safeToConfigure()) {
            if (safeToConfigure() && value != target) {
                target = value; session = ChargingSession(target); persistTarget()
                snapshot?.let(::acceptSnapshot); render()
            }
        }, LinearLayout.LayoutParams(0, dp(72), 1f)) }
        addView(targets)
        if (status == SessionStatus.TARGET_REACHED || status == SessionStatus.INTERRUPTED) {
            addView(button("새 충전 세션 시작", false, safeToConfigure()) {
                if (safeToConfigure()) {
                    interruptActivity("새 충전 세션을 시작했어요.")
                    recovery = null; completed = null; windowEndElapsed = 0; windowEndWall = 0
                    session = ChargingSession(target); persistTarget(); saveCheckpoint()
                    snapshot?.let(::acceptSnapshot); render()
                }
            })
        }
        addView(label("내가 정한 대기시간", 24f, true).apply { setPadding(0, dp(24), 0, dp(8)) })
        val times = row()
        listOf(8, 20, 40, 60).forEach { minutes -> times.addView(button("${minutes}분", windowMinutes == minutes,
            allowed() && windowEndElapsed == 0L) {
            if (!allowed() || windowEndElapsed != 0L) return@button
            windowMinutes = minutes; prefs.edit().putInt("windowMinutes", minutes).apply(); render()
        }.also { timeButtons.add(it) }, LinearLayout.LayoutParams(0, dp(72), 1f)) }
        addView(times)
        windowLabel = label("", 25f, true).also { addView(it) }
        addView(label("충전 완료 예상시간이 아니에요.\n마무리 2분을 남겨둘게요.", 22f))
        resetWindowButton = button("새 대기 시간 설정", false, allowed()) {
            if (!allowed()) return@button
            media.pauseForHost()
            interruptActivity("새 대기 시간을 선택해 주세요.")
            recovery = null; completed = null; windowEndElapsed = 0; windowEndWall = 0
            saveCheckpoint(); render()
        }.also { addView(it) }
    }

    private fun buildRight(): LinearLayout = column().apply {
        setPadding(dp(40), dp(24), dp(40), dp(28))
        when (tab) {
            1 -> {
                addView(label("가볍게 보고, 제때 쉬어요", 38f, true))
                addView(label("공식 예제 영상과 자체 웹 활동 · 재생 이력으로 취향을 학습하지 않아요", 24f))
                if (allowed()) addView(media) else buildBlockedState(this)
            }
            2 -> buildSettings(this)
            else -> {
                addView(label(if (active != null) "지금은 나를 위한 시간" else "이 틈에, 무엇을 할까요?", 38f, true))
                addView(label(message, 24f).apply { setTextColor(muted) })
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

    private fun buildBlockedState(parent: LinearLayout) {
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
        parent.addView(label("차 안에서 편안하게 · 준비된 자체 활동", 25f))
        val cards = if (resources.configuration.screenWidthDp >= 1200) row() else column()
        choices.forEachIndexed { index, choice ->
            val card = column().apply {
                setPadding(dp(28), dp(28), dp(28), dp(24)); background = rounded(if (index == 0) mint else Color.WHITE)
                addView(label("0${index + 1}   /   ${choice.activity.durationSeconds / 60} MIN", 23f))
                addView(label(choice.activity.title, 32f, true))
                addView(label(choice.activity.instructions.first(), 24f))
                addView(label(choice.reason, 22f).apply { setTextColor(muted) })
                addView(button("시작하기  →", index == 0) { startActivity(choice.activity) })
            }
            val lp = if (cards.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f)
                else LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(dp(7), dp(16), dp(7), dp(10)); cards.addView(card, lp)
        }
        parent.addView(cards)
        if (choices.isEmpty()) parent.addView(label("남은 시간에는 새 활동보다 마무리를 준비해 주세요.", 28f, true))
        parent.addView(label("산책·간식 추천은 도보 경로와 휴대폰 복귀 알림을 검증한 뒤 제공할 예정이에요.", 23f))
    }

    private fun startActivity(activity: OwnActivity) {
        if (!allowed() || ActivityRecommender.recommend(remainingWindowSeconds(), status).none { it.activity.id == activity.id }) return
        startWindowIfNeeded()
        active = Run(activity.id, UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis(), bucket())
        completed = null; quizIndex = 0; quizAnswer = null
        message = "언제든 중단할 수 있어요. 중단은 취향 평가에 반영하지 않아요."
        saveCheckpoint(); render()
    }

    private fun buildActive(parent: LinearLayout, run: Run) {
        val activity = ActivityCatalog.find(run.activityId) ?: return
        parent.addView(label(activity.title, 32f, true))
        timerLabel = label(clock(remainingActivitySeconds(run)), 54f, true).also { parent.addView(it) }
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

    private fun buildFeedback(parent: LinearLayout, run: Run) {
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
        parent.addView(label("나에게 맞는 틈", 38f, true))
        if (!demoMode && !hasEnergyPermission()) {
            parent.addView(button("충전 상태 읽기 허용") { requestEnergyPermission() })
        }
        parent.addView(label("${windowMinutes}분 구간의 직접 선호 · 이 기기에만 저장", 24f))
        val profile = loadProfile(bucket())
        ActivityKind.values().forEach { kind ->
            val name = when (kind) { ActivityKind.REST -> "눈 쉬기"; ActivityKind.REFLECTION -> "생각 정리"; ActivityKind.QUIZ -> "가벼운 퀴즈" }
            parent.addView(button((if (kind in profile.preferredKinds) "✓ " else "") + name, kind in profile.preferredKinds, allowed()) {
                val kinds = profile.preferredKinds.toMutableSet()
                if (!kinds.add(kind)) kinds.remove(kind)
                saveProfile(bucket(), profile.copy(preferredKinds = kinds)); render()
            })
        }
        parent.addView(button("자체 활동 취향 학습: ${if (learningOn) "켜짐" else "꺼짐"}", learningOn, allowed()) {
            learningOn = !learningOn; prefs.edit().putBoolean("learningOn", learningOn).apply(); render()
        })
        parent.addView(button("이 구간 학습 기록 초기화", false, allowed()) {
            saveProfile(bucket(), ActivityProfile(preferredKinds = profile.preferredKinds))
            completed = null; message = "이 구간의 학습 기록을 지웠어요."; render()
        })
        if (!allowed()) parent.addView(label("선호 변경은 차량·안전 상태가 확인된 뒤 가능해요.", 24f))
        if (BuildConfig.DEBUG) {
            parent.addView(label("개발용 시연 설정", 26f, true))
            if (!demoMode) {
                parent.addView(button("차량 연결 다시 확인") { reconnectVehicle() })
                diagnosticsLabel = label((source as? PleosVehicleDataSource)?.diagnostics.orEmpty(), 22f).also { parent.addView(it) }
            }
            parent.addView(button(if (demoMode) "실제 Pleos 연결로 전환" else "모의 데이터 시연 켜기", demoMode) { switchSource() })
            if (demoMode) {
                listOf("충전 중 65%", "목표 도달", "목표 전 충전 중단", "주행 제한", "데이터 지연").forEachIndexed { index, text ->
                    parent.addView(button(text) {
                        session = ChargingSession(target); persistTarget()
                        demoScenario = index
                        prefs.edit().putInt("demoScenario", index).apply()
                        emitDemoScenario()
                    })
                }
            }
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
        socLabel?.text = usable?.stateOfCharge?.takeIf { it in 0..100 }?.let { "$it%" } ?: "—"
        statusLabel?.text = statusText()
        diagnosticsLabel?.text = (source as? PleosVehicleDataSource)?.diagnostics.orEmpty()
        windowLabel?.text = if (windowEndElapsed == 0L) "선택한 시간 ${windowMinutes}분" else "대기시간 ${clock(remainingWindowSeconds())}"
        active?.let { run ->
            val left = remainingActivitySeconds(run)
            timerLabel?.text = if (left == 0) "시간이 되었어요 · 완료를 확인해 주세요" else clock(left)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat() }
    private fun label(value: String, size: Float = 24f, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ink); setPadding(0, dp(8), 0, dp(8))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, selected: Boolean = false, enabled: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 24f; isAllCaps = false; minHeight = dp(64); isEnabled = enabled
        setTextColor(ink); background = rounded(if (selected) mint else Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
    }
}
