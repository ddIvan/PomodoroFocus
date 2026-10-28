package com.ivan.pomodoro.ui.session

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.R
import com.ivan.pomodoro.databinding.ActivityFocusSessionBinding
import com.ivan.pomodoro.monitor.FocusMonitor
import com.ivan.pomodoro.session.FocusSessionManager
import com.ivan.pomodoro.session.SessionPhase
import com.ivan.pomodoro.session.SessionUiState
import com.ivan.pomodoro.ui.MainActivity
import com.ivan.pomodoro.util.TimeUtils
import kotlinx.coroutines.launch

class FocusSessionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAN_ID = "plan_id"
        const val EXTRA_MONITOR = "monitor_enabled"
        const val EXTRA_LENS = "monitor_lens"
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
        private const val STATE_RESULT_SHOWN = "result_shown"

        /** 两次语音提醒的最小间隔，避免连续播报 */
        private const val VOICE_INTERVAL_MS = 12_000L
    }

    private lateinit var binding: ActivityFocusSessionBinding
    private var monitor: FocusMonitor? = null
    private var resultShown = false
    private var permissionRequested = false
    private var lastMonitorEnabled = false
    private var distractionWarned = false
    private var lastLens = com.ivan.pomodoro.data.Prefs.LENS_FRONT
    private var abandonDialog: androidx.appcompat.app.AlertDialog? = null
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var ttsInited = false
    private var lastVoiceAt = 0L

    private val prefs by lazy { com.ivan.pomodoro.data.Prefs(this) }

    private val colorGreen by lazy { ContextCompat.getColor(this, R.color.pomo_green) }
    private val colorAmber by lazy { ContextCompat.getColor(this, R.color.pomo_amber) }
    private val colorText by lazy { ContextCompat.getColor(this, R.color.pomo_text) }
    private val colorSecond by lazy { ContextCompat.getColor(this, R.color.pomo_text_second) }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMonitorIfNeeded()
            } else {
                FocusSessionManager.markMonitorUnavailable("未获得摄像头权限，本次专注不使用监控")
                binding.previewCard.isVisible = false
                Toast.makeText(this, "已跳过摄像头监控，专注继续进行", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFocusSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        FocusSessionManager.init(this)
        resultShown = savedInstanceState?.getBoolean(STATE_RESULT_SHOWN) ?: false

        // 进程被回收后从通知进入：会话状态已丢失，直接退出并清理残留服务
        if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) &&
            !FocusSessionManager.state.value.isActive
        ) {
            FocusSessionManager.stopServiceIfNeeded()
            finish()
            return
        }

        setupButtons()
        observeState()

        // 提前加载语音引擎，首次分心时才能立即播报
        initTts()

        lastLens = prefs.monitorLens
        if (FocusSessionManager.state.value.isActive) {
            lastMonitorEnabled = FocusSessionManager.state.value.monitorEnabled
        } else {
            startFromIntent()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmAbandon()
            }
        })
    }

    private fun startFromIntent() {
        val planId = intent.getLongExtra(EXTRA_PLAN_ID, 0L)
        val monitorEnabled = intent.getBooleanExtra(EXTRA_MONITOR, false)
        lastLens = intent.getIntExtra(EXTRA_LENS, prefs.monitorLens)
        if (planId <= 0L) {
            finish()
            return
        }
        lifecycleScope.launch {
            val plan = (application as PomodoroApp).repository.getPlan(planId)
            if (plan == null) {
                Toast.makeText(this@FocusSessionActivity, "计划不存在", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            lastMonitorEnabled = monitorEnabled
            FocusSessionManager.startSession(plan, monitorEnabled, plan.strictness)
        }
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener { confirmAbandon() }
        binding.btnStop.setOnClickListener { confirmAbandon() }
        binding.btnPause.setOnClickListener {
            val st = FocusSessionManager.state.value
            when {
                st.isPaused -> FocusSessionManager.resume()
                st.isRunning -> FocusSessionManager.pause()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FocusSessionManager.state.collect { st ->
                    render(st)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val st = FocusSessionManager.state.value
        if (st.isActive) {
            FocusSessionManager.resumeFromBackground()
            if (st.monitorEnabled) startMonitorIfNeeded()
        }
    }

    override fun onStop() {
        super.onStop()
        monitor?.stop()
        monitor = null
        if (FocusSessionManager.state.value.isRunning) {
            FocusSessionManager.pauseByBackground()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RESULT_SHOWN, resultShown)
    }

    override fun onDestroy() {
        monitor?.stop()
        monitor = null
        abandonDialog?.dismiss()
        abandonDialog = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    // ------------------------------------------------------------ 摄像头

    private fun maybeStartMonitor(st: SessionUiState) {
        if (!st.isActive || !st.monitorEnabled || monitor != null) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startMonitorIfNeeded()
        } else if (!permissionRequested) {
            permissionRequested = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startMonitorIfNeeded() {
        if (monitor != null) return
        val st = FocusSessionManager.state.value
        if (!st.monitorEnabled || !st.isActive) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        binding.previewCard.isVisible = true
        val lens = if (lastLens == com.ivan.pomodoro.data.Prefs.LENS_BACK) {
            FocusMonitor.Lens.BACK
        } else {
            FocusMonitor.Lens.FRONT
        }
        val task = if (st.isPaperTask) {
            com.ivan.pomodoro.monitor.DistractionJudge.Task.PAPER
        } else {
            com.ivan.pomodoro.monitor.DistractionJudge.Task.SCREEN
        }
        val m = FocusMonitor(this, this, binding.previewView, lens, task) { verdict, active ->
            FocusSessionManager.updateMonitor(
                active, verdict.distracted, verdict.severe, verdict.reason, verdict.note
            )
        }
        monitor = m
        m.start()
    }

    // ------------------------------------------------------------ 渲染

    private fun render(st: SessionUiState) {
        binding.tvPlanName.text = st.planName.ifBlank { "专注" }
        binding.tvGoal.text = when {
            st.pendingAbandon ->
                st.message.ifBlank {
                    if (st.isPaperTask) {
                        "请回到专注状态，否则本次不计入番茄"
                    } else {
                        "请回到镜头前，否则本次不计入番茄"
                    }
                }

            st.planGoal.isNotBlank() -> st.planGoal
            else -> "保持专注，完成这个时段"
        }
        binding.tvGoal.setTextColor(if (st.pendingAbandon) colorAmber else colorSecond)
        binding.tvRemaining.text = TimeUtils.clock(st.remainingSeconds)
        binding.ring.progress = st.progress
        // 只有"作废级"分心或宽限期倒计时才让进度环变警示色，轻微低头不打扰
        binding.ring.warnMode = st.severeDistracted || st.pendingAbandon

        binding.tvPhase.text = when (st.phase) {
            SessionPhase.RUNNING -> when {
                st.pendingAbandon || st.severeDistracted ->
                    if (st.isPaperTask) "请回到专注状态" else "请回到镜头前"

                st.instantDistracted -> "专注中（轻微偏离）"
                else -> "专注中"
            }
            SessionPhase.PAUSED -> if (st.pausedByBackground) "已暂停 · 请留在本页面" else "已暂停"
            SessionPhase.COMPLETED -> "已完成"
            SessionPhase.INVALID -> "监控未通过"
            SessionPhase.ABANDONED -> "已结束"
            SessionPhase.IDLE -> ""
        }

        val tagText = when {
            !st.monitorEnabled -> "未开启监控"
            st.strictness == 0 -> "宽松监控"
            st.strictness == 2 -> "严格监控"
            else -> "标准监控"
        }
        binding.tvStrictTag.text = if (!st.monitorEnabled) {
            tagText
        } else {
            buildString {
                append(tagText)
                if (lastLens == com.ivan.pomodoro.data.Prefs.LENS_BACK) append(" · 俯拍")
                if (st.isPaperTask) append(" · 纸面")
            }
        }

        binding.previewCard.isVisible = monitor != null && st.monitorEnabled && st.isActive
        val green = colorGreen
        val amber = colorAmber
        val textColor = colorText
        when {
            !st.monitorEnabled -> {
                binding.tvMonitorState.text = "未开启摄像头监控"
                binding.tvMonitorState.setTextColor(textColor)
                binding.tvDistractInfo.text = "本次不检测分心行为"
            }

            st.pendingAbandon -> {
                binding.tvMonitorState.text = "分心超标 · 宽限中"
                binding.tvMonitorState.setTextColor(amber)
                binding.tvDistractInfo.text = "请回到镜头前，否则本次不计入番茄"
            }

            st.monitorActive && st.instantDistracted -> {
                binding.tvMonitorState.text =
                    if (st.severeDistracted) "检测到分心" else "轻微偏离"
                binding.tvMonitorState.setTextColor(amber)
                binding.tvDistractInfo.text =
                    "分心 ${st.distractionCount} 次 · 累计 ${st.distractedSeconds} 秒"
            }

            st.monitorActive && st.monitorNote.isNotBlank() -> {
                binding.tvMonitorState.text = "专注中"
                binding.tvMonitorState.setTextColor(green)
                binding.tvDistractInfo.text = st.monitorNote
            }

            st.monitorActive -> {
                binding.tvMonitorState.text = "专注中"
                binding.tvMonitorState.setTextColor(green)
                binding.tvDistractInfo.text =
                    "分心 ${st.distractionCount} 次 · 累计 ${st.distractedSeconds} 秒"
            }

            else -> {
                binding.tvMonitorState.text = if (st.isActive) "监控启动中…" else "监控已停止"
                binding.tvMonitorState.setTextColor(textColor)
                binding.tvDistractInfo.text = "分心 ${st.distractionCount} 次"
            }
        }

        binding.cardDistraction.isVisible = st.monitorActive && st.instantDistracted && st.isRunning
        binding.tvDistraction.text = when {
            !st.severeDistracted ->
                st.distractionReason.ifBlank {
                    if (st.isPaperTask) "轻微提醒，不影响本次专注" else "轻微偏离，不影响本次专注"
                }

            else ->
                st.distractionReason.ifBlank { "检测到分心，请回到专注状态" }
        }

        // 仅在作废级分心时震动提醒，轻微低头只做文字提示
        if (st.severeDistracted && st.isRunning) {
            if (!distractionWarned) {
                distractionWarned = true
                warnDistraction()
            }
        } else {
            distractionWarned = false
        }

        binding.btnPause.text = if (st.isPaused) "继续专注" else "暂停"
        binding.btnPause.isEnabled = st.isActive
        binding.btnStop.isEnabled = st.isActive

        if (st.isFinished) {
            showResult(st)
        } else if (st.isActive) {
            lastMonitorEnabled = st.monitorEnabled
            maybeStartMonitor(st)
        }
    }

    // ------------------------------------------------------------ 交互

    private fun warnDistraction() {
        speakDistraction()
        if (!prefs.vibrateOnDistraction) return
        runCatching {
            val vibrator = getSystemService(android.os.Vibrator::class.java) ?: return@runCatching
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(
                    80, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        }
    }

    /** 语音提醒：开关打开且引擎可用时播报，并限制最小间隔避免连续播报 */
    private fun speakDistraction() {
        if (!prefs.voiceOnDistraction) return
        if (!ttsInited) {
            initTts()
            return
        }
        if (!ttsReady) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastVoiceAt < VOICE_INTERVAL_MS) return
        lastVoiceAt = now
        val code = runCatching {
            tts?.speak(
                "请不要分心",
                android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                null,
                "distraction"
            )
        }.getOrNull()
        android.util.Log.i("FocusVoice", "播放分心语音提醒 speak=$code")
        if (code == android.speech.tts.TextToSpeech.ERROR) {
            ttsReady = false
        }
    }

    /** 完成一个番茄时的语音播报；TTS 可能还没就绪，做几次短延迟重试 */
    private fun speakComplete(attempt: Int = 0) {
        if (!prefs.voiceOnComplete) return
        if (!ttsInited) initTts()
        if (!ttsReady) {
            if (attempt < 6 && !isFinishing && !isDestroyed) {
                binding.root.postDelayed({ speakComplete(attempt + 1) }, 250L)
            }
            return
        }
        val code = runCatching {
            tts?.speak(
                "您已获得一个番茄",
                android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                null,
                "complete"
            )
        }.getOrNull()
        android.util.Log.i("FocusVoice", "播放完成语音播报 speak=$code")
        if (code == android.speech.tts.TextToSpeech.ERROR) {
            ttsReady = false
        }
    }

    private fun initTts() {
        if (ttsInited || !(prefs.voiceOnDistraction || prefs.voiceOnComplete)) return
        ttsInited = true
        tts = android.speech.tts.TextToSpeech(this) { status ->
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            android.util.Log.i("FocusVoice", "TTS 初始化结果：status=$status ready=$ttsReady")
            if (ttsReady) {
                runCatching {
                    val lang = tts?.setLanguage(java.util.Locale.SIMPLIFIED_CHINESE)
                    android.util.Log.i("FocusVoice", "TTS 语言设置结果：$lang")
                    if (lang == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                        lang == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        tts?.setLanguage(java.util.Locale.getDefault())
                    }
                    // 走提示音通道，保证媒体音量较低时也能听见
                    tts?.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.setOnUtteranceProgressListener(
                        object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                android.util.Log.i("FocusVoice", "语音开始播放")
                            }

                            override fun onDone(utteranceId: String?) {
                                android.util.Log.i("FocusVoice", "语音播放完成")
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                android.util.Log.w("FocusVoice", "语音播放失败：$utteranceId")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun confirmAbandon() {
        val st = FocusSessionManager.state.value
        if (!st.isActive) {
            FocusSessionManager.reset()
            finish()
            return
        }
        if (abandonDialog?.isShowing == true) return
        abandonDialog = MaterialAlertDialogBuilder(this)
            .setTitle("结束本次专注？")
            .setMessage("未完成完整时段，本次不会计入番茄。")
            .setNegativeButton("继续专注", null)
            .setPositiveButton("结束") { _, _ -> FocusSessionManager.abandon() }
            .show()
    }

    /**
     * 结束收尾：不弹结果对话框（因此也没有"再专注一次"），
     * 只用一条 Toast 说明结果，然后直接回到首页。
     */
    private fun showResult(st: SessionUiState) {
        if (resultShown) return
        resultShown = true

        // 完成一个完整时段时先报喜
        if (st.phase == SessionPhase.COMPLETED) speakComplete()

        val message = when (st.phase) {
            SessionPhase.COMPLETED ->
                "「${st.planName}」专注 ${st.plannedSeconds / 60} 分钟，收获 1 个番茄"

            SessionPhase.INVALID ->
                st.message.ifBlank { "检测到持续分心，本次专注不计入番茄" }

            else ->
                "本次专注 ${st.elapsedSeconds / 60} 分钟，未计入番茄"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        FocusSessionManager.reset()
        if (isFinishing || isDestroyed) return

        // 完成播报是异步的，留出起播时间再退出，否则 onDestroy 里的 stop 会把它掐断
        val delay = if (st.phase == SessionPhase.COMPLETED && prefs.voiceOnComplete) 1600L else 0L
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            // 从通知进入时栈里可能没有首页，显式拉起，保证结束一定落到首页
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }, delay)
    }
}
