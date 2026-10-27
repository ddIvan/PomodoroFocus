package com.ivan.pomodoro.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ivan.pomodoro.data.dao.FocusPlanDao
import com.ivan.pomodoro.data.dao.FocusSessionDao
import com.ivan.pomodoro.data.entity.FocusPlan
import com.ivan.pomodoro.data.entity.FocusSession

@Database(
    entities = [FocusPlan::class, FocusSession::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun focusPlanDao(): FocusPlanDao
    abstract fun focusSessionDao(): FocusSessionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v2：记录明细增加 cleared 标记，用于清空/跨天自动清理明细而不影响统计 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE focus_sessions ADD COLUMN cleared INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v3：计划与记录增加任务类型（0 盯屏 / 1 纸面），旧数据按盯屏处理 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE focus_plans ADD COLUMN taskMode INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE focus_sessions ADD COLUMN taskMode INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun build(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pomodoro.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
