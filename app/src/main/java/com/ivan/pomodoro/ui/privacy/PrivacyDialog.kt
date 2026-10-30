package com.ivan.pomodoro.ui.privacy

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.databinding.DialogPrivacyBinding

/**
 * 首次启动的隐私政策征询弹窗。
 *
 * 只允许通过「同意并继续」或「不同意」两个按钮关闭：点击弹窗外部、按返回键都不生效，
 * 避免用户绕过同意流程直接进入应用。选择不同意时直接退出应用，
 * 下次启动仍会再次征询。
 */
object PrivacyDialog {

    fun show(activity: AppCompatActivity, onAgreed: () -> Unit): AlertDialog {
        val binding = DialogPrivacyBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("隐私政策")
            .setView(binding.root)
            .setPositiveButton("同意并继续") { _, _ ->
                PrivacyPolicy.markAgreed(activity)
                onAgreed()
            }
            .setNegativeButton("不同意") { _, _ ->
                activity.finishAffinity()
            }
            .setCancelable(false)
            .create()

        binding.tvPrivacyDetail.setOnClickListener { PrivacyPolicy.open(activity) }
        dialog.show()
        return dialog
    }
}
