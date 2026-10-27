package com.ivan.pomodoro.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ivan.pomodoro.R
import com.ivan.pomodoro.data.entity.FocusSession
import com.ivan.pomodoro.databinding.ItemSessionBinding
import com.ivan.pomodoro.util.TimeUtils

class SessionAdapter : ListAdapter<FocusSession, SessionAdapter.VH>(DIFF) {

    class VH(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val context = holder.itemView.context
        b.tvTime.text = TimeUtils.timeOfDay(item.startTime)
        b.tvName.text = item.planName.ifBlank { "自由专注" }

        val minutes = (item.actualSeconds / 60).coerceAtLeast(0)
        /* 监控任务补充任务类型快照，方便回溯这次是盯屏还是纸面 */
        val taskTag = if (item.monitorEnabled) " · ${item.taskModeText()}" else ""
        when (item.status) {
            FocusSession.STATUS_COMPLETED -> {
                b.tvMeta.text = "${item.plannedSeconds / 60} 分钟 · 完整完成$taskTag"
                b.tvBadge.text = "番茄 +1"
                b.tvBadge.setTextColor(ContextCompat.getColor(context, R.color.pomo_primary))
                b.tvBadge.setBackgroundResource(R.drawable.bg_tag_primary)
            }

            FocusSession.STATUS_INVALID -> {
                b.tvMeta.text = "$minutes 分钟 · 分心未通过$taskTag"
                b.tvBadge.text = "未计入"
                b.tvBadge.setTextColor(ContextCompat.getColor(context, R.color.pomo_text_second))
                b.tvBadge.setBackgroundResource(R.drawable.bg_tag_gray)
            }

            else -> {
                b.tvMeta.text = "$minutes 分钟 · 手动结束$taskTag"
                b.tvBadge.text = "未计入"
                b.tvBadge.setTextColor(ContextCompat.getColor(context, R.color.pomo_text_second))
                b.tvBadge.setBackgroundResource(R.drawable.bg_tag_gray)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FocusSession>() {
            override fun areItemsTheSame(oldItem: FocusSession, newItem: FocusSession): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: FocusSession, newItem: FocusSession): Boolean =
                oldItem == newItem
        }
    }
}
