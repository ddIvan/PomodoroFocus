package com.ivan.pomodoro.session

enum class SessionPhase { IDLE, RUNNING, PAUSED, COMPLETED, INVALID, ABANDONED }

data class SessionUiState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val planId: Long = 0L,
    val planName: String = "",
    val planGoal: String = "",
    val plannedSeconds: Int = 0,
    val elapsedSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val monitorEnabled: Boolean = false,
    val monitorActive: Boolean = false,
    val instantDistracted: Boolean = false,
    /** 是否为"作废级"分心（离开镜头/大幅偏头）；低头、闭眼等仅提示，不计入失败 */
    val severeDistracted: Boolean = false,
    /** 分心已超标、正在宽限期倒计时，回到镜头前即可继续 */
    val pendingAbandon: Boolean = false,
    val distractionReason: String = "",
    val distractionCount: Int = 0,
    val distractedSeconds: Int = 0,
    val strictness: Int = 1,
    /** 任务类型：0=盯屏 1=纸面（与 FocusPlan.taskMode 一致） */
    val taskMode: Int = 0,
    /** 仅提示、不计入分心的观察（如纸面任务暂时看不到脸） */
    val monitorNote: String = "",
    val message: String = "",
    val pausedByBackground: Boolean = false
) {
    val isPaperTask: Boolean get() = taskMode == 1
    val isRunning: Boolean get() = phase == SessionPhase.RUNNING
    val isPaused: Boolean get() = phase == SessionPhase.PAUSED
    val isActive: Boolean get() = isRunning || isPaused
    val isFinished: Boolean
        get() = phase == SessionPhase.COMPLETED || phase == SessionPhase.INVALID ||
            phase == SessionPhase.ABANDONED

    val progress: Float
        get() = if (plannedSeconds <= 0) 0f else (elapsedSeconds.toFloat() / plannedSeconds).coerceIn(0f, 1f)
}
