package com.ivan.pomodoro.monitor

import org.json.JSONObject

/**
 * 监控判定参数的场景。
 *
 * 同一套判定阈值在不同使用场景下并不通用：
 * 手机平放写作业时"看不到脸"是常态，而正对屏幕看网课时"看不到脸"就是人走了。
 * 因此参数按 [视角 × 任务类型] 四象限分别保存，切换计划时自动取对应的一组。
 */
enum class MonitorScene(
    val key: String,
    val lensBack: Boolean,
    val paper: Boolean,
    val title: String,
    val summary: String
) {
    FRONT_SCREEN(
        "front_screen", false, false,
        "前置 · 盯屏",
        "手机正对自己，看网课、看视频类计划。判断依据是头部朝向屏幕。"
    ),
    FRONT_PAPER(
        "front_paper", false, true,
        "前置 · 纸面",
        "手机正对自己，写作业、看书类计划。低头是专注证据，抬头张望才是分心。"
    ),
    BACK_SCREEN(
        "back_screen", true, false,
        "俯拍 · 盯屏",
        "手机架高从上往下拍，看网课类计划。判断依据是人脸是否还在画面中间。"
    ),
    BACK_PAPER(
        "back_paper", true, true,
        "俯拍 · 纸面",
        "手机架高俯拍桌面，写作业类计划。看不到脸是常态，以画面有没有动静判定。"
    );

    companion object {
        fun of(lensBack: Boolean, paper: Boolean): MonitorScene = when {
            lensBack && paper -> BACK_PAPER
            lensBack -> BACK_SCREEN
            paper -> FRONT_PAPER
            else -> FRONT_SCREEN
        }

        fun fromKey(key: String?): MonitorScene =
            entries.firstOrNull { it.key == key } ?: FRONT_SCREEN
    }
}

/** 参数预设档位：在场景默认值基础上整体放宽或收紧 */
enum class ProfilePreset(val label: String, val timeScale: Float, val angleScale: Float) {
    LOOSE("宽松", 1.6f, 1.2f),
    NORMAL("标准", 1f, 1f),
    STRICT("严格", 0.65f, 0.85f)
}

/**
 * 一组监控判定参数。
 *
 * 默认值取自真机实测结论（见 [DistractionJudge] 注释），每个字段都是设置页里可调的项：
 * - 姿态类（角度/概率）：决定"这一帧算不算分心"
 * - 时间类（毫秒）：决定"持续多久才提示、持续多久才判本次失败"
 * - 画质类：俯拍偏离距离、画面活动阈值、帧差阈值、分析间隔
 */
data class MonitorProfile(
    /** 扭头阈值：头部左右偏离超过该角度视为看别处 */
    val yawLimit: Float = 42f,
    /** 盯屏任务：低头超过该角度视为没在看屏幕 */
    val lookDownPitch: Float = 32f,
    /** 纸面任务：pitch 小于等于该值视为低头书写（正值为抬头、负值为低头） */
    val paperWorkPitchMax: Float = -8f,
    /** 纸面任务：pitch 大于等于该值视为抬头张望 */
    val paperLookUpPitchMin: Float = -4f,
    /** 双眼睁开概率都低于该值视为闭眼 */
    val eyeOpenMin: Float = 0.2f,
    /** 状态消抖：需持续该时长才上报/解除，过滤眨眼与瞬时抖动 */
    val holdMs: Long = 1500L,
    /** 前置视角：多久看不到人脸判为分心 */
    val noFaceDelayMs: Long = 4000L,
    /** 俯拍视角：多久看不到人脸开始提醒 */
    val overheadNoFaceDelayMs: Long = 6000L,
    /** 俯拍视角：多久看不到人脸升级为作废级 */
    val overheadNoFaceSevereMs: Long = 25_000L,
    /** 纸面前置：多久看不到人脸只做提示（低头时头顶挡脸属正常） */
    val paperNoFaceHintMs: Long = 60_000L,
    /** 纸面任务：抬头持续多久升级为作废级 */
    val lookUpSevereMs: Long = 20_000L,
    /** 纸面任务：闭眼持续多久升级为作废级 */
    val eyesSevereMs: Long = 30_000L,
    /**
     * 俯拍：偏离画面中心多久升级为作废级。
     *
     * 暂未参与判定（纸面俯拍走画面活动分支，盯屏俯拍的升级阈值固定为 0），
     * 保留字段以兼容已保存的参数，设置页不暴露该控件。
     */
    val offSeatSevereMs: Long = 30_000L,
    /** 俯拍：人脸中心偏离画面中心超过该比例视为离开座位 */
    val overheadMaxOffset: Float = 0.40f,
    /** 纸面俯拍：显著变化格占比达到该值视为手在动 */
    val paperActiveMin: Float = 0.01f,
    /** 纸面俯拍：画面静止多久开始提示 */
    val paperQuietHintMs: Long = 12_000L,
    /** 纸面俯拍：画面静止多久升级为作废级 */
    val paperQuietSevereMs: Long = 45_000L,
    /** 帧分析最小间隔，越大越省电 */
    val analysisIntervalMs: Long = 300L,
    /** 帧差阈值：单个采样格灰度差超过该值才算"这一格在变" */
    val motionDiff: Int = 20
) {

    fun toJson(): String = JSONObject().apply {
        put("yawLimit", yawLimit.toDouble())
        put("lookDownPitch", lookDownPitch.toDouble())
        put("paperWorkPitchMax", paperWorkPitchMax.toDouble())
        put("paperLookUpPitchMin", paperLookUpPitchMin.toDouble())
        put("eyeOpenMin", eyeOpenMin.toDouble())
        put("holdMs", holdMs)
        put("noFaceDelayMs", noFaceDelayMs)
        put("overheadNoFaceDelayMs", overheadNoFaceDelayMs)
        put("overheadNoFaceSevereMs", overheadNoFaceSevereMs)
        put("paperNoFaceHintMs", paperNoFaceHintMs)
        put("lookUpSevereMs", lookUpSevereMs)
        put("eyesSevereMs", eyesSevereMs)
        put("offSeatSevereMs", offSeatSevereMs)
        put("overheadMaxOffset", overheadMaxOffset.toDouble())
        put("paperActiveMin", paperActiveMin.toDouble())
        put("paperQuietHintMs", paperQuietHintMs)
        put("paperQuietSevereMs", paperQuietSevereMs)
        put("analysisIntervalMs", analysisIntervalMs)
        put("motionDiff", motionDiff)
    }.toString()

    companion object {

        val DEFAULT = MonitorProfile()

        /** 各场景的出厂参数，与真机实测结论一致 */
        fun defaultFor(scene: MonitorScene): MonitorProfile = when (scene) {
            MonitorScene.FRONT_SCREEN -> DEFAULT.copy(
                yawLimit = 42f,
                lookDownPitch = 32f,
                noFaceDelayMs = 4000L,
                analysisIntervalMs = 300L
            )

            MonitorScene.FRONT_PAPER -> DEFAULT.copy(
                yawLimit = 45f,
                paperWorkPitchMax = -8f,
                paperLookUpPitchMin = -4f,
                paperNoFaceHintMs = 60_000L,
                lookUpSevereMs = 20_000L,
                eyesSevereMs = 30_000L,
                analysisIntervalMs = 300L
            )

            MonitorScene.BACK_SCREEN -> DEFAULT.copy(
                yawLimit = 42f,
                overheadNoFaceDelayMs = 6000L,
                overheadNoFaceSevereMs = 25_000L,
                overheadMaxOffset = 0.40f,
                analysisIntervalMs = 300L
            )

            MonitorScene.BACK_PAPER -> DEFAULT.copy(
                yawLimit = 45f,
                paperWorkPitchMax = -8f,
                paperLookUpPitchMin = -4f,
                overheadMaxOffset = 0.40f,
                paperActiveMin = 0.01f,
                paperQuietHintMs = 12_000L,
                paperQuietSevereMs = 45_000L,
                offSeatSevereMs = 30_000L,
                analysisIntervalMs = 300L
            )
        }

        /**
         * 按预设缩放。只动时间门槛与角度容差，不动 pitch 方向类参数——
         * 那些值的符号本身承载语义（正=抬头、负=低头），缩放会破坏判据。
         */
        fun preset(base: MonitorProfile, preset: ProfilePreset): MonitorProfile {
            fun t(value: Long, min: Long, max: Long): Long =
                (value * preset.timeScale).toLong().coerceIn(min, max)

            return base.copy(
                holdMs = t(base.holdMs, 800L, 4000L),
                noFaceDelayMs = t(base.noFaceDelayMs, 1500L, 20_000L),
                overheadNoFaceDelayMs = t(base.overheadNoFaceDelayMs, 2000L, 20_000L),
                overheadNoFaceSevereMs = t(base.overheadNoFaceSevereMs, 6000L, 60_000L),
                paperNoFaceHintMs = t(base.paperNoFaceHintMs, 15_000L, 180_000L),
                lookUpSevereMs = t(base.lookUpSevereMs, 5000L, 90_000L),
                eyesSevereMs = t(base.eyesSevereMs, 5000L, 90_000L),
                offSeatSevereMs = t(base.offSeatSevereMs, 5000L, 90_000L),
                paperQuietHintMs = t(base.paperQuietHintMs, 3000L, 60_000L),
                paperQuietSevereMs = t(base.paperQuietSevereMs, 15_000L, 180_000L),
                yawLimit = (base.yawLimit * preset.angleScale).coerceIn(20f, 70f),
                overheadMaxOffset = (base.overheadMaxOffset * preset.angleScale)
                    .coerceIn(0.15f, 0.65f)
            )
        }

        /** 解析持久化的参数；缺失或损坏时返回 null，由调用方回退到场景默认值 */
        fun fromJson(raw: String?): MonitorProfile? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                val d = DEFAULT
                MonitorProfile(
                    yawLimit = o.optDouble("yawLimit", d.yawLimit.toDouble()).toFloat(),
                    lookDownPitch = o.optDouble("lookDownPitch", d.lookDownPitch.toDouble()).toFloat(),
                    paperWorkPitchMax = o.optDouble("paperWorkPitchMax", d.paperWorkPitchMax.toDouble())
                        .toFloat(),
                    paperLookUpPitchMin = o.optDouble(
                        "paperLookUpPitchMin", d.paperLookUpPitchMin.toDouble()
                    ).toFloat(),
                    eyeOpenMin = o.optDouble("eyeOpenMin", d.eyeOpenMin.toDouble()).toFloat(),
                    holdMs = o.optLong("holdMs", d.holdMs),
                    noFaceDelayMs = o.optLong("noFaceDelayMs", d.noFaceDelayMs),
                    overheadNoFaceDelayMs = o.optLong(
                        "overheadNoFaceDelayMs", d.overheadNoFaceDelayMs
                    ),
                    overheadNoFaceSevereMs = o.optLong(
                        "overheadNoFaceSevereMs", d.overheadNoFaceSevereMs
                    ),
                    paperNoFaceHintMs = o.optLong("paperNoFaceHintMs", d.paperNoFaceHintMs),
                    lookUpSevereMs = o.optLong("lookUpSevereMs", d.lookUpSevereMs),
                    eyesSevereMs = o.optLong("eyesSevereMs", d.eyesSevereMs),
                    offSeatSevereMs = o.optLong("offSeatSevereMs", d.offSeatSevereMs),
                    overheadMaxOffset = o.optDouble(
                        "overheadMaxOffset", d.overheadMaxOffset.toDouble()
                    ).toFloat(),
                    paperActiveMin = o.optDouble("paperActiveMin", d.paperActiveMin.toDouble())
                        .toFloat(),
                    paperQuietHintMs = o.optLong("paperQuietHintMs", d.paperQuietHintMs),
                    paperQuietSevereMs = o.optLong("paperQuietSevereMs", d.paperQuietSevereMs),
                    analysisIntervalMs = o.optLong("analysisIntervalMs", d.analysisIntervalMs),
                    motionDiff = o.optInt("motionDiff", d.motionDiff)
                )
            }.getOrNull()
        }
    }
}
