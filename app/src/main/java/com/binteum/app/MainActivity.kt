package com.binteum.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.binteum.core.*

/** Buildable concept screen. It deliberately requests no vehicle permissions. */
class MainActivity : Activity() {
    private lateinit var source: DemoVehicleDataSource
    private var target = 80
    private var session = ChargingSession(target)
    private lateinit var statusView: TextView
    private lateinit var actionView: TextView
    private lateinit var selectionView: TextView
    private var lastSnapshot: VehicleSnapshot? = null
    private val handler = Handler(Looper.getMainLooper())
    private val freshnessCheck = object : Runnable {
        override fun run() {
            lastSnapshot?.let(::show)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = savedInstanceState?.getInt("target", 80) ?: 80
        session = ChargingSession(target, checkpoint = savedInstanceState?.let {
            SessionCheckpoint(target, it.getBoolean("reached"), it.getBoolean("notified"))
        })
        lastSnapshot = savedInstanceState?.getBundle("snapshot")?.let {
            VehicleSnapshot(
                if (it.containsKey("soc")) it.getInt("soc") else null,
                if (it.containsKey("charging")) it.getBoolean("charging") else null,
                if (it.containsKey("restricted")) it.getBoolean("restricted") else null,
                it.getBoolean("awake"), it.getLong("observed"),
            )
        }
        source = DemoVehicleDataSource(lastSnapshot)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(244, 247, 243))
        }
        fun label(value: String, size: Float = 20f): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(25, 48, 39)); setPadding(0, 10, 0, 10)
            container.addView(this)
        }
        label("빈틈의실", 34f)
        label("충전하는 동안, 나를 위한 짧은 시간", 22f)
        label("시연 모드 · 실제 차량과 연결되어 있지 않습니다", 16f)
        selectionView = label(getString(R.string.target_summary, target))
        statusView = label("충전 상태를 확인합니다", 28f)
        actionView = label("")
        fun button(value: String, action: () -> Unit) {
            container.addView(Button(this).apply {
                text = value; minHeight = 64; isAllCaps = false; setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        label("목표 잔량 선택")
        listOf(70, 80, 90).forEach { value ->
            button("${value}%에서 알려주세요") {
                if (target == value) return@button
                target = value; session = ChargingSession(target)
                selectionView.text = getString(R.string.target_summary, target)
                lastSnapshot?.let(::show)
            }
        }
        label("시연 상황 선택", 18f)
        button("새 시연 세션 시작 · 충전 중 65%") { session = ChargingSession(target); source.emit(65) }
        button("목표 잔량 도달") { source.emit(target, charging = false) }
        button("새 시연 · 목표 전에 충전 중단") { session = ChargingSession(target); source.emit(65, charging = false) }
        button("주행 제한 활성화") { source.emit(65, uxRestricted = true) }
        button("차량 상태 갱신 끊김") { source.emit(65, stale = true) }
        button("차량 전원 휴면 상태") { source.emit(65, awake = false) }
        setContentView(ScrollView(this).apply { addView(container) })
    }

    override fun onStart() { super.onStart(); source.start(::show); handler.post(freshnessCheck) }
    override fun onStop() { handler.removeCallbacks(freshnessCheck); source.stop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("target", target)
        val checkpoint = session.checkpoint()
        outState.putBoolean("reached", checkpoint.targetReached)
        outState.putBoolean("notified", checkpoint.notificationDelivered)
        lastSnapshot?.let { snapshot ->
            outState.putBundle("snapshot", Bundle().apply {
                snapshot.stateOfCharge?.let { putInt("soc", it) }
                snapshot.charging?.let { putBoolean("charging", it) }
                snapshot.uxRestricted?.let { putBoolean("restricted", it) }
                putBoolean("awake", snapshot.vehicleAwake)
                putLong("observed", snapshot.observedAtMillis)
            })
        }
        super.onSaveInstanceState(outState)
    }

    private fun show(snapshot: VehicleSnapshot) {
        lastSnapshot = snapshot
        val result = session.update(snapshot, System.currentTimeMillis())
        statusView.text = when (result.status) {
            SessionStatus.CHARGING -> "현재 ${snapshot.stateOfCharge}% · 목표 ${target}%"
            SessionStatus.TARGET_REACHED -> "목표 잔량에 도달했어요"
            SessionStatus.INTERRUPTED -> "목표 전에 충전이 멈췄어요"
            SessionStatus.DRIVING_LOCKED -> "주행 중에는 활동 추천을 잠시 멈춰요"
            SessionStatus.SUSPENDED -> "차량이 쉬고 있어요 · 감시가 중단됐어요"
            SessionStatus.WAITING_FOR_DATA -> "차량 상태를 다시 확인해야 해요"
        }
        val activities = listOf(ActivityOption("5분 눈 휴식", 5), ActivityOption("10분 독서", 10))
            .filter { TimeBudget.fits(it, 20) }
        actionView.text = when (result.status) {
            SessionStatus.CHARGING -> "차 안에서 할 수 있어요\n" + activities.joinToString("\n") { "• ${it.title}" } +
                "\n\n마무리 2분을 남겨두었습니다. 20분은 직접 정한 시간이며 충전 완료 예측이 아닙니다."
            SessionStatus.TARGET_REACHED -> "다음 이용자를 위해 차량 이동을 준비해주세요."
            SessionStatus.INTERRUPTED -> "충전기와 연결 상태를 확인해주세요. 목표 잔량 도달과 다른 상태입니다."
            else -> "상태가 확인되면 다시 안내합니다."
        }
        if (result.notifyTargetReached) statusView.announceForAccessibility(statusView.text)
    }
}
