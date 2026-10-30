package com.ivan.pomodoro.ui.login

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.R
import com.ivan.pomodoro.databinding.ActivityLoginBinding
import com.ivan.pomodoro.session.AccountManager
import com.ivan.pomodoro.session.AuthResult
import com.ivan.pomodoro.ui.MainActivity
import com.ivan.pomodoro.ui.privacy.PrivacyDialog
import com.ivan.pomodoro.ui.privacy.PrivacyPolicy
import kotlinx.coroutines.launch

/**
 * 登录页：本地账号校验，登录态通过 Prefs 保持。
 *
 * 本页是应用的唯一启动入口，因此隐私政策征询放在这里：未同意前只渲染界面，
 * 不初始化账户与数据库、不申请任何权限；用户不同意则退出应用。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var busy = false
    private var agreeDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnRegister.setOnClickListener { requestRegister() }
        binding.etPassword.setOnEditorActionListener { _, _, _ ->
            doLogin()
            true
        }
        binding.cbPrivacy.setOnCheckedChangeListener { _, _ -> refreshAgreeState() }
        setupPrivacyTip()
        refreshAgreeState()

        if (PrivacyPolicy.isAgreed(this)) {
            startSessionFlow()
        } else {
            requestPrivacyAgreement()
        }
    }

    /** 同意隐私政策后，才允许初始化账户体系并恢复登录态 */
    private fun startSessionFlow() {
        AccountManager.init(this)
        if (!AccountManager.isLoggedIn) return
        binding.btnLogin.isEnabled = false
        binding.tvLoginSubtitle.text = "正在恢复登录状态…"
        lifecycleScope.launch {
            if (AccountManager.restore() != null) {
                goMain()
            } else {
                binding.btnLogin.isEnabled = true
                binding.tvLoginSubtitle.text = "登录后开始你的专注"
            }
        }
    }

    private fun requestPrivacyAgreement() {
        PrivacyDialog.show(this) { startSessionFlow() }
    }

    /** 复选框右侧的协议文本，《隐私政策》几个字可点击查看全文 */
    private fun setupPrivacyTip() {
        val tip = "我已阅读并同意《隐私政策》"
        val start = tip.indexOf("《隐私政策》")
        val text = SpannableString(tip)
        if (start >= 0) {
            text.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    PrivacyPolicy.open(this@LoginActivity)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = ContextCompat.getColor(this@LoginActivity, R.color.pomo_primary)
                    ds.isUnderlineText = false
                }
            }, start, tip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvPrivacyTip.text = text
        binding.tvPrivacyTip.movementMethod = LinkMovementMethod.getInstance()
        binding.tvPrivacyTip.highlightColor = Color.TRANSPARENT
    }

    private fun doLogin() {
        if (busy) return
        // 兜底：任何路径进入登录动作前都必须已经同意隐私政策
        if (!PrivacyPolicy.isAgreed(this)) {
            requestPrivacyAgreement()
            return
        }
        // 未勾选协议时先二次确认，确认后自动勾选再继续登录
        if (!binding.cbPrivacy.isChecked) {
            confirmAgreement { performLogin() }
            return
        }
        performLogin()
    }

    /** 注册入口同样要先确认协议，避免未勾选也能走完注册直接进应用 */
    private fun requestRegister() {
        if (busy) return
        if (!PrivacyPolicy.isAgreed(this)) {
            requestPrivacyAgreement()
            return
        }
        if (!binding.cbPrivacy.isChecked) {
            confirmAgreement { RegisterDialog.show(this) { goMain() } }
            return
        }
        RegisterDialog.show(this) { goMain() }
    }

    /**
     * 未勾选复选框时的二次确认：只有点了「同意并继续」才回填勾选并执行后续动作，
     * 取消则保持未勾选、不继续原动作。
     */
    private fun confirmAgreement(onAgreed: () -> Unit) {
        if (agreeDialog?.isShowing == true) return
        agreeDialog = MaterialAlertDialogBuilder(this)
            .setTitle("隐私政策")
            .setMessage(
                "你还没有勾选《隐私政策》。\n\n" +
                    "继续操作即表示你已阅读并同意本政策，同意后我们会为你自动勾选。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("同意并继续") { _, _ ->
                binding.cbPrivacy.isChecked = true
                onAgreed()
            }
            .show()
    }

    /** 协议未勾选时只降低按钮视觉权重、保持可点击，否则二次确认弹窗无法触发 */
    private fun refreshAgreeState() {
        binding.btnLogin.alpha = if (binding.cbPrivacy.isChecked) 1f else 0.6f
    }

    private fun performLogin() {
        val username = binding.etUsername.text?.toString().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        binding.tvError.isVisible = false
        setBusy(true)
        lifecycleScope.launch {
            when (val result = AccountManager.login(username, password)) {
                is AuthResult.Success -> goMain()
                is AuthResult.Failure -> {
                    showError(result.message)
                    setBusy(false)
                }
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.btnLogin.isEnabled = !value
        binding.btnRegister.isEnabled = !value
        binding.btnLogin.text = if (value) "登录中…" else "登 录"
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.isVisible = true
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
