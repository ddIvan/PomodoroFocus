package com.ivan.pomodoro.ui.admin

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.data.AdminRepository
import com.ivan.pomodoro.databinding.ActivityAdminBinding
import com.ivan.pomodoro.session.AccountManager
import kotlinx.coroutines.launch

/** 管理员数据管理：跨账户查看番茄数，可按日期管理/删除单个番茄，也可清空记录或删除账户 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var adapter: AccountAdapter

    private val admin: AdminRepository by lazy { (application as PomodoroApp).adminRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccountManager.isAdmin) {
            finish()
            return
        }
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AccountAdapter(
            onTomatoes = { openTomatoes(it) },
            onClear = { confirmClear(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.rvAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvAccounts.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // 非管理员（或被降权/登出）时不渲染，onCreate 提前 finish 的情况也要挡住
        if (!::binding.isInitialized || !AccountManager.isAdmin) {
            finish()
            return
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val list = admin.summaries()
            adapter.submit(list)
            binding.tvStatAccounts.text = list.size.toString()
            binding.tvStatTomatoes.text = list.sumOf { it.tomatoes }.toString()
            binding.tvStatPlans.text = list.sumOf { it.planCount }.toString()
            binding.rvAccounts.isVisible = list.isNotEmpty()
            binding.tvEmpty.isVisible = list.isEmpty()
        }
    }

    private fun openTomatoes(item: AdminRepository.AccountSummary) {
        startActivity(AdminTomatoesActivity.intent(this, item.account.id, item.account.username))
    }

    private fun confirmClear(item: AdminRepository.AccountSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空专注记录")
            .setMessage("将删除 ${item.account.username} 的全部专注记录，计划会保留。此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val count = admin.clearSessions(item.account.id)
                    Toast.makeText(this@AdminActivity, "已清空 $count 条记录", Toast.LENGTH_SHORT).show()
                    load()
                }
            }
            .show()
    }

    private fun confirmDelete(item: AdminRepository.AccountSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除账户")
            .setMessage("将删除 ${item.account.username} 及其全部计划与专注记录。此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val error = admin.deleteAccount(item.account, AccountManager.currentId)
                    if (error != null) {
                        Toast.makeText(this@AdminActivity, error, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@AdminActivity, "已删除", Toast.LENGTH_SHORT).show()
                        load()
                    }
                }
            }
            .show()
    }
}
