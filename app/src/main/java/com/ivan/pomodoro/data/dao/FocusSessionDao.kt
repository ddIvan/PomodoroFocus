package com.ivan.pomodoro.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ivan.pomodoro.data.entity.FocusSession
import com.ivan.pomodoro.data.model.DailyStat
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusSessionDao {

    @Insert
    suspend fun insert(session: FocusSession): Long

    @Query("DELETE FROM focus_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 某天的番茄数 */
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE status = 0 AND dateKey = :dateKey")
    fun observeDayTomatoes(dateKey: String): Flow<Int>

    /** 某天已完成专注的总秒数 */
    @Query("SELECT IFNULL(SUM(actualSeconds), 0) FROM focus_sessions WHERE status = 0 AND dateKey = :dateKey")
    fun observeDaySeconds(dateKey: String): Flow<Int>

    /** 日期区间内每日番茄数（统计日历用） */
    @Query(
        "SELECT dateKey AS dateKey, COUNT(*) AS count, IFNULL(SUM(actualSeconds), 0) AS seconds " +
            "FROM focus_sessions WHERE status = 0 AND dateKey BETWEEN :from AND :to GROUP BY dateKey"
    )
    fun observeDailyStats(from: String, to: String): Flow<List<DailyStat>>

    /** 某天全部记录（含未通过，已清空的明细不再展示） */
    @Query("SELECT * FROM focus_sessions WHERE dateKey = :dateKey AND cleared = 0 ORDER BY startTime DESC")
    fun observeSessionsOfDay(dateKey: String): Flow<List<FocusSession>>

    /** 最近记录 */
    @Query("SELECT * FROM focus_sessions WHERE cleared = 0 ORDER BY startTime DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FocusSession>>

    /** 清空某天的记录明细；统计查询不筛选 cleared，因此番茄数不受影响 */
    @Query("UPDATE focus_sessions SET cleared = 1 WHERE dateKey = :dateKey AND cleared = 0")
    suspend fun clearDetailsOfDay(dateKey: String): Int

    /** 清空某天之前的记录明细（跨天自动清理用） */
    @Query("UPDATE focus_sessions SET cleared = 1 WHERE dateKey < :dateKey AND cleared = 0")
    suspend fun clearDetailsBefore(dateKey: String): Int

    /** 历史累计番茄数 */
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE status = 0")
    fun observeTotalTomatoes(): Flow<Int>

    /** 历史累计专注秒数 */
    @Query("SELECT IFNULL(SUM(actualSeconds), 0) FROM focus_sessions WHERE status = 0")
    fun observeTotalSeconds(): Flow<Int>

    /** 历史完成过专注的所有日期（用于连续打卡） */
    @Query("SELECT DISTINCT dateKey FROM focus_sessions WHERE status = 0 ORDER BY dateKey DESC")
    suspend fun completedDates(): List<String>

    /** 某计划在某天的完成番茄数 */
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE status = 0 AND planId = :planId AND dateKey = :dateKey")
    suspend fun countPlanOnDay(planId: Long, dateKey: String): Int
}
