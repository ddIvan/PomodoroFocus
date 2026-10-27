package com.ivan.pomodoro.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.data.FocusRepository
import com.ivan.pomodoro.data.entity.FocusSession
import com.ivan.pomodoro.data.model.DailyStat
import com.ivan.pomodoro.databinding.FragmentStatsBinding
import com.ivan.pomodoro.ui.common.SessionAdapter
import com.ivan.pomodoro.util.TimeUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private val repository: FocusRepository by lazy {
        (requireActivity().application as PomodoroApp).repository
    }

    private lateinit var adapter: SessionAdapter
    private var currentMonth: YearMonth = YearMonth.now()
    private var selectedDate: LocalDate = LocalDate.now()

    /** 当月每天的汇总数据，用于在明细被清空后仍显示正确的番茄数与时长 */
    private var monthStats: Map<LocalDate, DailyStat> = emptyMap()

    private val monthFlow = MutableStateFlow(YearMonth.now())
    private val selectedFlow = MutableStateFlow(LocalDate.now())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SessionAdapter()
        binding.rvDaySessions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDaySessions.adapter = adapter

        binding.tvMonth.text = TimeUtils.monthTitle(currentMonth)
        binding.btnNextMonth.isEnabled = false
        updateSelectedTitle()

        binding.calendar.onDayClick = { date ->
            selectedDate = date
            selectedFlow.value = date
            binding.calendar.setSelected(date)
            updateSelectedTitle()
            updateSelectedSummary()
        }
        binding.btnPrevMonth.setOnClickListener { changeMonth(-1) }
        binding.btnNextMonth.setOnClickListener { changeMonth(1) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    monthFlow.flatMapLatest { month ->
                        repository.observeDailyStats(month.atDay(1), month.atEndOfMonth())
                    }.collect { stats ->
                        val counts = HashMap<LocalDate, Int>()
                        val byDate = HashMap<LocalDate, DailyStat>()
                        var total = 0
                        var seconds = 0
                        stats.forEach { stat ->
                            TimeUtils.parseDateKey(stat.dateKey)?.let {
                                counts[it] = stat.count
                                byDate[it] = stat
                            }
                            total += stat.count
                            seconds += stat.seconds
                        }
                        monthStats = byDate
                        binding.tvMonthTomatoes.text = total.toString()
                        binding.tvMonthMinutes.text = "本月专注 ${seconds / 60} 分钟"
                        binding.calendar.submit(currentMonth, counts, selectedDate)
                        updateSelectedSummary()
                    }
                }
                launch {
                    repository.observeTotalTomatoes().collect {
                        binding.tvTotalTomatoes.text = it.toString()
                    }
                }
                launch {
                    selectedFlow.flatMapLatest { date ->
                        repository.observeSessionsOfDay(date)
                    }.collect { list ->
                        onDaySessions(list)
                    }
                }
                launch {
                    val dates = repository.completedDates()
                        .mapNotNull { TimeUtils.parseDateKey(it) }
                        .toSet()
                    binding.tvStreak.text = "连续 ${calcStreak(dates)} 天"
                }
            }
        }
    }

    private fun calcStreak(dates: Set<LocalDate>): Int {
        var cursor = LocalDate.now()
        if (!dates.contains(cursor)) cursor = cursor.minusDays(1)
        var streak = 0
        while (dates.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun onDaySessions(list: List<FocusSession>) {
        adapter.submitList(list)
        binding.tvEmptyDay.isVisible = list.isEmpty()
        binding.rvDaySessions.isVisible = list.isNotEmpty()
        updateSelectedTitle()
        updateSelectedSummary()
    }

    /** 摘要取当月汇总数据，明细被清空后仍能显示当天的番茄数与专注时长 */
    private fun updateSelectedSummary() {
        val stat = monthStats[selectedDate]
        val tomatoes = stat?.count ?: 0
        val minutes = (stat?.seconds ?: 0) / 60
        binding.tvSelectedSummary.text = "$tomatoes 个番茄 · $minutes 分钟"
        binding.tvEmptyDay.text = if (tomatoes > 0) {
            "当天记录明细已清空，番茄数已计入统计"
        } else {
            "这一天还没有收获番茄"
        }
    }

    private fun updateSelectedTitle() {
        binding.tvSelectedDay.text = TimeUtils.dayTitle(selectedDate)
    }

    private fun changeMonth(delta: Int) {
        val target = currentMonth.plusMonths(delta.toLong())
        if (target > YearMonth.now()) return
        currentMonth = target
        monthFlow.value = target
        binding.tvMonth.text = TimeUtils.monthTitle(target)
        binding.btnNextMonth.isEnabled = target < YearMonth.now()

        val newSelected = if (target == YearMonth.now()) LocalDate.now() else target.atDay(1)
        selectedDate = newSelected
        selectedFlow.value = newSelected
        binding.calendar.setSelected(newSelected)
        updateSelectedTitle()
        updateSelectedSummary()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
