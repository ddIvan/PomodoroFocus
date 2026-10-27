package com.ivan.pomodoro.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeUtils {

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("M月d日")

    private val WEEK_NAMES = arrayOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** 秒 -> mm:ss（超过一小时则 h:mm:ss） */
    fun clock(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /** 秒 -> "25 分钟" / "1 小时 10 分钟" */
    fun durationText(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        return when {
            minutes < 60 -> "$minutes 分钟"
            minutes % 60 == 0 -> "${minutes / 60} 小时"
            else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        }
    }

    fun dateKey(date: LocalDate): String = date.format(DATE_KEY_FORMAT)

    fun parseDateKey(key: String): LocalDate? = try {
        LocalDate.parse(key, DATE_KEY_FORMAT)
    } catch (e: Exception) {
        null
    }

    /** "10月27日 星期二" */
    fun dayTitle(date: LocalDate): String =
        "${date.format(MONTH_DAY_FORMAT)} ${WEEK_NAMES[date.dayOfWeek.value - 1]}"

    /** "2026年10月" */
    fun monthTitle(month: YearMonth): String = "${month.year}年${month.monthValue}月"

    fun timeOfDay(millis: Long): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(TIME_FORMAT)

    fun today(): LocalDate = LocalDate.now()

    fun toastTimeRange(start: Long, end: Long): String = "${timeOfDay(start)} - ${timeOfDay(end)}"
}
