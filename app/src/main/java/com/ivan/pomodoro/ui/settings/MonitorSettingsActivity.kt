package com.ivan.pomodoro.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.ivan.pomodoro.R
import com.ivan.pomodoro.data.Prefs
import com.ivan.pomodoro.databinding.ActivityMonitorSettingsBinding
import com.ivan.pomodoro.monitor.MonitorProfile
import com.ivan.pomodoro.monitor.MonitorScene
import com.ivan.pomodoro.monitor.ProfilePreset
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 监控参数设置页。
 *
 * 参数按 [MonitorScene] 分成四组，每组独立保存。页面上半部分选场景，下半部分是
 * 该场景的可调项——表单由 [specs] 这张参数表驱动：换场景时同一套代码重建控件，
 * 不需要为每个场景各写一份布局，也不会出现"加了参数忘记加控件"的漏配。
 */
class MonitorSettingsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SCENE = "scene"

        fun newIntent(context: Context, scene: MonitorScene): Intent =
            Intent(context, MonitorSettingsActivity::class.java).putExtra(EXTRA_SCENE, scene.key)
    }

    private lateinit var binding: ActivityMonitorSettingsBinding
    private val prefs by lazy { Prefs(this) }

    private var scene = MonitorScene.FRONT_SCREEN
    private var profile = MonitorProfile.DEFAULT

    /** 程序回填预设按钮时压制回调，避免和用户点击互相触发 */
    private var suppressPresetCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitorSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scene = intent.getStringExtra(EXTRA_SCENE)?.let { MonitorScene.fromKey(it) }
            ?: MonitorScene.of(prefs.monitorLens == Prefs.LENS_BACK, false)
        profile = prefs.monitorProfile(scene)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvReset.setOnClickListener { confirmReset() }

        binding.chipsScene.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val next = sceneOf(id)
            if (next == scene) return@setOnCheckedStateChangeListener
            scene = next
            profile = prefs.monitorProfile(scene)
            render()
        }

        binding.togglePreset.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressPresetCallback) return@addOnButtonCheckedListener
            applyPreset(presetOf(checkedId) ?: return@addOnButtonCheckedListener)
        }

        binding.chipsScene.check(chipOf(scene))
        render()
    }

    // ------------------------------------------------------------ 状态

    private fun render() {
        binding.tvSceneSummary.text = scene.summary
        binding.tvProfileState.text = if (prefs.hasMonitorProfile(scene)) "已自定义" else "出厂参数"
        rebuildRows()
        syncPresetButtons()
    }

    private fun applyPreset(preset: ProfilePreset) {
        // 预设永远基于该场景的出厂值缩放，不在当前值上反复叠加
        profile = MonitorProfile.preset(MonitorProfile.defaultFor(scene), preset)
        prefs.saveMonitorProfile(scene, profile)
        render()
        toast("已套用「${preset.label}」预设")
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("恢复默认")
            .setMessage("「${scene.title}」的参数将恢复为出厂值。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                prefs.resetMonitorProfile(scene)
                profile = prefs.monitorProfile(scene)
                render()
                toast("已恢复出厂参数")
            }
            .show()
    }

    private fun syncPresetButtons() {
        val base = MonitorProfile.defaultFor(scene)
        val matched = ProfilePreset.values().firstOrNull {
            MonitorProfile.preset(base, it).toJson() == profile.toJson()
        }
        suppressPresetCallback = true
        if (matched == null) binding.togglePreset.clearChecked()
        else binding.togglePreset.check(buttonOf(matched))
        suppressPresetCallback = false
    }

    // ------------------------------------------------------------ 参数表

    /**
     * 一个可调参数。
     *
     * @param min/max/step 滑块范围，单位是"展示单位"（度、秒、百分比、毫秒）
     * @param read/write 展示单位与 [MonitorProfile] 字段的换算
     * @param visible 该参数只在哪些场景有意义；换场景时自动隐藏
     */
    private data class Spec(
        val group: String,
        val label: String,
        val hint: String,
        val min: Float,
        val max: Float,
        val step: Float,
        val read: (MonitorProfile) -> Float,
        val write: (MonitorProfile, Float) -> MonitorProfile,
        val format: (Float) -> String,
        val visible: (MonitorScene) -> Boolean = { true }
    ) {
        fun align(value: Float): Float {
            val clamped = value.coerceIn(min, max)
            val steps = ((clamped - min) / step).roundToInt()
            return BigDecimal(min.toDouble())
                .add(BigDecimal(step.toDouble()).multiply(BigDecimal(steps)))
                .toFloat()
        }
    }

    private fun specs(): List<Spec> = listOf(
        // ---------- 姿态 ----------
        Spec(
            "姿态判定", "扭头阈值", "头部左右偏离超过这个角度算看别处",
            20f, 70f, 1f,
            { it.yawLimit },
            { p, v -> p.copy(yawLimit = v) },
            { "${it.roundToInt()}°" },
            // 俯拍视角不看头部姿态，只判人脸位置与画面活动
            visible = { !it.lensBack }
        ),
        Spec(
            "姿态判定", "低头阈值", "前置盯屏：低头超过这个角度算没在看屏幕",
            10f, 60f, 1f,
            { it.lookDownPitch },
            { p, v -> p.copy(lookDownPitch = v) },
            { "${it.roundToInt()}°" },
            visible = { !it.paper && !it.lensBack }
        ),
        Spec(
            "姿态判定", "纸面低头上限", "前置纸面：pitch 小于这个值算低头书写（越低头数值越负）",
            -35f, 10f, 1f,
            { it.paperWorkPitchMax },
            { p, v -> p.copy(paperWorkPitchMax = v) },
            { "${it.roundToInt()}°" },
            visible = { it.paper && !it.lensBack }
        ),
        Spec(
            "姿态判定", "抬头阈值", "前置纸面：pitch 大于等于这个值算抬头张望",
            -30f, 20f, 1f,
            { it.paperLookUpPitchMin },
            { p, v -> p.copy(paperLookUpPitchMin = v) },
            { "${it.roundToInt()}°" },
            visible = { it.paper && !it.lensBack }
        ),
        Spec(
            "姿态判定", "闭眼概率", "双眼睁开概率都低于这个值算闭眼",
            5f, 80f, 1f,
            { it.eyeOpenMin * 100f },
            { p, v -> p.copy(eyeOpenMin = v / 100f) },
            { "${it.roundToInt()}%" },
            visible = { !it.lensBack }
        ),

        // ---------- 时间 ----------
        Spec(
            "时间门槛", "判定确认", "状态要持续这么久才上报，用来过滤眨眼和瞬时抖动",
            0.8f, 4f, 0.1f,
            { it.holdMs / 1000f },
            { p, v -> p.copy(holdMs = (v * 1000f).roundToInt().toLong()) },
            { seconds(it) }
        ),
        Spec(
            "时间门槛", "无人脸提醒", "看不到人多久开始提醒（盯屏场景判分心，俯拍场景先口头提醒）",
            2f, 20f, 0.5f,
            { if (scene.lensBack) it.overheadNoFaceDelayMs / 1000f else it.noFaceDelayMs / 1000f },
            { p, v ->
                if (scene.lensBack) p.copy(overheadNoFaceDelayMs = (v * 1000f).roundToInt().toLong())
                else p.copy(noFaceDelayMs = (v * 1000f).roundToInt().toLong())
            },
            { seconds(it) },
            // 纸面任务的两条路径都不走这个阈值：前置看头顶挡脸、俯拍看画面动静
            visible = { !it.paper }
        ),
        Spec(
            "时间门槛", "无脸提示", "前置纸面：头顶挡住脸是常态，超过这个时长才提醒",
            15f, 180f, 5f,
            { it.paperNoFaceHintMs / 1000f },
            { p, v -> p.copy(paperNoFaceHintMs = (v * 1000f).roundToInt().toLong()) },
            { seconds(it) },
            visible = { it.paper && !it.lensBack }
        ),
        Spec(
            "时间门槛", "无人脸作废", "俯拍盯屏：看不到人多久判本次失败",
            6f, 60f, 1f,
            { it.overheadNoFaceSevereMs / 1000f },
            { p, v -> p.copy(overheadNoFaceSevereMs = (v * 1000f).roundToInt().toLong()) },
            { seconds(it) },
            visible = { it.lensBack && !it.paper }
        ),
        Spec(
            "时间门槛", "抬头升级", "纸面前置：抬头持续多久判本次失败",
            5f, 90f, 5f,
            { it.lookUpSevereMs / 1000f },
            { p, v -> p.copy(lookUpSevereMs = (v * 1000f).roundToInt().toLong()) },
            { seconds(it) },
            visible = { it.paper && !it.lensBack }
        ),
        Spec(
            "时间门槛", "闭眼升级", "纸面前置：闭眼持续多久判本次失败",
            5f, 90f, 5f,
            { it.eyesSevereMs / 1000f },
            { p, v -> p.copy(eyesSevereMs = (v * 1000f).roundToInt().toLong()) },
            { seconds(it) },
            visible = { it.paper && !it.lensBack }
        ),
        // 注意：offSeatSevereMs（离座升级）不在界面暴露。纸面俯拍走的是画面活动分支，
        // 不会进入人脸偏移判定；盯屏俯拍的升级阈值固定为 0。这一项改任何值都不影响判定。
        Spec(
            "时间门槛", "纸面静止提示", "俯拍纸面：画面没动静多久开始提醒",
            3f, 60f, 1f,
            { it.paperQuietHintMs / 1000f },
            { p, v -> p.copy(paperQuietHintMs = (v * 1000f).roundToInt().toLong()) },
            { seconds(it) },
            visible = { it.lensBack && it.paper }
        ),
        Spec(
            "时间门槛", "纸面静止作废", "俯拍纸面：画面没动静多久判本次失败",
            15f, 180f, 5f,
            { it.paperQuietSevereMs / 1000f },
            { p, v -> p.copy(paperQuietSevereMs = (v * 1000f).roundToInt().toLong()) },
            { seconds(it) },
            visible = { it.lensBack && it.paper }
        ),

        // ---------- 画面与性能 ----------
        Spec(
            "画面与性能", "画面活动阈值", "俯拍纸面：变化格占比达到这个值算在写",
            1f, 100f, 1f,
            { it.paperActiveMin * 100f },
            { p, v -> p.copy(paperActiveMin = v / 100f) },
            { "${it.roundToInt()}%" },
            visible = { it.lensBack && it.paper }
        ),
        Spec(
            "画面与性能", "偏离容差", "俯拍盯屏：人脸离画面中心超过这个比例算离座",
            15f, 65f, 1f,
            { it.overheadMaxOffset * 100f },
            { p, v -> p.copy(overheadMaxOffset = v / 100f) },
            { "${it.roundToInt()}%" },
            // 纸面俯拍走画面活动判定，不看人脸偏移
            visible = { it.lensBack && !it.paper }
        ),
        Spec(
            "画面与性能", "分析间隔", "每多少毫秒分析一帧，调大更省电、反应更慢",
            200f, 1000f, 50f,
            { it.analysisIntervalMs.toFloat() },
            { p, v -> p.copy(analysisIntervalMs = v.roundToInt().toLong()) },
            { "${it.roundToInt()} 毫秒" }
        ),
        Spec(
            "画面与性能", "帧差阈值", "单格灰度差超过这个值才算画面在动",
            5f, 50f, 1f,
            { it.motionDiff.toFloat() },
            { p, v -> p.copy(motionDiff = v.roundToInt()) },
            { "${it.roundToInt()}" }
        )
    )

    // ------------------------------------------------------------ 控件

    private fun rebuildRows() {
        binding.containerParams.removeAllViews()
        var lastGroup = ""
        specs().filter { it.visible(scene) }.forEach { spec ->
            if (spec.group != lastGroup) {
                lastGroup = spec.group
                binding.containerParams.addView(groupHeader(spec.group))
            }
            binding.containerParams.addView(rowView(spec))
        }
    }

    private fun groupHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(ContextCompat.getColor(this@MonitorSettingsActivity, R.color.pomo_text))
        typeface = Typeface.DEFAULT_BOLD
        val top = if (binding.containerParams.childCount == 0) 2 else 18
        setPadding(0, dp(top), 0, 0)
    }

    private fun rowView(spec: Spec): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(this).apply {
            text = spec.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(this@MonitorSettingsActivity, R.color.pomo_text))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueLabel = TextView(this).apply {
            text = spec.format(spec.read(profile))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(
                ContextCompat.getColor(this@MonitorSettingsActivity, R.color.pomo_primary)
            )
            typeface = Typeface.DEFAULT_BOLD
        }

        head.addView(label)
        head.addView(valueLabel)
        wrapper.addView(head)

        if (spec.hint.isNotBlank()) {
            wrapper.addView(
                TextView(this).apply {
                    text = spec.hint
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(
                        ContextCompat.getColor(
                            this@MonitorSettingsActivity,
                            R.color.pomo_text_second
                        )
                    )
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }

        val slider = Slider(this).apply {
            valueFrom = spec.min
            valueTo = spec.max
            stepSize = spec.step
            value = runCatching { spec.align(spec.read(profile)) }.getOrDefault(spec.min)
            addOnChangeListener { _, newValue, fromUser ->
                if (!fromUser) return@addOnChangeListener
                valueLabel.text = spec.format(newValue)
                profile = spec.write(profile, newValue)
                prefs.saveMonitorProfile(scene, profile)
                binding.tvProfileState.text = "已自定义"
                syncPresetButtons()
            }
        }
        wrapper.addView(slider)
        return wrapper
    }

    // ------------------------------------------------------------ 工具

    private fun dp(value: Int): Int =
        (resources.displayMetrics.density * value).roundToInt()

    private fun seconds(v: Float): String =
        if (abs(v - v.roundToInt()) < 0.01f) "${v.roundToInt()} 秒" else "%.1f 秒".format(v)

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun chipOf(target: MonitorScene): Int = when (target) {
        MonitorScene.FRONT_SCREEN -> R.id.chipFrontScreen
        MonitorScene.FRONT_PAPER -> R.id.chipFrontPaper
        MonitorScene.BACK_SCREEN -> R.id.chipBackScreen
        MonitorScene.BACK_PAPER -> R.id.chipBackPaper
    }

    private fun sceneOf(chipId: Int): MonitorScene = when (chipId) {
        R.id.chipFrontPaper -> MonitorScene.FRONT_PAPER
        R.id.chipBackScreen -> MonitorScene.BACK_SCREEN
        R.id.chipBackPaper -> MonitorScene.BACK_PAPER
        else -> MonitorScene.FRONT_SCREEN
    }

    private fun presetOf(buttonId: Int): ProfilePreset? = when (buttonId) {
        R.id.btnPresetLoose -> ProfilePreset.LOOSE
        R.id.btnPresetNormal -> ProfilePreset.NORMAL
        R.id.btnPresetStrict -> ProfilePreset.STRICT
        else -> null
    }

    private fun buttonOf(preset: ProfilePreset): Int = when (preset) {
        ProfilePreset.LOOSE -> R.id.btnPresetLoose
        ProfilePreset.NORMAL -> R.id.btnPresetNormal
        ProfilePreset.STRICT -> R.id.btnPresetStrict
    }
}
