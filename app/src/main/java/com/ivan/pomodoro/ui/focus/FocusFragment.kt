package com.ivan.pomodoro.ui.focus

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.R
import com.ivan.pomodoro.data.FocusRepository
import com.ivan.pomodoro.data.Prefs
import com.ivan.pomodoro.data.entity.FocusPlan
import com.ivan.pomodoro.databinding.FragmentFocusBinding
import com.ivan.pomodoro.session.FocusSessionManager
import com.ivan.pomodoro.ui.MainActivity
import com.ivan.pomodoro.ui.common.SessionAdapter
import com.ivan.pomodoro.ui.session.FocusSessionActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.util.TimeUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class FocusFragment : Fragment() {

    private var _binding: FragmentFocusBinding? = null
    private val binding get() = _binding!!

    private val repository: FocusRepository by lazy {
        (requireActivity().application as PomodoroApp).repository
    }
    private val prefs: Prefs by lazy { Prefs(requireContext()) }

    private lateinit var adapter: SessionAdapter
    private var currentPlan: FocusPlan? = null
    private var todayTomatoes = 0
    private var todayRecordsEmpty = true

    /** 当前展示的日期；跨天回到首页时切换，触发数据重新订阅 */
    private val dayFlow = MutableStateFlow(LocalDate.now())

    /** 程序化写入开关时跳过监听，避免把默认值当成用户操作持久化 */
    private var suppressMonitorCallback = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFocusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvDate.text = TimeUtils.dayTitle(LocalDate.now())
        binding.tvGreeting.text = greeting()

        adapter = SessionAdapter()
        binding.rvToday.layoutManager = LinearLayoutManager(requireContext())
        binding.rvToday.adapter = adapter

        binding.cardPlan.setOnClickListener { openPlansTab() }
        binding.tvPlanAction.setOnClickListener { openPlansTab() }
        binding.btnStart.setOnClickListener { startFocus() }

        // 恢复上次的监控开关，之后的改动实时持久化
        binding.switchMonitor.isChecked = prefs.monitorEnabled ?: false
        binding.switchMonitor.setOnCheckedChangeListener { _, checked ->
            if (suppressMonitorCallback) return@setOnCheckedChangeListener
            prefs.monitorEnabled = checked
        }

        binding.toggleLens.check(
            if (prefs.monitorLens == Prefs.LENS_BACK) R.id.btnLensBack else R.id.btnLensFront
        )
        binding.toggleLens.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.monitorLens =
                if (checkedId == R.id.btnLensBack) Prefs.LENS_BACK else Prefs.LENS_FRONT
        }

        binding.switchVoice.isChecked = prefs.voiceOnDistraction
        binding.switchVoice.setOnCheckedChangeListener { _, checked ->
            prefs.voiceOnDistraction = checked
        }

        binding.tvClearToday.setOnClickListener { confirmClearToday() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observePlans().collect { onPlansChanged(it) }
                }
                launch {
                    dayFlow.flatMapLatest { repository.observeDayTomatoes(it) }.collect {
                        todayTomatoes = it
                        updateTodayCard()
                        updateEmptyTodayText()
                    }
                }
                launch {
                    dayFlow.flatMapLatest { repository.observeDaySeconds(it) }.collect {
                        binding.tvTodayFocus.text = "今日已专注 ${it / 60} 分钟"
                    }
                }
                launch {
                    dayFlow.flatMapLatest { repository.observeSessionsOfDay(it) }.collect { list ->
                        adapter.submitList(list)
                        todayRecordsEmpty = list.isEmpty()
                        binding.tvEmptyToday.isVisible = list.isEmpty()
                        binding.tvTodayCount.text = "${list.size} 条"
                        binding.tvClearToday.isVisible = list.isNotEmpty()
                        updateEmptyTodayText()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDayIfNeeded()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshDayIfNeeded()
    }

    /** 跨天回到首页时，把今天的数据重新订阅一遍，避免仍显示昨天的记录 */
    private fun refreshDayIfNeeded() {
        if (_binding == null) return
        val today = LocalDate.now()
        if (today == dayFlow.value) return
        dayFlow.value = today
        binding.tvDate.text = TimeUtils.dayTitle(today)
        binding.tvGreeting.text = greeting()
    }

    private fun greeting(): String = when (LocalTime.now().hour) {
        in 5..10 -> "早上好，开始专注"
        in 11..13 -> "中午好，来一个番茄"
        in 14..17 -> "下午好，保持节奏"
        in 18..22 -> "晚上好，静心专注"
        else -> "夜深了，注意休息"
    }

    private fun onPlansChanged(list: List<FocusPlan>) {
        val selected = list.firstOrNull { it.id == prefs.selectedPlanId } ?: list.firstOrNull()
        currentPlan = selected
        if (selected != null) {
            if (selected.id != prefs.selectedPlanId) prefs.selectedPlanId = selected.id
            binding.tvPlanName.text = selected.name
            binding.tvPlanMeta.text = buildPlanMeta(selected)
            // 用户手动设置过就以用户的选择为准，否则沿用计划默认值
            if (prefs.monitorEnabled == null) {
                setMonitorSwitchSilently(selected.monitorEnabled)
            }
            binding.switchMonitor.isEnabled = true
        } else {
            binding.tvPlanName.text = "还没有计划"
            binding.tvPlanMeta.text = "点击创建你的第一个专注计划"
            if (prefs.monitorEnabled == null) setMonitorSwitchSilently(false)
            binding.switchMonitor.isEnabled = false
        }
        updateMonitorHints(selected)
        updateTodayCard()
    }

    /** 监控说明随当前计划的任务类型切换，避免用盯屏的说法解释纸面任务 */
    private fun updateMonitorHints(plan: FocusPlan?) {
        val paper = plan?.taskMode == FocusPlan.TASK_PAPER
        binding.tvMonitorHint.text = if (paper) {
            "低头书写算专注，抬头张望或离座才算分心"
        } else {
            "持续离开镜头才会判定本次未通过"
        }
        binding.tvMonitorRuleHint.text = if (paper) {
            "纸面任务：低头书写、暂时看不到脸都属于常态，不会因此判定失败；抬头张望、离开座位才算分心。画面仅本机分析，不保存不上传。"
        } else {
            "前置正对：低头看书、闭眼只做提醒；后置俯拍：手机架高朝下，离开座位才算分心。画面仅本机分析，不保存不上传。"
        }
    }

    /** 程序化同步开关状态，不写入偏好 */
    private fun setMonitorSwitchSilently(checked: Boolean) {
        if (binding.switchMonitor.isChecked == checked) return
        suppressMonitorCallback = true
        binding.switchMonitor.isChecked = checked
        suppressMonitorCallback = false
    }

    private fun buildPlanMeta(plan: FocusPlan): String {
        val taskTag = if (plan.monitorEnabled) " · ${plan.taskModeText()}" else ""
        val base =
            "${plan.durationMinutes} 分钟 · ${plan.frequencyText()} · 每日 ${plan.dailyTarget} 个$taskTag"
        val withGoal = if (plan.goal.isBlank()) base else "$base · ${plan.goal}"
        return if (plan.scheduledOn(LocalDate.now())) {
            withGoal
        } else {
            "$withGoal\n今天不是该计划的执行日，仍可手动开始"
        }
    }

    /** 记录被清空但当天仍有番茄时，明确说明番茄已计入统计，避免误以为数据丢了 */
    private fun updateEmptyTodayText() {
        if (!todayRecordsEmpty) return
        binding.tvEmptyToday.text = if (todayTomatoes > 0) {
            "今日记录已清空\n番茄数已计入统计"
        } else {
            "今天还没有专注记录\n完成一个完整时段即可收获一枚番茄"
        }
    }

    private fun updateTodayCard() {
        binding.tvTodayTomatoes.text = todayTomatoes.toString()
        val target = currentPlan?.dailyTarget ?: 0
        binding.tvTodayTarget.text = if (target > 0) "/ $target 个" else ""
        binding.progressTarget.max = target.coerceAtLeast(1)
        binding.progressTarget.setProgressCompat(
            if (target > 0) todayTomatoes.coerceAtMost(target) else 0, true
        )
    }

    private fun confirmClearToday() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清空今日记录")
            .setMessage("今天的记录明细会被清空，今日番茄数、专注时长等统计不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val count = repository.clearDay(LocalDate.now())
                    Toast.makeText(
                        requireContext(),
                        if (count > 0) "已清空 $count 条今日记录" else "今天没有可清空的记录",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun openPlansTab() {
        (activity as? MainActivity)?.selectTab(R.id.nav_plans)
    }

    private fun startFocus() {
        val plan = currentPlan
        if (plan == null) {
            Toast.makeText(requireContext(), "请先创建一个专注计划", Toast.LENGTH_SHORT).show()
            openPlansTab()
            return
        }
        if (FocusSessionManager.isActive) {
            startActivity(Intent(requireContext(), FocusSessionActivity::class.java))
            return
        }
        startActivity(
            Intent(requireContext(), FocusSessionActivity::class.java).apply {
                putExtra(FocusSessionActivity.EXTRA_PLAN_ID, plan.id)
                putExtra(FocusSessionActivity.EXTRA_MONITOR, binding.switchMonitor.isChecked)
                putExtra(FocusSessionActivity.EXTRA_LENS, prefs.monitorLens)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
