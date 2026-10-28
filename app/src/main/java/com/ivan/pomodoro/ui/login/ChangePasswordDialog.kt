package com.ivan.pomodoro.ui.login

import android.app.AlertDialog
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.databinding.DialogChangePasswordBinding
import com.ivan.pomodoro.session.AccountManager
import com.ivan.pomodoro.session.AuthResult
import kotlinx.coroutines.launch

/** 修改当前登录账户的密码：校验原密码，成功后保持登录状态 */
object ChangePasswordDialog {

    fun show(activity: AppCompatActivity) {
        val binding = DialogChangePasswordBinding.inflate(activity.layoutInflater)
        val accountName = AccountManager.displayName
        binding.tvChangeAccount.text = if (accountName.isEmpty()) {
            "设置新密码后将立即生效"
        } else {
            "当前账户：$accountName"
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("修改密码")
            .setView(binding.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val old = binding.etOldPassword.text?.toString().orEmpty()
                val new = binding.etNewPassword.text?.toString().orEmpty()
                val confirm = binding.etConfirmPassword.text?.toString().orEmpty()
                binding.tvChangeError.isVisible = false
                activity.lifecycleScope.launch {
                    when (val result = AccountManager.changePassword(old, new, confirm)) {
                        is AuthResult.Success -> {
                            dialog.dismiss()
                            Toast.makeText(activity, "密码已修改", Toast.LENGTH_SHORT).show()
                        }

                        is AuthResult.Failure -> {
                            binding.tvChangeError.text = result.message
                            binding.tvChangeError.isVisible = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
