package com.ivan.pomodoro.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地账户：账号与口令只保存在本机数据库，不联网、不上传。
 *
 * 口令不落明文，统一存 SHA-256；定位是"多个家庭成员/多角色共用一台设备时区分数据"，
 * 不承担真正的安全边界（拿到设备与数据库即可绕过）。
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["username"], unique = true)]
)
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String = "",
    /** 口令的 SHA-256（小写十六进制） */
    val passwordHash: String = "",
    /** 0=普通用户 1=管理员 */
    val role: Int = ROLE_USER,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isAdmin: Boolean get() = role == ROLE_ADMIN

    fun roleText(): String = if (isAdmin) "管理员" else "普通用户"

    companion object {
        const val ROLE_USER = 0
        const val ROLE_ADMIN = 1

        /** 预置的默认口令（123456）对应的 SHA-256，用于数据库初始化与迁移 */
        const val DEFAULT_PASSWORD_HASH =
            "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92"
    }
}
