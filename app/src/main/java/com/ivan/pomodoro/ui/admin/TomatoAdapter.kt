package com.ivan.pomodoro.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ivan.pomodoro.data.AdminRepository
import com.ivan.pomodoro.data.entity.FocusSession
import com.ivan.pomodoro.databinding.ItemTomatoBinding
import com.ivan.pomodoro.databinding.ItemTomatoDayBinding
import com.ivan.pomodoro.util.TimeUtils

/**
 * 番茄管理列表：按日期分组，组内每个番茄一行，可单独删除。
 * 只有"计入统计的番茄"（status = 完成）会出现在这里。
 */
class TomatoAdapter(
    private val onDelete: (day: String, session: FocusSession, indexInDay: Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val day: AdminRepository.DayTomatoes) : Row()
        data class Item(
            val dateKey: String,
            val session: FocusSession,
            val indexInDay: Int
        ) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(days: List<AdminRepository.DayTomatoes>) {
        rows.clear()
        days.forEach { day ->
            rows.add(Row.Header(day))
            day.sessions.forEachIndexed { index, session ->
                rows.add(Row.Item(day.dateKey, session, index))
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemTomatoDayBinding.inflate(inflater, parent, false))
        } else {
            ItemVH(ItemTomatoBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(row.day)
            is Row.Item -> (holder as ItemVH).bind(row)
        }
    }

    private class HeaderVH(private val b: ItemTomatoDayBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(day: AdminRepository.DayTomatoes) {
            b.tvDayTitle.text = TimeUtils.parseDateKey(day.dateKey)?.let { TimeUtils.dayTitle(it) } ?: day.dateKey
            b.tvDayCount.text = "${day.count} 个番茄 · ${day.minutes} 分钟"
        }
    }

    private inner class ItemVH(private val b: ItemTomatoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.Item) {
            val session = row.session
            b.tvTomatoTitle.text = buildString {
                append("第 ${row.indexInDay + 1} 个 · ")
                append(TimeUtils.timeOfDay(session.startTime))
                append(" · ")
                append(TimeUtils.durationText(session.actualSeconds))
            }
            b.tvTomatoMeta.text = "${session.planName.ifBlank { "专注" }} · ${session.taskModeText()}"
            b.btnDeleteTomato.setOnClickListener {
                onDelete(row.dateKey, session, row.indexInDay)
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
