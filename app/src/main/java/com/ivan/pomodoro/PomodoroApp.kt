package com.ivan.pomodoro

import android.app.Application
import com.ivan.pomodoro.data.AdminRepository
import com.ivan.pomodoro.data.AppDatabase
import com.ivan.pomodoro.data.FocusRepository
import com.ivan.pomodoro.session.AccountManager

class PomodoroApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }

    /** 业务仓库：所有读写都限定在当前登录账户 */
    val repository: FocusRepository by lazy {
        FocusRepository(
            database.focusPlanDao(),
            database.focusSessionDao(),
            { AccountManager.currentId }
        )
    }

    /** 管理端仓库：仅管理员页面使用，可跨账户访问 */
    val adminRepository: AdminRepository by lazy {
        AdminRepository(database.accountDao(), database.focusPlanDao(), database.focusSessionDao())
    }

    companion object {
        lateinit var instance: PomodoroApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AccountManager.init(this)
    }
}
