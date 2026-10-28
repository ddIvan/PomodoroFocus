package com.ivan.pomodoro.ui.plans

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.R
import com.ivan.pomodoro.data.FocusRepository
import com.ivan.pomodoro.data.Prefs
import com.ivan.pomodoro.data.entity.FocusPlan
import com.ivan.pomodoro.databinding.ActivityPlanEditBinding
import kotlinx.coroutines.launch

class PlanEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAN_ID = "plan_id"
    }

    private lateinit var binding: ActivityPlanEditBinding
    private val repository: FocusRepository by lazy {
        (application as PomodoroApp).repository
    }
    private val prefs: Prefs by lazy { Prefs(this) }

    private var planId = 0L
    private var existing: FocusPlan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlanEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        planId = intent.getLongExtra(EXTRA_PLAN_ID, 0L)
        setupListeners()
        setupDefaults()

        if (planId > 0L) {
            binding.tvTitle.text = "编辑计划"
            binding.btnDelete.isVisible = true
            lifecycleScope.launch {
                existing = repository.getPlan(planId)
                existing?.let { fillForm(it) }
            }
        } else {
            binding.tvTitle.text = "新建计划"
            binding.btnDelete.isVisible = false
        }
    }

    private fun setupDefaults() {
        binding.sliderDuration.value = 25f
        binding.sliderDailyTarget.value = 4f
        binding.tvDurationValue.text = "25 分钟"
        binding.tvDailyTargetValue.text = "4 个"
        binding.toggleFrequency.check(R.id.btnFreqDaily)
        binding.toggleStrictness.check(R.id.btnStrictStandard)
        binding.toggleTaskMode.check(R.id.btnTaskScreen)
        binding.chipWeekdays.isVisible = false
        updateTaskHint(FocusPlan.TASK_SCREEN)
        updateStrictHint(1, FocusPlan.TASK_SCREEN)
    }

    private fun setupListeners() {
        binding.sliderDuration.addOnChangeListener { _, value, _ ->
            binding.tvDurationValue.text = "${value.toInt()} 分钟"
        }
        binding.sliderDailyTarget.addOnChangeListener { _, value, _ ->
            binding.tvDailyTargetValue.text = "${value.toInt()} 个"
        }
        binding.toggleFrequency.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                binding.chipWeekdays.isVisible = checkedId == R.id.btnFreqCustom
            }
        }
        binding.toggleStrictness.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val strictness = when (checkedId) {
                    R.id.btnStrictLenient -> 0
                    R.id.btnStrictStrict -> 2
                    else -> 1
                }
                updateStrictHint(strictness, currentTaskMode())
            }
        }
        binding.toggleTaskMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val taskMode = if (checkedId == R.id.btnTaskPaper) {
                    FocusPlan.TASK_PAPER
                } else {
                    FocusPlan.TASK_SCREEN
                }
                updateTaskHint(taskMode)
                updateStrictHint(currentStrictness(), taskMode)
            }
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun currentTaskMode(): Int = when (binding.toggleTaskMode.checkedButtonId) {
        R.id.btnTaskPaper -> FocusPlan.TASK_PAPER
        else -> FocusPlan.TASK_SCREEN
    }

    private fun currentStrictness(): Int = when (binding.toggleStrictness.checkedButtonId) {
        R.id.btnStrictLenient -> 0
        R.id.btnStrictStrict -> 2
        else -> 1
    }

    private fun updateTaskHint(taskMode: Int) {
        binding.tvTaskHint.text = if (taskMode == FocusPlan.TASK_PAPER) {
            "写作业、看书：低头书写算专注；俯拍时笔和手在动就不判分心，画面长时间没动静才提示"
        } else {
            "看网课、看视频：面部朝向屏幕才算专注，低头、闭眼只提醒"
        }
    }

    private fun updateStrictHint(strictness: Int, taskMode: Int) {
        val paper = taskMode == FocusPlan.TASK_PAPER
        binding.tvStrictHint.text = if (paper) {
            when (strictness) {
                0 -> "只做页内提醒，不会判定失败；抬头、离座都不影响番茄"
                2 -> "抬头、闭眼也计入分心，连续 15 秒或分心占比超过 10% 即判定未通过"
                else -> "低头书写视为专注；连续抬头或离座 30 秒、分心占比超过 20% 才判定未通过"
            }
        } else {
            when (strictness) {
                0 -> "只做页内提醒，不会判定失败；低头看书、短暂离开都不影响番茄"
                2 -> "低头、闭眼也计入分心，连续 12 秒或累计 15% 时长即判定未通过"
                else -> "低头看书只提醒；连续离开镜头 25 秒或累计 30% 时长才判定未通过"
            }
        }
    }

    private fun fillForm(plan: FocusPlan) {
        binding.etName.setText(plan.name)
        binding.etGoal.setText(plan.goal)
        binding.sliderDuration.value = plan.durationMinutes.coerceIn(5, 120).toFloat()
        binding.sliderDailyTarget.value = plan.dailyTarget.coerceIn(1, 12).toFloat()
        binding.tvDurationValue.text = "${plan.durationMinutes} 分钟"
        binding.tvDailyTargetValue.text = "${plan.dailyTarget} 个"

        when (plan.frequencyType) {
            FocusPlan.FREQ_WEEKDAYS -> binding.toggleFrequency.check(R.id.btnFreqWeekdays)
            FocusPlan.FREQ_CUSTOM -> {
                binding.toggleFrequency.check(R.id.btnFreqCustom)
                binding.chipWeekdays.isVisible = true
                applyWeekdays(plan.weekdaysMask)
            }

            else -> binding.toggleFrequency.check(R.id.btnFreqDaily)
        }

        when (plan.strictness) {
            0 -> binding.toggleStrictness.check(R.id.btnStrictLenient)
            2 -> binding.toggleStrictness.check(R.id.btnStrictStrict)
            else -> binding.toggleStrictness.check(R.id.btnStrictStandard)
        }
        if (plan.taskMode == FocusPlan.TASK_PAPER) {
            binding.toggleTaskMode.check(R.id.btnTaskPaper)
        } else {
            binding.toggleTaskMode.check(R.id.btnTaskScreen)
        }
        binding.switchMonitorDefault.isChecked = plan.monitorEnabled
        updateTaskHint(plan.taskMode)
        updateStrictHint(plan.strictness, plan.taskMode)
    }

    private fun applyWeekdays(mask: Int) {
        val chips = listOf(
            binding.chipMon to 0, binding.chipTue to 1, binding.chipWed to 2,
            binding.chipThu to 3, binding.chipFri to 4, binding.chipSat to 5,
            binding.chipSun to 6
        )
        chips.forEach { (chip, bit) -> chip.isChecked = (mask shr bit) and 1 == 1 }
    }

    private fun save() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.tilName.error = "请输入计划名称"
            return
        }
        binding.tilName.error = null

        val frequencyType = when (binding.toggleFrequency.checkedButtonId) {
            R.id.btnFreqWeekdays -> FocusPlan.FREQ_WEEKDAYS
            R.id.btnFreqCustom -> FocusPlan.FREQ_CUSTOM
            else -> FocusPlan.FREQ_DAILY
        }

        var weekdaysMask = 0b1111111
        if (frequencyType == FocusPlan.FREQ_CUSTOM) {
            val chips = listOf(
                binding.chipMon to 0, binding.chipTue to 1, binding.chipWed to 2,
                binding.chipThu to 3, binding.chipFri to 4, binding.chipSat to 5,
                binding.chipSun to 6
            )
            weekdaysMask = 0
            chips.forEach { (chip, bit) ->
                if (chip.isChecked) weekdaysMask = weekdaysMask or (1 shl bit)
            }
            if (weekdaysMask == 0) {
                Toast.makeText(this, "请至少选择一天", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val strictness = when (binding.toggleStrictness.checkedButtonId) {
            R.id.btnStrictLenient -> 0
            R.id.btnStrictStrict -> 2
            else -> 1
        }

        val plan = (existing ?: FocusPlan()).copy(
            name = name,
            durationMinutes = binding.sliderDuration.value.toInt(),
            goal = binding.etGoal.text?.toString()?.trim().orEmpty(),
            frequencyType = frequencyType,
            weekdaysMask = weekdaysMask,
            dailyTarget = binding.sliderDailyTarget.value.toInt(),
            monitorEnabled = binding.switchMonitorDefault.isChecked,
            strictness = strictness,
            taskMode = currentTaskMode()
        )

        lifecycleScope.launch {
            val id = repository.savePlan(plan)
            if (prefs.selectedPlanId == 0L) prefs.selectedPlanId = id
            Toast.makeText(this@PlanEditActivity, "计划已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun confirmDelete() {
        val plan = existing ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("删除计划")
            .setMessage("确定要删除「${plan.name}」吗？已有的专注记录会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    repository.deletePlan(plan.id)
                    if (prefs.selectedPlanId == plan.id) prefs.selectedPlanId = 0L
                    finish()
                }
            }
            .show()
    }
}
