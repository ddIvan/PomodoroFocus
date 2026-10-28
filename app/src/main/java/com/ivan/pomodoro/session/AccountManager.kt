package com.ivan.pomodoro.session

import android.content.Context
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.data.Prefs
import com.ivan.pomodoro.data.entity.Account
import java.security.MessageDigest

/** 登录 / 注册 / 会话保持。账号密码只在本机保存，无服务端。 */
object AccountManager {

    private lateinit var prefs: Prefs

    @Volatile
    private var cached: Account? = null

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = Prefs(context.applicationContext)
        }
    }

    private val dao get() = PomodoroApp.instance.database.accountDao()

    /** 当前账户 id，0 表示未登录；所有业务查询都以它为隔离键 */
    val currentId: Long
        get() = if (::prefs.isInitialized) prefs.currentAccountId else 0L

    val isLoggedIn: Boolean get() = currentId > 0L

    /** 是否管理员：允许查看与管理所有账户的数据 */
    val isAdmin: Boolean get() = isLoggedIn && prefs.currentAccountAdmin

    val displayName: String
        get() = if (::prefs.isInitialized) prefs.currentAccountName else ""

    fun currentAccount(): Account? = cached

    /** 启动时按持久化的账户 id 还原登录态；账户已被删除则自动登出 */
    suspend fun restore(): Account? {
        val id = currentId
        if (id <= 0L) return null
        val account = dao.getById(id)
        if (account == null) {
            logout()
            return null
        }
        cached = account
        return account
    }

    suspend fun login(username: String, password: String): AuthResult {
        val name = username.trim()
        if (name.isEmpty()) return AuthResult.Failure("请输入账号")
        if (password.isEmpty()) return AuthResult.Failure("请输入密码")
        val account = dao.findByUsername(name) ?: return AuthResult.Failure("账号或密码不正确")
        if (account.passwordHash != sha256(password)) return AuthResult.Failure("账号或密码不正确")
        return AuthResult.Success(apply(account))
    }

    suspend fun register(username: String, password: String, confirm: String): AuthResult {
        val name = username.trim()
        if (name.length < 3 || name.length > 16) return AuthResult.Failure("账号需为 3~16 位")
        if (!USERNAME_RULE.matches(name)) return AuthResult.Failure("账号只能包含字母、数字和下划线")
        if (password.length < 6) return AuthResult.Failure("密码至少 6 位")
        if (password != confirm) return AuthResult.Failure("两次输入的密码不一致")
        if (dao.findByUsername(name) != null) return AuthResult.Failure("该账号已存在")

        val account = Account(
            username = name,
            passwordHash = sha256(password),
            role = Account.ROLE_USER
        )
        val id = dao.insert(account)
        return AuthResult.Success(apply(account.copy(id = id)))
    }

    /** 明确的下线：清掉偏好里的登录态与当前选中计划 */
    fun logout() {
        cached = null
        if (!::prefs.isInitialized) return
        prefs.currentAccountId = 0L
        prefs.currentAccountName = ""
        prefs.currentAccountAdmin = false
        prefs.selectedPlanId = 0L
    }

    private fun apply(account: Account): Account {
        cached = account
        prefs.currentAccountId = account.id
        prefs.currentAccountName = account.username
        prefs.currentAccountAdmin = account.isAdmin
        // 换账户后旧的"选中计划"不再适用，重置后由首页自动选中第一个可用计划
        prefs.selectedPlanId = 0L
        return account
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val USERNAME_RULE = Regex("^[A-Za-z0-9_]+$")
}

sealed class AuthResult {
    data class Success(val account: Account) : AuthResult()
    data class Failure(val message: String) : AuthResult()
}
