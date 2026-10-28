package com.ivan.pomodoro.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ivan.pomodoro.data.entity.Account
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY role DESC, createdAt ASC")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts ORDER BY role DESC, createdAt ASC")
    suspend fun getAll(): List<Account>

    @Query("SELECT * FROM accounts WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): Account?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): Account?

    @Insert
    suspend fun insert(account: Account): Long

    /** 只更新口令哈希，账号、角色与创建时间保持不变 */
    @Query("UPDATE accounts SET passwordHash = :passwordHash WHERE id = :id")
    suspend fun updatePassword(id: Long, passwordHash: String)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE role = 1")
    suspend fun adminCount(): Int
}
