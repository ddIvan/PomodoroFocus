package com.ivan.pomodoro.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.PopupMenu
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
import com.ivan.pomodoro.monitor.MonitorScene
import com.ivan.pomodoro.session.AccountManager
import com.ivan.pomodoro.session.FocusSessionManager
import com.ivan.pomodoro.ui.about.AboutActivity
import com.ivan.pomodoro.ui.admin.AdminActivity
import com.ivan.pomodoro.ui.focus.FocusFragment
import com.ivan.pomodoro.ui.login.ChangePasswordDialog
import com.ivan.pomodoro.ui.login.LoginActivity
import com.ivan.pomodoro.ui.login.RegisterDialog
import com.ivan.pomodoro.ui.plans.PlansFragment
import com.ivan.pomodoro.ui.privacy.PrivacyPolicy
import com.ivan.pomodoro.ui.session.FocusSessionActivity
import com.ivan.pomodoro.ui.settings.MonitorSettingsActivity
import com.ivan.pomodoro.ui.stats.StatsFragment
import com.ivan.pomodoro.util.TimeUtils
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    companion object {
        /** 专注成功结束时带过来，让首页播放番茄入账动效并报喜 */
        const val EXTRA_TOMATO_GAINED = "tomato_gained"
    }

    private lateinit var binding: ActivityMainBinding

    private val repository: FocusRepository by lazy {
        (application as PomodoroApp).repository
    }
    private val prefs: Prefs by lazy { Prefs(this) }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** onCreate 与 onResume 都可能触发跳登录页，用标记避免叠两层 */
    private var redirecting = false

    /** 已收到番茄入账事件，等首页真正回到前台再播 */
    private var pendingTomatoGain = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 兜底：未同意隐私政策时不允许进入主界面（正常入口由登录页拦截）
        if (!PrivacyPolicy.isAgreed(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        AccountManager.init(this)
        if (!AccountManager.isLoggedIn) {
            redirectToLogin()
            return
        }

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

        binding.ivAccountMenu.setOnClickListener { showAccountMenu() }
        bindAccountBar()
        requestNotificationPermission()
        handleTomatoGain(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTomatoGain(intent)
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
        if (!AccountManager.isLoggedIn) {
            redirectToLogin()
            return
        }
        bindAccountBar()
        autoClearHistory()
        if (FocusSessionManager.isActive) {
            // 必须复用栈中已有实例，否则每次回到首页都会叠一层专注页，各自弹出结束提示
            val intent = Intent(this, FocusSessionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            return
        }
        dispatchTomatoGain()
    }

    /**
     * 专注页完成时带过来的标记：切到首页，动效交给它播。
     * 标记读完即清，避免旋转屏或进程重建后重放一遍。
     */
    private fun handleTomatoGain(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_TOMATO_GAINED, false) != true) return
        intent.removeExtra(EXTRA_TOMATO_GAINED)
        pendingTomatoGain = true
        selectTab(R.id.nav_focus)
    }

    /** 等首页真正回到前台再播，否则 900ms 的动效有一半会消耗在转场里 */
    private fun dispatchTomatoGain() {
        if (!pendingTomatoGain) return
        pendingTomatoGain = false
        window.decorView.post {
            supportFragmentManager.executePendingTransactions()
            val focus = supportFragmentManager.findFragmentByTag("focus") as? FocusFragment
            focus?.playTomatoGain()
        }
    }

    private fun bindAccountBar() {
        binding.tvAccountName.text = AccountManager.displayName.ifEmpty { "未登录" }
        binding.tvAccountMeta.text = when {
            AccountManager.isAdmin -> "管理员 · 可管理所有账户数据"
            AccountManager.isLoggedIn -> "普通账户 · 数据仅保存在本机"
            else -> "请先登录"
        }
    }

    private fun showAccountMenu() {
        val popup = PopupMenu(this, binding.ivAccountMenu)
        popup.menuInflater.inflate(R.menu.account_menu, popup.menu)
        popup.menu.findItem(R.id.action_admin).isVisible = AccountManager.isAdmin
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_register -> {
                    // 首页也能直接注册：注册成功会自动登录该账户，重启页面刷新数据
                    RegisterDialog.show(this) { restartSelf() }
                    true
                }

                R.id.action_change_password -> {
                    ChangePasswordDialog.show(this)
                    true
                }

                R.id.action_switch_account, R.id.action_logout -> {
                    AccountManager.logout()
                    redirectToLogin()
                    true
                }

                R.id.action_monitor_settings -> {
                    val prefs = Prefs(this)
                    val scene = MonitorScene.of(
                        prefs.monitorLens == Prefs.LENS_BACK,
                        false
                    )
                    startActivity(MonitorSettingsActivity.newIntent(this, scene))
                    true
                }

                R.id.action_admin -> {
                    startActivity(Intent(this, AdminActivity::class.java))
                    true
                }

                R.id.action_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun redirectToLogin() {
        if (redirecting || isFinishing) return
        redirecting = true
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    /** 注册成功后账户已切换，重建首页让所有列表按新账户刷新 */
    private fun restartSelf() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * 跨天进入 App 时自动清空昨日及更早的记录明细（按账户各记一份进度）。
     * 只清空明细展示，番茄数、专注时长、打卡等统计仍按全量数据计算。
     */
    private fun autoClearHistory() {
        val accountId = AccountManager.currentId
        if (accountId <= 0L) return
        val today = LocalDate.now()
        val key = TimeUtils.dateKey(today)
        if (prefs.autoClearMark(accountId) == key) return
        lifecycleScope.launch {
            repository.clearBefore(today)
            prefs.setAutoClearMark(accountId, key)
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
