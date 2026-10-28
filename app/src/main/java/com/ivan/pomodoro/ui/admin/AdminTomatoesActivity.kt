package com.ivan.pomodoro.ui.admin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.data.AdminRepository
import com.ivan.pomodoro.data.entity.FocusSession
import com.ivan.pomodoro.databinding.ActivityAdminTomatoesBinding
import com.ivan.pomodoro.session.AccountManager
import com.ivan.pomodoro.util.TimeUtils
import kotlinx.coroutines.launch

/** 管理员：按日期管理某个账户已获得的番茄，可删除指定的某一个 */
class AdminTomatoesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminTomatoesBinding
    private lateinit var adapter: TomatoAdapter

    private val admin: AdminRepository by lazy { (application as PomodoroApp).adminRepository }
    private var accountId: Long = 0
    private var username: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccountManager.isAdmin) {
            finish()
            return
        }
        accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, 0)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        if (accountId <= 0) {
            finish()
            return
        }
        binding = ActivityAdminTomatoesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        adapter = TomatoAdapter { dateKey, session, index -> confirmDelete(dateKey, session, index) }
        binding.rvTomatoes.layoutManager = LinearLayoutManager(this)
        binding.rvTomatoes.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized || !AccountManager.isAdmin) {
            finish()
            return
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val days = admin.tomatoesByDay(accountId)
            val total = days.sumOf { it.count }
            binding.tvAccount.text = "$username · 共 $total 个番茄"
            adapter.submit(days)
            binding.rvTomatoes.isVisible = days.isNotEmpty()
            binding.tvEmpty.isVisible = days.isEmpty()
        }
    }

    private fun confirmDelete(dateKey: String, session: FocusSession, indexInDay: Int) {
        val dayText = TimeUtils.parseDateKey(dateKey)?.let { TimeUtils.dayTitle(it) } ?: dateKey
        val message = "将删除 $username 在 $dayText 的第 ${indexInDay + 1} 个番茄" +
            "（${TimeUtils.timeOfDay(session.startTime)} · ${TimeUtils.durationText(session.actualSeconds)}）。" +
            "该账户的番茄数与专注时长会同步减少，此操作不可撤销。"
        MaterialAlertDialogBuilder(this)
            .setTitle("删除番茄")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val ok = admin.deleteTomato(accountId, session.id)
                    Toast.makeText(
                        this@AdminTomatoesActivity,
                        if (ok) "已删除 1 个番茄" else "删除失败，记录可能已不存在",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) load()
                }
            }
            .show()
    }

    companion object {
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_USERNAME = "username"

        fun intent(context: Context, accountId: Long, username: String): Intent =
            Intent(context, AdminTomatoesActivity::class.java)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                .putExtra(EXTRA_USERNAME, username)
    }
}
