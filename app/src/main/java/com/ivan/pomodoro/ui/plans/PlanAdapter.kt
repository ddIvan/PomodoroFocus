package com.ivan.pomodoro.ui.plans

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ivan.pomodoro.R
import com.ivan.pomodoro.data.entity.FocusPlan
import com.ivan.pomodoro.databinding.ItemPlanBinding

class PlanAdapter(
    private val onSelect: (FocusPlan) -> Unit,
    private val onMore: (View, FocusPlan) -> Unit
) : ListAdapter<FocusPlan, PlanAdapter.VH>(DIFF) {

    var currentPlanId: Long = 0L

    class VH(val binding: ItemPlanBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPlanBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val plan = getItem(position)
        val b = holder.binding
        val context = holder.itemView.context

        b.tvName.text = plan.name
        b.tvGoal.text = plan.goal.ifBlank { "未设置专注目标" }
        val tags = StringBuilder(
            "${plan.durationMinutes} 分钟 · ${plan.frequencyText()} · 每日 ${plan.dailyTarget} 个"
        )
        if (plan.monitorEnabled) {
            tags.append(" · 监控 · ").append(plan.taskModeText())
        }
        b.tvTags.text = tags.toString()

        val isCurrent = plan.id == currentPlanId
        b.tvCurrent.isVisible = isCurrent
        b.cardRoot.setStrokeColor(
            ContextCompat.getColor(
                context,
                if (isCurrent) R.color.pomo_primary else R.color.pomo_divider
            )
        )
        b.cardRoot.strokeWidth = if (isCurrent) (1.4f * context.resources.displayMetrics.density).toInt() else 1
        b.cardRoot.setOnClickListener { onSelect(plan) }
        b.btnMore.setOnClickListener { onMore(it, plan) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FocusPlan>() {
            override fun areItemsTheSame(oldItem: FocusPlan, newItem: FocusPlan): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: FocusPlan, newItem: FocusPlan): Boolean =
                oldItem == newItem
        }
    }
}
