package com.ivan.pomodoro.ui.privacy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ivan.pomodoro.data.Prefs

/**
 * 隐私政策的统一入口：同意状态、线上地址、政策版本。
 *
 * 应用市场（华为等）要求首次启动时以弹窗等显著方式提示用户阅读隐私政策，
 * 且未经同意不得收集任何信息、不得申请权限。因此启动流程必须先过这里，
 * 再初始化账户与数据库（[com.ivan.pomodoro.session.AccountManager]）。
 */
object PrivacyPolicy {

    /** 政策版本：正文发生实质变更时同步递增，可让老用户重新确认 */
    const val VERSION = "1.3"

    /** 线上政策地址：交给系统浏览器打开，应用自身没有网络权限 */
    const val URL = "http://www.tainylong.cn/workbuddy/zhuanzhu.html"

    fun isAgreed(context: Context): Boolean =
        Prefs(context.applicationContext).privacyAgreedVersion == VERSION

    fun markAgreed(context: Context) {
        Prefs(context.applicationContext).privacyAgreedVersion = VERSION
    }

    /** 交给系统浏览器打开完整政策；返回 false 表示设备上没有可用的浏览器 */
    fun open(context: Context): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URL)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
