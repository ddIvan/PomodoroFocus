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
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun focusPlanDao(): FocusPlanDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun accountDao(): AccountDao

    companion object {
        /** 预置体验数据所在的日期与条数：zhengfy 在该日默认拥有 2 个番茄 */
        private const val SEED_DATE = "2026-10-27"
        private const val SEED_TARGET = 2
        private const val SEED_PLAN_NAME = "番茄专注"

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

        /**
         * v5：把 2026-10-27 的完成记录收敛到 2 个。
         * 升级用户当天可能已有自己的完成记录，v4 又补了 2 条预置记录，
         * 这里删掉多余的预置记录，保证"当天正好 2 个番茄"的预期不被打破。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query(
                    "SELECT COUNT(*) FROM focus_sessions WHERE dateKey = '$SEED_DATE' AND status = 0"
                )
                val total = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                cursor.close()
                val extra = total - SEED_TARGET
                if (extra > 0) {
                    db.execSQL(
                        "DELETE FROM focus_sessions WHERE id IN (" +
                            "SELECT id FROM focus_sessions WHERE dateKey = '$SEED_DATE'" +
                            " AND planName = '$SEED_PLAN_NAME' ORDER BY startTime DESC LIMIT $extra)"
                    )
                }
                seedTomato(db, 1793062800000L, 1793064300000L)
                seedTomato(db, 1793066400000L, 1793067900000L)
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

        /**
         * 给 zhengfy 补一条已完成的 25 分钟记录。
         * 两个约束保证幂等：该日完成数已达到 SEED_TARGET 时不再补；相同开始时间的记录不重复插。
         */
        private fun seedTomato(db: SupportSQLiteDatabase, startTime: Long, endTime: Long) {
            db.execSQL(
                "INSERT INTO focus_sessions(planId, planName, startTime, endTime, plannedSeconds, " +
                    "actualSeconds, dateKey, status, monitorEnabled, strictness, taskMode, " +
                    "distractionCount, distractedSeconds, invalidReason, cleared, accountId) " +
                    "SELECT 0, '$SEED_PLAN_NAME', $startTime, $endTime, 1500, 1500, '$SEED_DATE', 0, " +
                    "0, 1, 0, 0, 0, '', 0, a.id FROM accounts a WHERE a.username = 'zhengfy' " +
                    "AND (SELECT COUNT(*) FROM focus_sessions s WHERE s.dateKey = '$SEED_DATE'" +
                    " AND s.status = 0 AND s.accountId = a.id) < $SEED_TARGET " +
                    "AND NOT EXISTS (SELECT 1 FROM focus_sessions WHERE startTime = $startTime)"
            )
        }

        fun build(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pomodoro.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(SEED_CALLBACK)
                .build()
                .also { instance = it }
        }
    }
}
