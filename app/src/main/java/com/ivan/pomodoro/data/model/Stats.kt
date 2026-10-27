package com.ivan.pomodoro.data.model

/** 每日番茄汇总 */
data class DailyStat(
    val dateKey: String,
    val count: Int,
    val seconds: Int
)
