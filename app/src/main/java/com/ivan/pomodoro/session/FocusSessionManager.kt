package com.ivan.pomodoro.session

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.data.FocusRepository
import com.ivan.pomodoro.data.entity.FocusPlan
import com.ivan.pomodoro.data.entity.FocusSession
import com.ivan.pomodoro.service.FocusSessionService
import com.ivan.pomodoro.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局专注会话控制器：以单调时钟计时，负责完成/失败结算与前台服务生命周期。
 *
 * 番茄规则：
 * - 只有倒计时完整走完，才记录为 1 个番茄（status = COMPLETED）。
 * - 手动结束 -> ABANDONED，不计番茄。
 * - 摄像头监控判定持续分心 -> INVALID，不计番茄。
 */
object FocusSessionManager {

    private const val TAG = "FocusSessionManager"
    private const val TICK_INTERVAL = 200L

    /** 监控启动后的预热时间，期间不累计分心 */
    private const val MONITOR_WARMUP_MS = 3000L

    /** 检测结果超过该时长未更新，视为监控失活，立即停止累计（避免某帧丢失导致误判） */
    private const val MONITOR_STALE_MS = 6000L

    /** 会话开始后的保护期，期间只提示不作废 */
    private const val SESSION_GRACE_MS = 60_000L

    /** 超标后的宽限期：期间回到镜头前即可继续，否则判定未通过 */
    private const val WARN_GRACE_MS = 15_000L

    /** 分心占比判定的最小统计窗口，避免刚开始就按比例误判 */
    private const val RATIO_MIN_WINDOW_MS = 120_000L

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saving = AtomicBoolean(false)

    private var appContext: Context? = null
    private var repository: FocusRepository? = null

    private var plannedMs = 0L
    private var accumulatedMs = 0L
    private var segmentStart = 0L
    private var lastTickAt = 0L
    private var sessionStartElapsed = 0L
    private var sessionStartWall = 0L
    private var totalDistractedMs = 0L
    private var continuousDistractedMs = 0L
    /** 监控有效期间的总时长，作为"分心占比"的分母，未判定/灰区时长也算在内 */
    private var trackedMs = 0L
    private var distractionCount = 0
    private var wasDistracted = false
    private var strictness = 1
    private var taskMode = FocusPlan.TASK_SCREEN
    private var lastMonitorUpdateAt = 0L
    private var currentSevere = false
    private var pendingAbandon = false
    private var pendingSince = 0L

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            repository = (context.applicationContext as PomodoroApp).repository
        }
    }

    val isActive: Boolean get() = _state.value.isActive

    // ---------------------------------------------------------------- 开始 / 控制

    fun startSession(plan: FocusPlan, monitorEnabled: Boolean, strictness: Int) {
        if (isActive) return
        val ctx = appContext ?: return

        plannedMs = plan.durationMinutes.coerceAtLeast(1) * 60_000L
        accumulatedMs = 0L
        segmentStart = SystemClock.elapsedRealtime()
        lastTickAt = segmentStart
        sessionStartElapsed = segmentStart
        sessionStartWall = System.currentTimeMillis()
        totalDistractedMs = 0L
        continuousDistractedMs = 0L
        trackedMs = 0L
        distractionCount = 0
        wasDistracted = false
        lastMonitorUpdateAt = 0L
        currentSevere = false
        pendingAbandon = false
        pendingSince = 0L
        this.strictness = strictness.coerceIn(0, 2)
        this.taskMode = plan.taskMode
        saving.set(false)

        _state.value = SessionUiState(
            phase = SessionPhase.RUNNING,
            planId = plan.id,
            planName = plan.name,
            planGoal = plan.goal,
            plannedSeconds = (plannedMs / 1000).toInt(),
            elapsedSeconds = 0,
            remainingSeconds = (plannedMs / 1000).toInt(),
            monitorEnabled = monitorEnabled,
            monitorActive = false,
            strictness = this.strictness,
            taskMode = this.taskMode,
            message = ""
        )

        handler.removeCallbacks(ticker)
        handler.post(ticker)

        runCatching {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, FocusSessionService::class.java)
            )
        }.onFailure { Log.w(TAG, "启动前台服务失败", it) }
    }

    fun pause() {
        val st = _state.value
        if (st.phase != SessionPhase.RUNNING) return
        val now = SystemClock.elapsedRealtime()
        accumulatedMs += (now - segmentStart).coerceAtLeast(0)
        segmentStart = now
        handler.removeCallbacks(ticker)
        _state.value = st.copy(
            phase = SessionPhase.PAUSED,
            elapsedSeconds = (accumulatedMs / 1000).toInt(),
            remainingSeconds = ((plannedMs - accumulatedMs) / 1000).toInt().coerceAtLeast(0)
        )
    }

    fun resume() {
        val st = _state.value
        if (st.phase != SessionPhase.PAUSED) return
        val now = SystemClock.elapsedRealtime()
        segmentStart = now
        lastTickAt = now
        _state.value = st.copy(phase = SessionPhase.RUNNING, pausedByBackground = false)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** 离开专注界面（切后台/锁屏）时暂停计时，避免"无人监控"的时长被算作专注 */
    fun pauseByBackground() {
        val st = _state.value
        if (st.phase != SessionPhase.RUNNING) return
        pause()
        _state.value = _state.value.copy(
            pausedByBackground = true,
            message = "已离开专注界面，计时已暂停"
        )
    }

    fun resumeFromBackground() {
        val st = _state.value
        if (st.phase != SessionPhase.PAUSED || !st.pausedByBackground) return
        _state.value = _state.value.copy(message = "")
        resume()
    }

    fun abandon() {
        if (!isActive) return
        finish(SessionPhase.ABANDONED, "已手动结束本次专注，不计入番茄")
    }

    /** 会话结束后清理状态（用户关闭结果页时调用） */
    fun reset() {
        if (isActive) return
        _state.value = SessionUiState()
    }

    // ---------------------------------------------------------------- 监控输入

    fun updateMonitor(
        active: Boolean,
        distracted: Boolean,
        severe: Boolean,
        reason: String,
        note: String = ""
    ) {
        val st = _state.value
        if (!st.isActive || !st.monitorEnabled) return
        lastMonitorUpdateAt = SystemClock.elapsedRealtime()
        currentSevere = distracted && severe
        _state.value = st.copy(
            monitorActive = active,
            instantDistracted = distracted,
            severeDistracted = distracted && severe,
            distractionReason = if (distracted) reason else "",
            monitorNote = if (distracted) "" else note,
            pausedByBackground = st.pausedByBackground
        )
    }

    fun markMonitorUnavailable(reason: String) {
        val st = _state.value
        if (!st.isActive) return
        _state.value = st.copy(
            monitorEnabled = false,
            monitorActive = false,
            instantDistracted = false,
            severeDistracted = false,
            message = reason
        )
    }

    // ---------------------------------------------------------------- 计时循环

    private val ticker = object : Runnable {
        override fun run() {
            val st = _state.value
            if (st.phase != SessionPhase.RUNNING) return
            val now = SystemClock.elapsedRealtime()
            val delta = (now - lastTickAt).coerceAtLeast(0)
            lastTickAt = now

            val elapsed = elapsedMs(now)

            // 检测结果是否新鲜：某帧检测失败/卡住时不再按旧结果累计，避免"卡在分心"导致误作废
            val monitorFresh = lastMonitorUpdateAt > 0L &&
                now - lastMonitorUpdateAt <= MONITOR_STALE_MS
            // 保护期（前 60 秒）内只提示、不累计、不作废
            val monitorCounting = st.monitorEnabled && st.monitorActive && monitorFresh &&
                now - sessionStartElapsed > MONITOR_WARMUP_MS && elapsed >= SESSION_GRACE_MS

            // 分母：监控有效期间的时长，灰区（既不算专注也不算分心）也计入，
            // 这样纸面任务长时间低头书写不会因为"没有分心证据"被稀释。
            if (monitorCounting) trackedMs += delta

            // 分心时长累计：仅"作废级"分心计入（严格模式下低头/闭眼也计入）
            val countable = st.instantDistracted && (currentSevere || strictness == 2)
            if (monitorCounting && countable) {
                totalDistractedMs += delta
                continuousDistractedMs += delta
                if (!wasDistracted) {
                    distractionCount++
                    wasDistracted = true
                }
            } else {
                continuousDistractedMs = 0
                wasDistracted = false
            }

            if (elapsed >= plannedMs) {
                _state.value = st.copy(
                    elapsedSeconds = (plannedMs / 1000).toInt(),
                    remainingSeconds = 0,
                    distractedSeconds = (totalDistractedMs / 1000).toInt(),
                    distractionCount = distractionCount
                )
                finish(SessionPhase.COMPLETED, "")
                return
            }

            // 超标后先给 15 秒宽限期：回到镜头前即可继续，避免短暂低头被判失败
            var pending = pendingAbandon
            var hint = _state.value.message
            if (strictness > 0 && monitorCounting) {
                val limits = thresholds()
                val ratioOver = limits.ratio > 0.0 && trackedMs >= RATIO_MIN_WINDOW_MS &&
                    totalDistractedMs.toDouble() / trackedMs >= limits.ratio
                val over = continuousDistractedMs >= limits.continuousMs ||
                    totalDistractedMs >= limits.totalMs || ratioOver
                if (over) {
                    if (!pending) {
                        pending = true
                        pendingSince = now
                        hint = if (taskMode == FocusPlan.TASK_PAPER) {
                            "请回到专注状态，15 秒内未恢复本次将不计入番茄"
                        } else {
                            "请回到镜头前，15 秒内未恢复本次将不计入番茄"
                        }
                    } else if (now - pendingSince >= WARN_GRACE_MS) {
                        val reason = when {
                            continuousDistractedMs >= limits.continuousMs ->
                                "连续分心超过 " + (limits.continuousMs / 1000) + " 秒，本次专注不计入番茄"

                            ratioOver ->
                                "分心时间占比超过 " + (limits.ratio * 100).toInt() + "%，本次专注不计入番茄"

                            else -> "累计分心时间过长，本次专注不计入番茄"
                        }
                        _state.value = _state.value.copy(
                            elapsedSeconds = (elapsed / 1000).toInt(),
                            remainingSeconds = ((plannedMs - elapsed) / 1000).toInt()
                                .coerceAtLeast(0),
                            distractedSeconds = (totalDistractedMs / 1000).toInt(),
                            distractionCount = distractionCount
                        )
                        finish(SessionPhase.INVALID, reason)
                        return
                    }
                } else if (pending) {
                    pending = false
                    hint = ""
                }
            } else if (pending) {
                pending = false
                hint = ""
            }
            pendingAbandon = pending

            _state.value = _state.value.copy(
                elapsedSeconds = (elapsed / 1000).toInt(),
                remainingSeconds = ((plannedMs - elapsed) / 1000).toInt().coerceAtLeast(0),
                distractedSeconds = (totalDistractedMs / 1000).toInt(),
                distractionCount = distractionCount,
                pendingAbandon = pending,
                message = hint
            )
            handler.postDelayed(this, TICK_INTERVAL)
        }
    }

    private fun elapsedMs(now: Long): Long =
        if (_state.value.phase == SessionPhase.RUNNING) {
            (accumulatedMs + (now - segmentStart)).coerceAtLeast(0)
        } else {
            accumulatedMs
        }

    /** 判定阈值：连续分心时长、累计分心时长、分心占比（0 表示不启用占比规则） */
    private data class Limits(val continuousMs: Long, val totalMs: Long, val ratio: Double)

    private fun thresholds(): Limits {
        val paper = taskMode == FocusPlan.TASK_PAPER
        val continuous = when (strictness) {
            0 -> 60_000L
            2 -> if (paper) 15_000L else 12_000L
            else -> if (paper) 30_000L else 25_000L
        }
        val ratio = when (strictness) {
            0 -> 0.50
            2 -> if (paper) 0.10 else 0.15
            else -> if (paper) 0.20 else 0.30
        }
        val total = maxOf(150_000L, (plannedMs * ratio).toLong())
        // 占比规则只用于纸面任务：盯屏任务沿用原有两条判定，避免行为回退
        return Limits(continuous, total, if (paper) ratio else 0.0)
    }

    // ---------------------------------------------------------------- 结算

    private fun finish(phase: SessionPhase, reason: String) {
        val st = _state.value
        if (!st.isActive) return
        handler.removeCallbacks(ticker)

        val now = SystemClock.elapsedRealtime()
        val actualMs = if (phase == SessionPhase.COMPLETED) plannedMs else elapsedMs(now)
        accumulatedMs = actualMs
        val actualSeconds = (actualMs / 1000).toInt()

        _state.value = _state.value.copy(
            phase = phase,
            elapsedSeconds = if (phase == SessionPhase.COMPLETED) st.plannedSeconds else actualSeconds,
            remainingSeconds = if (phase == SessionPhase.COMPLETED) 0 else ((plannedMs - actualMs) / 1000).toInt().coerceAtLeast(0),
            distractedSeconds = (totalDistractedMs / 1000).toInt(),
            distractionCount = distractionCount,
            message = reason,
            pausedByBackground = false,
            monitorActive = false,
            instantDistracted = false,
            severeDistracted = false,
            monitorNote = "",
            pendingAbandon = false
        )

        val status = when (phase) {
            SessionPhase.COMPLETED -> FocusSession.STATUS_COMPLETED
            SessionPhase.INVALID -> FocusSession.STATUS_INVALID
            else -> FocusSession.STATUS_ABANDONED
        }
        saveSession(status, actualSeconds, reason)
    }

    private fun saveSession(status: Int, actualSeconds: Int, reason: String) {
        val repo = repository ?: return
        if (!saving.compareAndSet(false, true)) return
        val st = _state.value
        val dateKey = Instant.ofEpochMilli(sessionStartWall)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .let { TimeUtils.dateKey(it) }
        val session = FocusSession(
            planId = st.planId,
            planName = st.planName,
            startTime = sessionStartWall,
            endTime = System.currentTimeMillis(),
            plannedSeconds = st.plannedSeconds,
            actualSeconds = actualSeconds,
            dateKey = dateKey,
            status = status,
            monitorEnabled = st.monitorEnabled,
            strictness = strictness,
            taskMode = taskMode,
            distractionCount = st.distractionCount,
            distractedSeconds = st.distractedSeconds,
            invalidReason = reason
        )
        scope.launch {
            runCatching { repo.addSession(session) }.onFailure { Log.e(TAG, "保存专注记录失败", it) }
        }
    }

    fun stopServiceIfNeeded() {
        val ctx = appContext ?: return
        runCatching { ctx.stopService(Intent(ctx, FocusSessionService::class.java)) }
    }
}
