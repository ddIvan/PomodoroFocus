package com.ivan.pomodoro.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ivan.pomodoro.data.dao.AccountDao
import com.ivan.pomodoro.data.dao.FocusPlanDao
import com.ivan.pomodoro.data.dao.FocusSessionDao
import com.ivan.pomodoro.data.entity.Account
import com.ivan.pomodoro.data.entity.FocusPlan
import com.ivan.pomodoro.data.entity.FocusSession

@Database(
    entities = [FocusPlan::class, FocusSession::class, Account::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun focusPlanDao(): FocusPlanDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun accountDao(): AccountDao

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

        /**
         * v4：多账户隔离。
         * - 新建 accounts 表并写入预置账户（admin / zhengfy，口令都是 123456）
         * - 计划与记录增加 accountId，历史数据归到 zhengfy，避免升级后"数据消失"
         * - 给 zhengfy 预置 2026-10-27 的 2 个番茄，便于直接看统计效果
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createAccountsTable(db)
                db.execSQL("ALTER TABLE focus_plans ADD COLUMN accountId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE focus_sessions ADD COLUMN accountId INTEGER NOT NULL DEFAULT 0")
                seedAccounts(db)
            }
        }

        /** 新装数据库也要有预置账户与体验数据 */
        private val SEED_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedAccounts(db)
            }
        }

        private fun createAccountsTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `accounts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`username` TEXT NOT NULL, " +
                    "`passwordHash` TEXT NOT NULL, " +
                    "`role` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_username` ON `accounts` (`username`)"
            )
        }

        /**
         * 预置账户与体验数据。
         * 迁移与新装都会走到这里，因此全部用 INSERT OR IGNORE + 兜底 UPDATE 保证幂等。
         */
        private fun seedAccounts(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            val hash = Account.DEFAULT_PASSWORD_HASH
            db.execSQL(
                "INSERT OR IGNORE INTO accounts(username, passwordHash, role, createdAt) " +
                    "VALUES('admin', '$hash', ${Account.ROLE_ADMIN}, $now)"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO accounts(username, passwordHash, role, createdAt) " +
                    "VALUES('zhengfy', '$hash', ${Account.ROLE_USER}, $now)"
            )

            // 升级场景：老数据的 accountId 还是 0，统一归到 zhengfy
            db.execSQL(
                "UPDATE focus_plans SET accountId = " +
                    "(SELECT id FROM accounts WHERE username = 'zhengfy') WHERE accountId = 0"
            )
            db.execSQL(
                "UPDATE focus_sessions SET accountId = " +
                    "(SELECT id FROM accounts WHERE username = 'zhengfy') WHERE accountId = 0"
            )

            // 体验数据：2026-10-27 完成两次 25 分钟专注（CST 09:00 / 10:00 开始）
            seedTomato(db, 1793062800000L, 1793064300000L)
            seedTomato(db, 1793066400000L, 1793067900000L)
        }

        /** 给 zhengfy 塞一条已完成的 25 分钟记录；按开始时间去重，避免重复播种 */
        private fun seedTomato(db: SupportSQLiteDatabase, startTime: Long, endTime: Long) {
            db.execSQL(
                "INSERT INTO focus_sessions(planId, planName, startTime, endTime, plannedSeconds, " +
                    "actualSeconds, dateKey, status, monitorEnabled, strictness, taskMode, " +
                    "distractionCount, distractedSeconds, invalidReason, cleared, accountId) " +
                    "SELECT 0, '番茄专注', $startTime, $endTime, 1500, 1500, '2026-10-27', 0, " +
                    "0, 1, 0, 0, 0, '', 0, id FROM accounts WHERE username = 'zhengfy' " +
                    "AND NOT EXISTS (SELECT 1 FROM focus_sessions WHERE startTime = $startTime)"
            )
        }

        fun build(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pomodoro.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(SEED_CALLBACK)
                .build()
                .also { instance = it }
        }
    }
}
