package com.ivan.pomodoro.data

import com.ivan.pomodoro.data.dao.FocusPlanDao
import com.ivan.pomodoro.data.dao.FocusSessionDao
import com.ivan.pomodoro.data.entity.FocusPlan
import com.ivan.pomodoro.data.entity.FocusSession
import com.ivan.pomodoro.data.model.DailyStat
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class FocusRepository(
    private val planDao: FocusPlanDao,
    private val sessionDao: FocusSessionDao
) {

    companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    // ---------- 计划 ----------
    fun observePlans(): Flow<List<FocusPlan>> = planDao.observePlans()
    suspend fun getPlans(): List<FocusPlan> = planDao.getPlans()
    suspend fun getPlan(id: Long): FocusPlan? = planDao.getById(id)
    suspend fun savePlan(plan: FocusPlan): Long =
        if (plan.id == 0L) planDao.insert(plan) else { planDao.update(plan); plan.id }

    suspend fun deletePlan(id: Long) = planDao.deleteById(id)
    suspend fun planCount(): Int = planDao.count()

    // ---------- 记录 ----------
    suspend fun addSession(session: FocusSession): Long = sessionDao.insert(session)
    suspend fun deleteSession(id: Long) = sessionDao.deleteById(id)

    fun observeDayTomatoes(date: LocalDate): Flow<Int> = sessionDao.observeDayTomatoes(date.format(DATE_FORMAT))
    fun observeDaySeconds(date: LocalDate): Flow<Int> = sessionDao.observeDaySeconds(date.format(DATE_FORMAT))

    fun observeDailyStats(from: LocalDate, to: LocalDate): Flow<List<DailyStat>> =
        sessionDao.observeDailyStats(from.format(DATE_FORMAT), to.format(DATE_FORMAT))

    fun observeSessionsOfDay(date: LocalDate): Flow<List<FocusSession>> =
        sessionDao.observeSessionsOfDay(date.format(DATE_FORMAT))

    fun observeRecent(limit: Int = 30): Flow<List<FocusSession>> = sessionDao.observeRecent(limit)

    /** 清空某天记录明细，返回清除条数；统计数字保持不变 */
    suspend fun clearDay(date: LocalDate): Int =
        sessionDao.clearDetailsOfDay(date.format(DATE_FORMAT))

    /** 清空某天之前的记录明细，返回清除条数；统计数字保持不变 */
    suspend fun clearBefore(date: LocalDate): Int =
        sessionDao.clearDetailsBefore(date.format(DATE_FORMAT))

    fun observeTotalTomatoes(): Flow<Int> = sessionDao.observeTotalTomatoes()
    fun observeTotalSeconds(): Flow<Int> = sessionDao.observeTotalSeconds()

    suspend fun completedDates(): List<String> = sessionDao.completedDates()

    suspend fun countPlanOnDay(planId: Long, date: LocalDate): Int =
        sessionDao.countPlanOnDay(planId, date.format(DATE_FORMAT))
}
