package com.ivan.pomodoro.data

import com.ivan.pomodoro.data.dao.AccountDao
import com.ivan.pomodoro.data.dao.FocusPlanDao
import com.ivan.pomodoro.data.dao.FocusSessionDao
import com.ivan.pomodoro.data.entity.Account
import com.ivan.pomodoro.data.entity.FocusSession

/** 管理员视角的跨账户数据访问：汇总、番茄管理、清理与删除。 */
class AdminRepository(
    private val accountDao: AccountDao,
    private val planDao: FocusPlanDao,
    private val sessionDao: FocusSessionDao
) {

    data class AccountSummary(
        val account: Account,
        val tomatoes: Int,
        val minutes: Int,
        val planCount: Int
    )

    suspend fun summaries(): List<AccountSummary> = accountDao.getAll().map { account ->
        AccountSummary(
            account = account,
            tomatoes = sessionDao.countCompletedOf(account.id),
            minutes = sessionDao.sumSecondsOf(account.id) / 60,
            planCount = planDao.count(account.id)
        )
    }

    /** 某账户某天的番茄，按开始时间正序，便于按"第几个"定位 */
    data class DayTomatoes(val dateKey: String, val sessions: List<FocusSession>) {
        val count: Int get() = sessions.size
        val minutes: Int get() = sessions.sumOf { it.actualSeconds } / 60
    }

    /** 某账户全部番茄，按日期倒序分组（日期内按时间正序） */
    suspend fun tomatoesByDay(accountId: Long): List<DayTomatoes> =
        sessionDao.completedOf(accountId)
            .groupBy { it.dateKey }
            .map { (dateKey, list) -> DayTomatoes(dateKey, list.sortedBy { it.startTime }) }
            .sortedByDescending { it.dateKey }

    /** 删除某账户的一条番茄，返回是否删除成功 */
    suspend fun deleteTomato(accountId: Long, sessionId: Long): Boolean =
        sessionDao.deleteOneOf(sessionId, accountId) > 0

    /** 清空某账户的全部专注记录，返回删除条数 */
    suspend fun clearSessions(accountId: Long): Int = sessionDao.deleteByAccount(accountId)

    /**
     * 删除账户及其全部数据。
     * 返回 null 表示成功，否则返回不能删除的原因。
     */
    suspend fun deleteAccount(account: Account, currentAccountId: Long): String? {
        if (account.id == currentAccountId) return "不能删除当前登录的账户"
        if (account.isAdmin && accountDao.adminCount() <= 1) return "至少需要保留一个管理员账户"
        sessionDao.deleteByAccount(account.id)
        planDao.deleteByAccount(account.id)
        accountDao.deleteById(account.id)
        return null
    }
}
