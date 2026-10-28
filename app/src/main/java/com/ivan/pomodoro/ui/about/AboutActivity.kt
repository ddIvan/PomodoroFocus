package com.ivan.pomodoro.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ivan.pomodoro.databinding.ActivityAboutBinding

/**
 * 关于页：应用信息 + 隐私政策入口。
 *
 * 隐私政策放在线上页面而不是内置 HTML，是为了应用市场审核时能提供一个公开可访问的
 * 链接；网络权限本应用并未申请，这里只是把 URL 交给系统浏览器打开，不涉及应用内联网。
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        const val PRIVACY_URL = "http://www.tainylong.cn/workbuddy/zhuanzhu.html"
    }

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvVersion.text = "版本 ${versionName()}"
        binding.itemPrivacy.setOnClickListener { openPrivacyPolicy() }
    }

    /** 版本号从系统读取，避免和 build.gradle 里的 versionName 各写一份导致不一致 */
    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun openPrivacyPolicy() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有找到可以打开网页的浏览器", Toast.LENGTH_SHORT).show()
        }
    }
}
