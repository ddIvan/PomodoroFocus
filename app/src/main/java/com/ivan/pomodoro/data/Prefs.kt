package com.ivan.pomodoro.data

import android.content.Context
import androidx.core.content.edit
import com.ivan.pomodoro.monitor.MonitorProfile
import com.ivan.pomodoro.monitor.MonitorScene

/** 轻量偏好设置 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("pomodoro_prefs", Context.MODE_PRIVATE)

    // ---------- 登录态 ----------

    /** 当前登录账户 id，0 表示未登录 */
    var currentAccountId: Long
        get() = sp.getLong(KEY_ACCOUNT_ID, 0L)
        set(value) = sp.edit { putLong(KEY_ACCOUNT_ID, value) }

    /** 当前登录账户名（仅用于界面展示，真身仍以数据库为准） */
    var currentAccountName: String
        get() = sp.getString(KEY_ACCOUNT_NAME, "") ?: ""
        set(value) = sp.edit { putString(KEY_ACCOUNT_NAME, value) }

    /** 当前账户是否管理员 */
    var currentAccountAdmin: Boolean
        get() = sp.getBoolean(KEY_ACCOUNT_ADMIN, false)
        set(value) = sp.edit { putBoolean(KEY_ACCOUNT_ADMIN, value) }

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

    /** 专注完成后是否语音播报"您已获得一个番茄" */
    var voiceOnComplete: Boolean
        get() = sp.getBoolean(KEY_VOICE_COMPLETE, true)
        set(value) = sp.edit { putBoolean(KEY_VOICE_COMPLETE, value) }

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

    /**
     * 已同意的隐私政策版本，空串表示从未同意。
     * 存版本而不是布尔值，是为了政策内容出现实质变更时能让老用户重新确认。
     */
    var privacyAgreedVersion: String
        get() = sp.getString(KEY_PRIVACY_AGREED, "") ?: ""
        set(value) = sp.edit { putString(KEY_PRIVACY_AGREED, value) }

    // ---------- 监控参数（按场景各存一份） ----------

    /** 读取某场景的监控参数；没改过就用该场景的出厂值 */
    fun monitorProfile(scene: MonitorScene): MonitorProfile =
        MonitorProfile.fromJson(sp.getString(KEY_MONITOR_PROFILE + scene.key, null))
            ?: MonitorProfile.defaultFor(scene)

    /** 该场景是否被手动调过参数（用于设置页提示"已自定义"） */
    fun hasMonitorProfile(scene: MonitorScene): Boolean =
        sp.contains(KEY_MONITOR_PROFILE + scene.key)

    fun saveMonitorProfile(scene: MonitorScene, profile: MonitorProfile) = sp.edit {
        putString(KEY_MONITOR_PROFILE + scene.key, profile.toJson())
    }

    /** 恢复某场景的出厂参数 */
    fun resetMonitorProfile(scene: MonitorScene) = sp.edit {
        remove(KEY_MONITOR_PROFILE + scene.key)
    }

    /** 某个账户最近一次自动清理历史明细的日期（yyyy-MM-dd），保证每天只清理一次 */
    fun autoClearMark(accountId: Long): String =
        sp.getString("${KEY_LAST_AUTO_CLEAR}_$accountId", "") ?: ""

    fun setAutoClearMark(accountId: Long, dateKey: String) = sp.edit {
        putString("${KEY_LAST_AUTO_CLEAR}_$accountId", dateKey)
    }

    companion object {
        const val LENS_FRONT = 0
        const val LENS_BACK = 1

        private const val KEY_ACCOUNT_ID = "current_account_id"
        private const val KEY_ACCOUNT_NAME = "current_account_name"
        private const val KEY_ACCOUNT_ADMIN = "current_account_admin"
        private const val KEY_SELECTED_PLAN = "selected_plan_id"
        private const val KEY_VIBRATE = "vibrate_on_distraction"
        private const val KEY_SOUND = "sound_on_complete"
        private const val KEY_VOICE_COMPLETE = "voice_on_complete"
        private const val KEY_LENS = "monitor_lens"
        private const val KEY_MONITOR = "monitor_enabled"
        private const val KEY_VOICE = "voice_on_distraction"
        private const val KEY_LAST_AUTO_CLEAR = "last_auto_clear_date"
        private const val KEY_MONITOR_PROFILE = "monitor_profile_"
        private const val KEY_PRIVACY_AGREED = "privacy_agreed_version"
    }
}
