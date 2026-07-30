package com.oreoft.githubactions.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Application-level Service，负责安全存储 GitHub Personal Access Token。
 *
 * Token 存储在 IntelliJ 的 PasswordSafe（底层使用系统 Keychain / KWallet），
 * 不以明文写入任何配置文件。
 */
@Service(Service.Level.APP)
class GitHubActionsSettings {

    companion object {
        fun getInstance(): GitHubActionsSettings = service()

        private val CREDENTIAL_ATTRIBUTES = CredentialAttributes(
            generateServiceName("GitHubActionsController", "github-pat")
        )
    }

    /** 读取已存储的 Token，未设置则返回 null */
    fun getToken(): String? =
        PasswordSafe.instance.getPassword(CREDENTIAL_ATTRIBUTES)

    /**
     * 保存 Token。
     * 传入 null 或空字符串时，会删除已存储的凭据。
     */
    fun setToken(token: String?) {
        if (token.isNullOrBlank()) {
            PasswordSafe.instance.set(CREDENTIAL_ATTRIBUTES, null)
        } else {
            PasswordSafe.instance.set(
                CREDENTIAL_ATTRIBUTES,
                Credentials("github-pat", token.trim())
            )
        }
    }

    /** 是否已配置 Token */
    fun hasToken(): Boolean = !getToken().isNullOrBlank()
}
