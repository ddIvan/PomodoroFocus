package com.ivan.pomodoro.ui.login

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.ivan.pomodoro.databinding.ActivityLoginBinding
import com.ivan.pomodoro.session.AccountManager
import com.ivan.pomodoro.session.AuthResult
import com.ivan.pomodoro.ui.MainActivity
import kotlinx.coroutines.launch

/** 登录页：本地账号校验，登录态通过 Prefs 保持 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AccountManager.init(this)

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnRegister.setOnClickListener { RegisterDialog.show(this) { goMain() } }
        binding.etPassword.setOnEditorActionListener { _, _, _ ->
            doLogin()
            true
        }

        if (AccountManager.isLoggedIn) {
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
    }

    private fun doLogin() {
        if (busy) return
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
