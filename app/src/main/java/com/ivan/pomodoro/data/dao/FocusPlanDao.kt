package com.ivan.pomodoro.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.ivan.pomodoro.data.entity.FocusPlan
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusPlanDao {

    @Query("SELECT * FROM focus_plans WHERE archived = 0 AND accountId = :accountId ORDER BY createdAt ASC")
    fun observePlans(accountId: Long): Flow<List<FocusPlan>>

    @Query("SELECT * FROM focus_plans WHERE archived = 0 AND accountId = :accountId ORDER BY createdAt ASC")
    suspend fun getPlans(accountId: Long): List<FocusPlan>

    @Query("SELECT * FROM focus_plans WHERE id = :id")
    suspend fun getById(id: Long): FocusPlan?

    @Insert
    suspend fun insert(plan: FocusPlan): Long

    @Update
    suspend fun update(plan: FocusPlan)

    @Query("DELETE FROM focus_plans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM focus_plans WHERE archived = 0 AND accountId = :accountId")
    suspend fun count(accountId: Long): Int

    /** 管理端：删除某账户的全部计划 */
    @Query("DELETE FROM focus_plans WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long): Int
}
