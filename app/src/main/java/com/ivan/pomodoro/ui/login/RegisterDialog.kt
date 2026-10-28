package com.ivan.pomodoro.ui.login

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.databinding.DialogRegisterBinding
import com.ivan.pomodoro.session.AccountManager
import com.ivan.pomodoro.session.AuthResult
import kotlinx.coroutines.launch

/** 注册弹窗：登录页与首页共用，注册成功即自动登录 */
object RegisterDialog {

    fun show(activity: AppCompatActivity, onSuccess: () -> Unit) {
        val binding = DialogRegisterBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("注册新账号")
            .setView(binding.root)
            .setPositiveButton("注册", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = binding.etRegUsername.text?.toString().orEmpty()
                val password = binding.etRegPassword.text?.toString().orEmpty()
                val confirm = binding.etRegConfirm.text?.toString().orEmpty()
                binding.tvRegError.isVisible = false
                activity.lifecycleScope.launch {
                    when (val result = AccountManager.register(username, password, confirm)) {
                        is AuthResult.Success -> {
                            dialog.dismiss()
                            onSuccess()
                        }

                        is AuthResult.Failure -> {
                            binding.tvRegError.text = result.message
                            binding.tvRegError.isVisible = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
