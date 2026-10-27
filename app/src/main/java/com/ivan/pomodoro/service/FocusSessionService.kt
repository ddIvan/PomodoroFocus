package com.ivan.pomodoro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.ivan.pomodoro.R
import com.ivan.pomodoro.data.Prefs
import com.ivan.pomodoro.session.FocusSessionManager
import com.ivan.pomodoro.session.SessionPhase
import com.ivan.pomodoro.session.SessionUiState
import com.ivan.pomodoro.ui.session.FocusSessionActivity
import com.ivan.pomodoro.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 专注计时前台服务：保证锁屏/切后台时计时不中断，同时提供通知栏控制。
 */
class FocusSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var lastSecond = -2
    private var finishedAlerted = false
    private var stopScheduled = false
    private var ringtone: android.media.Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(FocusSessionManager.state.value)) }
            .onFailure { Log.e(TAG, "startForeground 失败", it) }

        scope.launch {
            FocusSessionManager.state.collectLatest { st ->
                if (st.phase == SessionPhase.IDLE) {
                    stopSelfSafely()
                    return@collectLatest
                }
                val second = if (st.isActive) st.remainingSeconds else -1
                if (second != lastSecond) {
                    lastSecond = second
                    notify(buildNotification(st))
                }
                if (st.isFinished) {
                    if (!finishedAlerted) {
                        finishedAlerted = true
                        alertFinished(st)
                    }
                    scheduleStop()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> FocusSessionManager.pause()
            ACTION_RESUME -> FocusSessionManager.resume()
            ACTION_STOP -> FocusSessionManager.abandon()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "专注计时", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "专注倒计时与完成提醒"
                    setShowBadge(false)
                    enableVibration(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(st: SessionUiState): Notification {
        val title: String
        val text: String
        when (st.phase) {
            SessionPhase.COMPLETED -> {
                title = "完成一个番茄"
                text = "${st.planName} · ${TimeUtils.durationText(st.plannedSeconds)}"
            }

            SessionPhase.INVALID -> {
                title = "本次专注未计入番茄"
                text = st.message.ifEmpty { "检测到分心行为" }
            }

            SessionPhase.ABANDONED -> {
                title = "本次专注已结束"
                text = "${st.planName} · 未计入番茄"
            }

            SessionPhase.PAUSED -> {
                title = "专注已暂停 · ${TimeUtils.clock(st.remainingSeconds)}"
                text = "${st.planName} · 点击继续"
            }

            SessionPhase.RUNNING -> {
                title = "专注中 · ${TimeUtils.clock(st.remainingSeconds)}"
                text = "${st.planName} · 已专注 ${TimeUtils.clock(st.elapsedSeconds)}"
            }

            else -> {
                title = "番茄专注"
                text = "准备开始"
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tomato)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(st.isActive)
            .setAutoCancel(st.isFinished)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)

        if (st.isActive && st.plannedSeconds > 0) {
            builder.setProgress(st.plannedSeconds, st.elapsedSeconds, false)
        }

        if (st.isRunning) {
            builder.addAction(0, "暂停", serviceIntent(ACTION_PAUSE, 1))
            builder.addAction(0, "结束", serviceIntent(ACTION_STOP, 2))
        } else if (st.isPaused) {
            builder.addAction(0, "继续", serviceIntent(ACTION_RESUME, 3))
            builder.addAction(0, "结束", serviceIntent(ACTION_STOP, 4))
        }
        return builder.build()
    }

    private fun openIntent(): PendingIntent {
        val intent = Intent(this, FocusSessionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(FocusSessionActivity.EXTRA_FROM_NOTIFICATION, true)
        }
        return PendingIntent.getActivity(
            this, 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, FocusSessionService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "通知更新失败", it) }
    }

    private fun alertFinished(st: SessionUiState) {
        val prefs = Prefs(this)
        if (st.phase == SessionPhase.COMPLETED && prefs.soundOnComplete) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtone = RingtoneManager.getRingtone(this, uri)?.also { it.play() }
            }
        }
        if (prefs.vibrateOnDistraction) {
            runCatching {
                val vibrator = getSystemService(Vibrator::class.java) ?: return@runCatching
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 150, 90, 150), -1)
                )
            }
        }
    }

    private fun scheduleStop() {
        if (stopScheduled) return
        stopScheduled = true
        handler.postDelayed({ stopSelfSafely() }, 90_000L)
    }

    private fun stopSelfSafely() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "FocusSessionService"
        private const val CHANNEL_ID = "focus_timer"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "com.ivan.pomodoro.action.PAUSE"
        const val ACTION_RESUME = "com.ivan.pomodoro.action.RESUME"
        const val ACTION_STOP = "com.ivan.pomodoro.action.STOP"
    }
}
