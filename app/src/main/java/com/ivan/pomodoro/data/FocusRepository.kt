package com.ivan.pomodoro.data

import com.ivan.pomodoro.data.dao.FocusPlanDao
import com.ivan.pomodoro.data.dao.FocusSessionDao
import com.ivan.pomodoro.data.entity.FocusPlan
import com.ivan.pomodoro.data.entity.FocusSession
import com.ivan.pomodoro.data.model.DailyStat
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 数据仓库。
 *
 * 所有查询都限定在 [accountId] 返回的账户上，做到"数据跟账户走"；
 * 管理端需要跨账户读取时走 [AdminRepository]。
 */
class FocusRepository(
    private val planDao: FocusPlanDao,
    private val sessionDao: FocusSessionDao,
    private val accountId: () -> Long
) {

    companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val acc: Long get() = accountId()

    // ---------- 计划 ----------
    fun observePlans(): Flow<List<FocusPlan>> = planDao.observePlans(acc)
    suspend fun getPlans(): List<FocusPlan> = planDao.getPlans(acc)
    suspend fun getPlan(id: Long): FocusPlan? = planDao.getById(id)
    suspend fun savePlan(plan: FocusPlan): Long {
        val owned = if (plan.id == 0L) plan.copy(accountId = acc) else plan
        return if (owned.id == 0L) planDao.insert(owned) else { planDao.update(owned); owned.id }
    }

    suspend fun deletePlan(id: Long) = planDao.deleteById(id)
    suspend fun planCount(): Int = planDao.count(acc)

    // ---------- 记录 ----------
    suspend fun addSession(session: FocusSession): Long =
        sessionDao.insert(session.copy(accountId = acc))

    suspend fun deleteSession(id: Long) = sessionDao.deleteById(id)

    fun observeDayTomatoes(date: LocalDate): Flow<Int> =
        sessionDao.observeDayTomatoes(date.format(DATE_FORMAT), acc)

    fun observeDaySeconds(date: LocalDate): Flow<Int> =
        sessionDao.observeDaySeconds(date.format(DATE_FORMAT), acc)

    fun observeDailyStats(from: LocalDate, to: LocalDate): Flow<List<DailyStat>> =
        sessionDao.observeDailyStats(from.format(DATE_FORMAT), to.format(DATE_FORMAT), acc)

    fun observeSessionsOfDay(date: LocalDate): Flow<List<FocusSession>> =
        sessionDao.observeSessionsOfDay(date.format(DATE_FORMAT), acc)

    fun observeRecent(limit: Int = 30): Flow<List<FocusSession>> =
        sessionDao.observeRecent(limit, acc)

    /** 清空某天记录明细，返回清除条数；统计数字保持不变 */
    suspend fun clearDay(date: LocalDate): Int =
        sessionDao.clearDetailsOfDay(date.format(DATE_FORMAT), acc)

    /** 清空某天之前的记录明细，返回清除条数；统计数字保持不变 */
    suspend fun clearBefore(date: LocalDate): Int =
        sessionDao.clearDetailsBefore(date.format(DATE_FORMAT), acc)

    fun observeTotalTomatoes(): Flow<Int> = sessionDao.observeTotalTomatoes(acc)
    fun observeTotalSeconds(): Flow<Int> = sessionDao.observeTotalSeconds(acc)

    suspend fun completedDates(): List<String> = sessionDao.completedDates(acc)

    suspend fun countPlanOnDay(planId: Long, date: LocalDate): Int =
        sessionDao.countPlanOnDay(planId, date.format(DATE_FORMAT), acc)
}
