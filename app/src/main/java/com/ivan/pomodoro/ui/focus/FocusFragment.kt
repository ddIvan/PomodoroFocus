package com.ivan.pomodoro.ui.focus

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
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
import com.ivan.pomodoro.monitor.MonitorScene
import com.ivan.pomodoro.session.FocusSessionManager
import com.ivan.pomodoro.ui.MainActivity
import com.ivan.pomodoro.ui.common.SessionAdapter
import com.ivan.pomodoro.ui.session.FocusSessionActivity
import com.ivan.pomodoro.ui.settings.MonitorSettingsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.util.TimeUtils
import com.ivan.pomodoro.util.TtsSpeaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class FocusFragment : Fragment() {

    private companion object {
        /** 入账动效总长：弹出 300ms + 飞行 400ms + 波纹与数字收尾 200ms */
        const val GAIN_TOTAL_MS = 900f
        const val GAIN_FLIGHT_START = 300f
        const val GAIN_FLIGHT_MS = 400f

        /** 等数据流把孩子落地的新值推上来再播，超时则按当前值兜底 */
        const val GAIN_PENDING_MS = 350L
    }

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

    /** 「检测语音」用的引擎实例，离开页面即释放 */
    private var voiceSpeaker: TtsSpeaker? = null

    /** 入账动效进行中：这段时间数字由动画接管，数据流不许覆盖 */
    private var gainAnimating = false

    /** 已收到「刚完成一个番茄」，在等数据流或超时后开播 */
    private var gainPending = false

    private var gainAnimator: ValueAnimator? = null

    private val gainWatchdog = Runnable {
        if (!gainPending) return@Runnable
        gainPending = false
        startGainAnimation()
    }

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

        // 参数按当前计划的场景打开：视角取自开关，盯屏/纸面取自计划的任务类型
        binding.tvMonitorTune.setOnClickListener {
            val scene = MonitorScene.of(
                prefs.monitorLens == Prefs.LENS_BACK,
                currentPlan?.taskMode == FocusPlan.TASK_PAPER
            )
            startActivity(MonitorSettingsActivity.newIntent(requireContext(), scene))
        }

        binding.switchVoice.isChecked = prefs.voiceOnDistraction
        binding.switchVoice.setOnCheckedChangeListener { _, checked ->
            prefs.voiceOnDistraction = checked
        }

        // 检测语音：播一句验证引擎真的出声，缺语音包时直接说清该去哪里设置
        binding.tvVoiceTest.setOnClickListener {
            val speaker = voiceSpeaker ?: TtsSpeaker(requireContext()).also { s ->
                s.onUnavailable = {
                    val ctx = context
                    if (ctx != null) {
                        Toast.makeText(
                            ctx,
                            "系统语音引擎不可用：请在「设置 → 辅助功能 → 文本转语音」中启用引擎并下载中文语音包",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                voiceSpeaker = s
            }
            speaker.prepare()
            speaker.speak("可以播放语音")
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
                        // 数据到位再开播，避免先按旧值演一遍 0 → 1 再跳成真实值
                        if (gainPending) {
                            gainPending = false
                            binding.root.removeCallbacks(gainWatchdog)
                            binding.root.postDelayed({ startGainAnimation() }, 60L)
                        }
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
            "笔在动、手在动算专注，画面长时间不动才算分心"
        } else {
            "持续离开镜头才会判定本次未通过"
        }
        binding.tvMonitorRuleHint.text = if (paper) {
            "纸面任务：低头书写、看不到脸都是常态，只要画面里还有笔或手在动就算专注；画面长时间没有动静（离座）才会提示分心。画面仅本机分析，不保存不上传。"
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
        // 动画期间数字按自己的节奏走，数据流这时候推上来会把中间态顶掉
        if (!gainAnimating) binding.tvTodayTomatoes.text = todayTomatoes.toString()
        val target = currentPlan?.dailyTarget ?: 0
        binding.tvTodayTarget.text = if (target > 0) "/ $target 个" else ""
        binding.progressTarget.max = target.coerceAtLeast(1)
        binding.progressTarget.setProgressCompat(
            if (target > 0) todayTomatoes.coerceAtMost(target) else 0, true
        )
    }

    // ------------------------------------------------------------ 获取番茄动效

    /**
     * 专注成功结束后由 MainActivity 调用：一枚番茄从页面中部弹起、飞进「今日番茄」，
     * 落点荡开一圈光晕，数字翻成新值，同时语音报喜。
     *
     * 先挂起再等一次数据流：专注页落库和首页订阅之间有时序差，直接播会用旧值演一遍 0 → 1。
     */
    fun playTomatoGain() {
        if (_binding == null) return
        if (gainAnimating || gainPending) return
        gainPending = true
        binding.root.postDelayed(gainWatchdog, GAIN_PENDING_MS)
    }

    private fun startGainAnimation() {
        val b = _binding ?: return
        if (gainAnimating) return
        // 刚从专注页切回来时布局可能还没测量，等一帧再量坐标
        if (b.root.width <= 0 || b.root.height <= 0) {
            b.root.post { startGainAnimation() }
            return
        }
        animateGain(b, todayTomatoes.coerceAtLeast(1))
    }

    private fun animateGain(b: FragmentFocusBinding, newValue: Int) {
        val overlay = b.root
        val tomato = b.ivGainTomato
        val ripple = b.viewGainRipple
        val num = b.tvTodayTomatoes
        val card = b.cardTodayGoal
        val density = resources.displayMetrics.density

        // 首页可能停在滚动中途，先回到顶部，落点才对得上卡片
        if (b.scrollFocus.scrollY != 0) b.scrollFocus.scrollTo(0, 0)

        val rootLoc = IntArray(2)
        val numLoc = IntArray(2)
        overlay.getLocationOnScreen(rootLoc)
        num.getLocationOnScreen(numLoc)

        // 落点取数字圆心；飞行体和光晕都按居中布局的初始位置算相对位移
        val targetCx = numLoc[0] - rootLoc[0] + num.width / 2f
        val targetCy = numLoc[1] - rootLoc[1] + num.height / 2f
        val baseX = (overlay.width - tomato.width) / 2f
        val baseY = (overlay.height - tomato.height) / 2f
        val endX = targetCx - tomato.width / 2f - baseX
        val endY = targetCy - tomato.height / 2f - baseY

        // 起点在页面中部偏下，像是从计时区域升起来
        val startY = overlay.height * 0.12f
        val lift = 44f * density

        val oldValue = (newValue - 1).coerceAtLeast(0)
        gainAnimating = true
        num.text = oldValue.toString()
        speakGain()

        tomato.visibility = View.VISIBLE
        tomato.alpha = 0f
        tomato.rotation = 0f
        tomato.translationX = 0f
        tomato.translationY = startY
        tomato.scaleX = 0.45f
        tomato.scaleY = 0.45f

        ripple.visibility = View.INVISIBLE
        ripple.alpha = 0f
        ripple.translationX = targetCx - ripple.width / 2f - (overlay.width - ripple.width) / 2f
        ripple.translationY = targetCy - ripple.height / 2f - (overlay.height - ripple.height) / 2f
        ripple.scaleX = 0.5f
        ripple.scaleY = 0.5f

        var switched = false
        val animator = ValueAnimator.ofFloat(0f, GAIN_TOTAL_MS)
        animator.duration = GAIN_TOTAL_MS.toLong()
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { va ->
            val t = va.animatedValue as Float

            // 弹出：先过冲到 1.16 再回落，这一下决定动画有没有"得到东西"的手感
            var scale: Float
            var alpha: Float
            if (t < 200f) {
                val p = t / 200f
                scale = 0.45f + (1.16f - 0.45f) * gainOutBack(p, 2.2f)
                alpha = p
            } else {
                val p = ((t - 200f) / 100f).coerceIn(0f, 1f)
                scale = 1.16f + (1f - 1.16f) * gainOutCubic(p)
                alpha = 1f
            }

            var x = 0f
            var y = startY
            var rot = 0f
            if (t > GAIN_FLIGHT_START) {
                val p = gainInOutCubic(
                    ((t - GAIN_FLIGHT_START) / GAIN_FLIGHT_MS).coerceIn(0f, 1f)
                )
                x = endX * p
                // 抛物线抬升，直线飞过去会显得很机械
                y = startY + (endY - startY) * p - sin(p * PI.toFloat()) * lift
                rot = 18f * p
                scale += (0.30f - scale) * p
            }
            tomato.translationX = x
            tomato.translationY = y
            tomato.rotation = rot
            tomato.scaleX = scale
            tomato.scaleY = scale
            tomato.alpha = if (t > 700f) (1f - (t - 700f) / 80f).coerceIn(0f, 1f) else alpha

            // 落点光晕
            if (t >= 600f) {
                val p = ((t - 600f) / 280f).coerceIn(0f, 1f)
                ripple.visibility = View.VISIBLE
                ripple.alpha = 0.55f * (1f - p)
                val rs = 0.5f + 1.9f * gainOutCubic(p)
                ripple.scaleX = rs
                ripple.scaleY = rs
            }

            // 卡片被打中的一下回弹
            val bounce = if (t in 600f..880f) {
                sin(((t - 600f) / 280f) * PI.toFloat()) * 0.035f
            } else 0f
            card.scaleX = 1f + bounce
            card.scaleY = 1f + bounce

            // 数字翻入，和卡片回弹同一拍
            if (!switched && t >= 620f) {
                switched = true
                num.text = newValue.toString()
                num.translationY = 24f * density
                num.alpha = 0.15f
                num.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(260L)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                tomato.visibility = View.INVISIBLE
                ripple.visibility = View.INVISIBLE
                card.scaleX = 1f
                card.scaleY = 1f
                num.translationY = 0f
                num.alpha = 1f
                gainAnimating = false
                gainAnimator = null
                _binding?.let {
                    if (todayTomatoes >= newValue) {
                        updateTodayCard()
                    } else {
                        // 数据流还没跟上，先用动画算出的值占位，免得数字倒回旧值跳一下
                        it.tvTodayTomatoes.text = newValue.toString()
                    }
                }
            }
        })
        gainAnimator = animator
        animator.start()
    }

    /** 完成一枚番茄的报喜。引擎还没就绪时 TtsSpeaker 会自己排队，这里不等 */
    private fun speakGain() {
        if (!prefs.voiceOnComplete) return
        val ctx = context ?: return
        val speaker = voiceSpeaker ?: TtsSpeaker(ctx).also { voiceSpeaker = it }
        speaker.speak("您已获得一个番茄")
    }

    private fun gainOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    private fun gainInOutCubic(t: Float): Float {
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            val u = -2f * t + 2f
            1f - u * u * u / 2f
        }
    }

    /** 带过冲的弹性缓动，弹出那一下才有重量 */
    private fun gainOutBack(t: Float, c1: Float): Float {
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
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
        gainAnimator?.cancel()
        gainAnimator = null
        gainAnimating = false
        gainPending = false
        _binding?.root?.removeCallbacks(gainWatchdog)
        voiceSpeaker?.shutdown()
        voiceSpeaker = null
        super.onDestroyView()
        _binding = null
    }
}
