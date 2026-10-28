package com.ivan.pomodoro.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次专注记录。只有 status == STATUS_COMPLETED 才计为一个番茄。
 */
@Entity(
    tableName = "focus_sessions",
    indices = [Index("dateKey"), Index("planId"), Index("status")]
)
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long = 0,
    val planName: String = "",
    /** 开始时间（毫秒） */
    val startTime: Long = 0,
    /** 结束时间（毫秒） */
    val endTime: Long = 0,
    /** 计划时长（秒） */
    val plannedSeconds: Int = 0,
    /** 实际专注秒数（不含暂停） */
    val actualSeconds: Int = 0,
    /** 本地日期 yyyy-MM-dd */
    val dateKey: String = "",
    /** 0=完整完成 1=手动放弃 2=监控未通过 */
    val status: Int = STATUS_COMPLETED,
    val monitorEnabled: Boolean = false,
    val strictness: Int = 1,
    /** 本次使用的任务类型快照：0=盯屏 1=纸面 */
    val taskMode: Int = 0,
    /** 归属账户，用于多账户数据隔离 */
    val accountId: Long = 0,
    /** 检测到分心的次数 */
    val distractionCount: Int = 0,
    /** 累计分心秒数 */
    val distractedSeconds: Int = 0,
    /** 未通过/放弃原因 */
    val invalidReason: String = "",
    /**
     * 明细是否已从列表清除。
     * 只影响明细展示，统计（番茄数、专注时长、打卡）始终按全量数据计算，
     * 因此清空记录不会让统计数据变少。
     */
    val cleared: Boolean = false
) {
    val isCompleted: Boolean get() = status == STATUS_COMPLETED

    fun taskModeText(): String = if (taskMode == FocusPlan.TASK_PAPER) "纸面任务" else "盯屏任务"

    companion object {
        const val STATUS_COMPLETED = 0
        const val STATUS_ABANDONED = 1
        const val STATUS_INVALID = 2
    }
}
