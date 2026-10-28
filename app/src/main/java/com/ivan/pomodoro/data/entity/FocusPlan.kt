package com.ivan.pomodoro.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 专注计划：用户自定义的番茄钟模板。
 */
@Entity(tableName = "focus_plans")
data class FocusPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 计划名称，如"晨间阅读" */
    val name: String = "",
    /** 单次专注时长（分钟） */
    val durationMinutes: Int = 25,
    /** 专注目标（这次专注要达成什么） */
    val goal: String = "",
    /** 频率类型：0=每天 1=工作日 2=自定义 */
    val frequencyType: Int = FREQ_DAILY,
    /** 自定义星期掩码：bit0=周一 ... bit6=周日 */
    val weekdaysMask: Int = 0b1111111,
    /** 每天计划完成几个番茄 */
    val dailyTarget: Int = 4,
    /** 是否默认启用摄像头专注监控 */
    val monitorEnabled: Boolean = false,
    /** 分心判定严格度：0=宽松 1=标准 2=严格 */
    val strictness: Int = 1,
    /**
     * 任务类型：0=盯屏（看网课/视频，面向屏幕才算专注）
     * 1=纸面（写作业/看书，低头书写才算专注，抬头张望才是分心）
     */
    val taskMode: Int = TASK_SCREEN,
    /** 归属账户，用于多账户数据隔离 */
    val accountId: Long = 0,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 指定的日期是否需要执行该计划 */
    fun scheduledOn(date: LocalDate): Boolean = when (frequencyType) {
        FREQ_WEEKDAYS -> date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
        FREQ_CUSTOM -> (weekdaysMask shr (date.dayOfWeek.value - 1)) and 1 == 1
        else -> true
    }

    fun frequencyText(): String = when (frequencyType) {
        FREQ_WEEKDAYS -> "工作日"
        FREQ_CUSTOM -> {
            if (weekdaysMask == 0b1111111) "每天" else {
                val names = listOf("一", "二", "三", "四", "五", "六", "日")
                val picked = names.filterIndexed { index, _ -> (weekdaysMask shr index) and 1 == 1 }
                "周" + picked.joinToString("、")
            }
        }
        else -> "每天"
    }

    fun strictnessText(): String = when (strictness) {
        0 -> "宽松"
        2 -> "严格"
        else -> "标准"
    }

    fun taskModeText(): String = when (taskMode) {
        TASK_PAPER -> "纸面任务"
        else -> "盯屏任务"
    }

    companion object {
        const val FREQ_DAILY = 0
        const val FREQ_WEEKDAYS = 1
        const val FREQ_CUSTOM = 2

        const val TASK_SCREEN = 0
        const val TASK_PAPER = 1
    }
}
