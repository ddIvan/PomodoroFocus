package com.ivan.pomodoro.data

import android.content.Context
import androidx.core.content.edit

/** 轻量偏好设置 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("pomodoro_prefs", Context.MODE_PRIVATE)

    var selectedPlanId: Long
        get() = sp.getLong(KEY_SELECTED_PLAN, 0L)
        set(value) = sp.edit { putLong(KEY_SELECTED_PLAN, value) }

    /** 分心提醒是否震动 */
    var vibrateOnDistraction: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, true)
        set(value) = sp.edit { putBoolean(KEY_VIBRATE, value) }

    /** 专注完成后是否响铃 */
    var soundOnComplete: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(value) = sp.edit { putBoolean(KEY_SOUND, value) }

    /**
     * 摄像头专注监控开关。
     * 返回 null 表示用户还没手动改过，此时沿用当前计划的默认值。
     */
    var monitorEnabled: Boolean?
        get() = if (sp.contains(KEY_MONITOR)) sp.getBoolean(KEY_MONITOR, false) else null
        set(value) = sp.edit {
            if (value == null) remove(KEY_MONITOR) else putBoolean(KEY_MONITOR, value)
        }

    /** 监控视角：0 前置（正对屏幕），1 后置（手机架高俯拍） */
    var monitorLens: Int
        get() = sp.getInt(KEY_LENS, LENS_FRONT)
        set(value) = sp.edit { putInt(KEY_LENS, value) }

    /** 分心时是否语音提醒 */
    var voiceOnDistraction: Boolean
        get() = sp.getBoolean(KEY_VOICE, true)
        set(value) = sp.edit { putBoolean(KEY_VOICE, value) }

    /** 最近一次自动清理历史明细的日期（yyyy-MM-dd），保证每天只清理一次 */
    var lastAutoClearDateKey: String
        get() = sp.getString(KEY_LAST_AUTO_CLEAR, "") ?: ""
        set(value) = sp.edit { putString(KEY_LAST_AUTO_CLEAR, value) }

    companion object {
        const val LENS_FRONT = 0
        const val LENS_BACK = 1

        private const val KEY_SELECTED_PLAN = "selected_plan_id"
        private const val KEY_VIBRATE = "vibrate_on_distraction"
        private const val KEY_SOUND = "sound_on_complete"
        private const val KEY_LENS = "monitor_lens"
        private const val KEY_MONITOR = "monitor_enabled"
        private const val KEY_VOICE = "voice_on_distraction"
        private const val KEY_LAST_AUTO_CLEAR = "last_auto_clear_date"
    }
}
