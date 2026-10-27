package com.ivan.pomodoro

import android.app.Application
import com.ivan.pomodoro.data.AppDatabase
import com.ivan.pomodoro.data.FocusRepository

class PomodoroApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val repository: FocusRepository by lazy { FocusRepository(database.focusPlanDao(), database.focusSessionDao()) }

    companion object {
        lateinit var instance: PomodoroApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
