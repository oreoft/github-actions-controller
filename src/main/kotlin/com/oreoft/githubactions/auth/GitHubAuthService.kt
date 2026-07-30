package com.oreoft.githubactions.auth

import com.intellij.openapi.components.service
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount

/**
 * 复用 IDEA 内置 GitHub 认证体系的工具类。
 *
 * 账号在 Settings → Version Control → GitHub 中管理，
 * 用户无需在本插件中再单独配置 Token。
 *
 * ⚠ [getToken] 必须在后台线程调用（内部使用 runBlocking 包裹 suspend 函数）。
 */
object GitHubAuthService {

    private fun manager(): GHAccountManager = service()

    /**
     * 获取 IDEA 中所有已配置的 GitHub 账号。
     * 可在任意线程调用（读取 StateFlow 快照，无 IO）。
     */
    fun getAccounts(): List<GithubAccount> =
        manager().accountsState.value.toList()

    /**
     * 找到与指定 host 匹配的第一个 GitHub 账号。
     * @param host 例如 "github.com" 或企业 GitHub 的域名
     */
    fun findAccountFor(host: String = "github.com"): GithubAccount? =
        getAccounts().firstOrNull { it.server.host.equals(host, ignoreCase = true) }

    /**
     * 获取账号对应的 Token（Bearer token / OAuth token）。
     *
     * @return Token 字符串，账号未授权或 Token 已过期时返回 null
     * @throws IllegalStateException 若在 EDT 调用（会导致 UI 卡顿）
     */
    fun getToken(account: GithubAccount): String? =
        runBlocking { manager().findCredentials(account) }

    /**
     * 一步式：自动找到匹配 [host] 的账号并获取其 Token。
     * 适合直接在后台任务中调用。
     * @return Pair<账号, Token>，找不到账号或 Token 时返回 null
     */
    fun findAccountAndToken(host: String = "github.com"): Pair<GithubAccount, String>? {
        val account = findAccountFor(host) ?: return null
        val token = getToken(account) ?: return null
        return account to token
    }
}
