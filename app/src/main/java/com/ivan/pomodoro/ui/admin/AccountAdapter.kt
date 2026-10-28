package com.ivan.pomodoro.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ivan.pomodoro.data.AdminRepository
import com.ivan.pomodoro.databinding.ItemAccountBinding

class AccountAdapter(
    private val onTomatoes: (AdminRepository.AccountSummary) -> Unit,
    private val onClear: (AdminRepository.AccountSummary) -> Unit,
    private val onDelete: (AdminRepository.AccountSummary) -> Unit
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    private val items = mutableListOf<AdminRepository.AccountSummary>()

    fun submit(list: List<AdminRepository.AccountSummary>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemAccountBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: AdminRepository.AccountSummary) {
            b.tvUsername.text = item.account.username
            b.tvRole.text = item.account.roleText()
            b.tvStats.text = "番茄 ${item.tomatoes} 个 · 专注 ${item.minutes} 分钟 · 计划 ${item.planCount} 个"
            b.btnView.setOnClickListener { onTomatoes(item) }
            b.btnClear.setOnClickListener { onClear(item) }
            b.btnDelete.setOnClickListener { onDelete(item) }
        }
    }
}
