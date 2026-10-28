package com.ivan.pomodoro.monitor

import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * 基于人脸关键指标的专注度判定。
 *
 * 两个正交维度：
 * - [Mode] 硬件视角：前置正对 / 后置俯拍
 * - [Task] 任务类型：盯屏（看网课、看视频）/ 纸面（写作业、看书）
 *
 * 盯屏任务的判据是"是否看着屏幕"；纸面任务则相反——低头书写才是专注证据，
 * 抬头张望才是分心，俯拍时只看到手和笔属于常态，不能当作离席。
 *
 * 纸面 + 俯拍按三档判定：
 * 1. 检测到人脸（抬头的一瞬间）→ 人在座位，专注；
 * 2. 看不到脸但画面里有明显动作（笔在手在动）→ 正在书写，专注；
 * 3. 既看不到脸、画面也长时间没有动静 → 人已离开，分心。
 *
 * 判定分级：
 * - 作废级（severe）：确认离开座位、大幅扭头看别处等强信号，累计后可能导致本次失败。
 * - 提醒级（mild）：抬头、闭眼等弱信号，只做页内提示；部分情况持续恶化后升级为作废级。
 * - note：仅提示、不计入任何分心统计（例如纸面任务长时间看不到脸）。
 */
class DistractionJudge(
    private val mode: Mode = Mode.FRONT,
    private val task: Task = Task.SCREEN
) {

    enum class Mode { FRONT, OVERHEAD }

    enum class Task { SCREEN, PAPER }

    data class Verdict(
        val distracted: Boolean,
        val reason: String = "",
        val severe: Boolean = false,
        /** 仅提示、不计入分心的观察 */
        val note: String = ""
    )

    private var noFaceSince = 0L
    private var quietSince = 0L
    private var instantDistracted = false
    private var instantSevere = false
    private var instantEscalateMs = 0L
    private var instantSince = 0L
    private var reportedDistracted = false
    private var reportedSevere = false
    private var lastReason = ""
    private var lastNote = ""

    fun reset() {
        noFaceSince = 0L
        quietSince = 0L
        instantDistracted = false
        instantSevere = false
        instantEscalateMs = 0L
        instantSince = 0L
        reportedDistracted = false
        reportedSevere = false
        lastReason = ""
        lastNote = ""
    }

    /**
     * @param motionActive 画面中"显著变化"的采样格占比，0~1；小于 0 表示本次没有该数据。
     */
    fun evaluate(
        faces: List<Face>,
        now: Long,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
        motionActive: Float = -1f
    ): Verdict {
        lastNote = ""
        if (faces.isEmpty()) {
            if (noFaceSince == 0L) noFaceSince = now
            judgeNoFace(now - noFaceSince, now, motionActive)
        } else {
            noFaceSince = 0L
            val face = faces.maxByOrNull {
                it.boundingBox.width().toLong() * it.boundingBox.height().toLong()
            }
            when {
                face == null -> markInstant(false, false, "", 0L, now)
                // 纸面俯拍：看到脸说明人就在座位上，直接算专注。
                // 俯角大时人脸常落在画面边缘，按中心偏移判"离座"会误报。
                mode == Mode.OVERHEAD && task == Task.PAPER -> {
                    quietSince = now
                    markInstant(false, false, "", 0L, now)
                }
                mode == Mode.OVERHEAD -> judgeOverhead(face, now, imageWidth, imageHeight)
                task == Task.PAPER -> judgePaperFront(face, now)
                else -> judgeScreenFront(face, now)
            }
        }

        // 消抖：状态需持续 HOLD_MS 才上报/解除，过滤眨眼与瞬时抖动
        if (instantDistracted) {
            if (now - instantSince >= HOLD_MS) {
                if (!reportedDistracted) {
                    reportedDistracted = true
                    reportedSevere = instantSevere
                } else if (instantSevere && !reportedSevere) {
                    // 提醒级持续恶化后升级为作废级
                    reportedSevere = true
                }
            }
            // 纸面任务的抬头/闭眼/离座：持续够久才升级为作废级
            if (reportedDistracted && !reportedSevere && instantEscalateMs > 0L &&
                now - instantSince >= instantEscalateMs
            ) {
                reportedSevere = true
            }
        } else if (reportedDistracted && now - instantSince >= HOLD_MS) {
            reportedDistracted = false
            reportedSevere = false
        }
        return Verdict(
            distracted = reportedDistracted,
            reason = if (reportedDistracted) lastReason else "",
            severe = reportedSevere,
            note = if (reportedDistracted) "" else lastNote
        )
    }

    // ---------------------------------------------------------------- 无人脸

    private fun judgeNoFace(gap: Long, now: Long, motionActive: Float = -1f) {
        when (mode) {
            Mode.OVERHEAD -> when {
                // 纸面俯拍：看不到脸是常态（低头只剩手和头发），只看画面里还有没有东西在动
                task == Task.PAPER && motionActive >= 0f ->
                    judgePaperOverheadMotion(motionActive, now)

                // 拿不到画面数据时退回静默：宁可漏报，也不误报
                task == Task.PAPER -> markInstant(false, false, "", 0L, now)

                gap >= OVERHEAD_SEVERE_NO_FACE_MS ->
                    markInstant(true, true, "长时间未检测到人，请回到座位", 0L, now)

                gap >= OVERHEAD_NO_FACE_DELAY_MS ->
                    markInstant(true, false, "未检测到人脸，请回到座位", 0L, now)
            }

            Mode.FRONT -> when {
                // 纸面任务低头时头顶会挡住脸，无人脸视为"正在低头"，不判分心
                task == Task.PAPER && gap >= PAPER_FRONT_NO_FACE_HINT_MS ->
                    markInstant(
                        false, false, "", 0L, now,
                        note = "长时间没看到你，请确认还在镜头前"
                    )

                task == Task.PAPER -> markInstant(false, false, "", 0L, now)

                gap >= NO_FACE_DELAY_MS ->
                    markInstant(true, true, "未检测到人脸，请回到镜头前", 0L, now)
            }
        }
    }

    /**
     * 纸面俯拍：以"画面里有没有东西在动"为唯一判据。
     *
     * 实测（手机平放俯拍桌面）：
     * - 空桌面：变化格占比 0.00%，噪声峰值也只有 0.26%；
     * - 手在写字、翻页：变化格占比 11%~78%。
     *
     * 人像分割在俯拍桌面误报严重——空桌面也有 22%~27% 的假前景、2.7% 的高置信
     * 前景，与真实有手时的 3.6% 完全重叠，因此不参与判定。
     *
     * 思考、看题时手会短暂停下，所以按"静默持续时长"判定而不是单帧，
     * 短暂的停顿不会提示。
     */
    private fun judgePaperOverheadMotion(motionActive: Float, now: Long) {
        if (motionActive >= PAPER_ACTIVE_MIN) {
            quietSince = now
            markInstant(false, false, "", 0L, now)
            return
        }
        if (quietSince == 0L) quietSince = now
        val quiet = now - quietSince
        when {
            quiet >= PAPER_QUIET_SEVERE_MS ->
                markInstant(true, true, "画面长时间没有动静，请回到座位继续书写", 0L, now)

            quiet >= PAPER_QUIET_HINT_MS ->
                markInstant(
                    true, false, "画面没有动静，请确认还在写作业",
                    PAPER_QUIET_SEVERE_MS, now
                )

            else -> markInstant(false, false, "", 0L, now)
        }
    }

    // ---------------------------------------------------------------- 前置正对

    /** 盯屏：看着屏幕才算专注 */
    private fun judgeScreenFront(face: Face, now: Long) {
        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX
        val leftEye = face.leftEyeOpenProbability
        val rightEye = face.rightEyeOpenProbability

        when {
            // 头部大幅偏离：扭头看别处，属于强信号
            abs(yaw) > HEAD_AWAY_YAW -> markInstant(true, true, "头部偏离屏幕，请面向镜头", 0L, now)

            // 低头（写字/看书）与闭眼：只提醒，不累计作废
            abs(pitch) > LOOK_DOWN_PITCH ->
                markInstant(true, false, "检测到低头，请适当抬头看屏幕", 0L, now)

            leftEye != null && rightEye != null && leftEye < 0.2f && rightEye < 0.2f ->
                markInstant(true, false, "检测到闭眼，请保持清醒", 0L, now)

            else -> markInstant(false, false, "", 0L, now)
        }
    }

    /**
     * 纸面：低头书写才是专注。
     * 注意俯角较大时 ML Kit 的睁眼概率不可靠，因此低头区间内不判闭眼。
     */
    private fun judgePaperFront(face: Face, now: Long) {
        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX
        val leftEye = face.leftEyeOpenProbability
        val rightEye = face.rightEyeOpenProbability

        when {
            // 大幅扭头看别处：纸面任务下最典型的分心
            abs(yaw) > PAPER_HEAD_AWAY_YAW ->
                markInstant(true, true, "请不要东张西望，回到手头任务", 0L, now)

            // 低头区间：正在书写/看书，作为专注证据
            pitch >= PAPER_WORK_PITCH_MIN -> markInstant(false, false, "", 0L, now)

            // 仰头张望：持续 20 秒升级为作废级
            pitch <= PAPER_LOOK_UP_PITCH_MAX -> markInstant(
                true, false, "检测到抬头，请继续专注于手头任务", PAPER_LOOK_UP_SEVERE_MS, now
            )

            // 平视灰区里的闭眼：持续 30 秒升级为作废级
            leftEye != null && rightEye != null && leftEye < 0.2f && rightEye < 0.2f ->
                markInstant(true, false, "检测到闭眼，请保持清醒", PAPER_EYES_SEVERE_MS, now)

            // 平视灰区：既不算专注证据，也不算分心
            else -> markInstant(false, false, "", 0L, now)
        }
    }

    // ---------------------------------------------------------------- 后置俯拍

    /** 俯拍：只看人是否还在座位区域，忽略俯视角度下不可靠的姿态角 */
    private fun judgeOverhead(face: Face, now: Long, width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            markInstant(false, false, "", 0L, now)
            return
        }
        val box = face.boundingBox
        val cx = (box.left + box.right) / 2f / width
        val cy = (box.top + box.bottom) / 2f / height
        val offX = abs(cx - 0.5f)
        val offY = abs(cy - 0.5f)
        if (offX > OVERHEAD_MAX_OFFSET || offY > OVERHEAD_MAX_OFFSET) {
            val escalate = if (task == Task.PAPER) PAPER_OFF_SEAT_SEVERE_MS else 0L
            val reason = if (task == Task.PAPER) {
                "已离开座位区域，请回到座位"
            } else {
                "已偏离监控范围，请回到座位"
            }
            markInstant(true, false, reason, escalate, now)
        } else {
            markInstant(false, false, "", 0L, now)
        }
    }

    private fun markInstant(
        value: Boolean,
        severe: Boolean,
        reason: String,
        escalateMs: Long,
        now: Long,
        note: String = ""
    ) {
        if (instantDistracted != value || instantSevere != severe) {
            instantDistracted = value
            instantSevere = severe
            instantSince = now
        }
        instantEscalateMs = escalateMs
        if (value && reason.isNotEmpty()) lastReason = reason
        if (!value && note.isNotEmpty()) lastNote = note
    }

    companion object {
        /** 盯屏：无人脸多久判分心 */
        private const val NO_FACE_DELAY_MS = 4000L

        /** 盯屏俯拍：先提醒、久未出现才升级为作废级 */
        private const val OVERHEAD_NO_FACE_DELAY_MS = 6000L
        private const val OVERHEAD_SEVERE_NO_FACE_MS = 25_000L

        /** 纸面前置：无脸只做提示，不计分心 */
        private const val PAPER_FRONT_NO_FACE_HINT_MS = 60_000L

        /**
         * 纸面俯拍：显著变化格占比达到该值即视为手在动。
         * 空桌面噪声峰值 0.26%，书写实测 11%~78%，取 1% 留足余量。
         */
        private const val PAPER_ACTIVE_MIN = 0.01f

        /**
         * 纸面俯拍：画面静止多久开始提示、多久升级为作废级。
         * 实测书写中停笔思考会有 5~10 秒的完全静止，取 12 秒避免误报。
         */
        private const val PAPER_QUIET_HINT_MS = 12_000L
        private const val PAPER_QUIET_SEVERE_MS = 45_000L

        private const val HOLD_MS = 1500L
        private const val HEAD_AWAY_YAW = 42f
        private const val LOOK_DOWN_PITCH = 32f
        private const val OVERHEAD_MAX_OFFSET = 0.40f

        /** 纸面任务：扭头阈值放宽，仰头阈值与升级时长 */
        private const val PAPER_HEAD_AWAY_YAW = 45f
        private const val PAPER_WORK_PITCH_MIN = 18f
        private const val PAPER_LOOK_UP_PITCH_MAX = -8f
        private const val PAPER_LOOK_UP_SEVERE_MS = 20_000L
        private const val PAPER_EYES_SEVERE_MS = 30_000L
        private const val PAPER_OFF_SEAT_SEVERE_MS = 30_000L
    }
}
