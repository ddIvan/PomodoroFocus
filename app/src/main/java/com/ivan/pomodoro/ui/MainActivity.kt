package com.ivan.pomodoro.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.R
import com.ivan.pomodoro.data.FocusRepository
import com.ivan.pomodoro.data.Prefs
import com.ivan.pomodoro.databinding.ActivityMainBinding
import com.ivan.pomodoro.session.FocusSessionManager
import com.ivan.pomodoro.ui.focus.FocusFragment
import com.ivan.pomodoro.ui.plans.PlansFragment
import com.ivan.pomodoro.ui.session.FocusSessionActivity
import com.ivan.pomodoro.ui.stats.StatsFragment
import com.ivan.pomodoro.util.TimeUtils
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val repository: FocusRepository by lazy {
        (application as PomodoroApp).repository
    }
    private val prefs: Prefs by lazy { Prefs(this) }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FocusSessionManager.init(this)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_focus -> {
                    showFragment(FocusFragment::class.java, "focus")
                    true
                }

                R.id.nav_plans -> {
                    showFragment(PlansFragment::class.java, "plans")
                    true
                }

                R.id.nav_stats -> {
                    showFragment(StatsFragment::class.java, "stats")
                    true
                }

                else -> false
            }
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_focus
        }

        requestNotificationPermission()
    }

    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    private fun showFragment(clazz: Class<out Fragment>, tag: String) {
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        fm.fragments.forEach { fragment ->
            if (fragment.tag != tag && !fragment.isHidden) transaction.hide(fragment)
        }
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            val fragment = clazz.getDeclaredConstructor().newInstance()
            transaction.add(R.id.fragmentContainer, fragment, tag)
        } else {
            transaction.show(existing)
        }
        transaction.commit()
    }

    override fun onResume() {
        super.onResume()
        autoClearHistory()
        if (FocusSessionManager.isActive) {
            // 必须复用栈中已有实例，否则每次回到首页都会叠一层专注页，各自弹出结束提示
            val intent = Intent(this, FocusSessionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }
    }

    /**
     * 跨天进入 App 时自动清空昨日及更早的记录明细。
     * 只清空明细展示，番茄数、专注时长、打卡等统计仍按全量数据计算。
     */
    private fun autoClearHistory() {
        val today = LocalDate.now()
        val key = TimeUtils.dateKey(today)
        if (prefs.lastAutoClearDateKey == key) return
        lifecycleScope.launch {
            repository.clearBefore(today)
            prefs.lastAutoClearDateKey = key
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
