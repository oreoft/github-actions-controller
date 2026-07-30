package com.oreoft.githubactions

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

/**
 * 插件字符串资源束。
 *
 * 默认语言：English（[GitHubActionsBundle.properties]）
 * 简体中文：[GitHubActionsBundle_zh_CN.properties]
 *
 * IDEA 会根据运行时 Locale 自动选择对应的 .properties 文件。
 *
 * 用法：
 * ```kotlin
 * GitHubActionsBundle.message("action.refresh.text")
 * GitHubActionsBundle.message("status.workflows.loaded", count, login, repo)
 * ```
 */
@NonNls
object GitHubActionsBundle {

    private const val BUNDLE = "messages.GitHubActionsBundle"

    private val instance = DynamicBundle(GitHubActionsBundle::class.java, BUNDLE)

    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any
    ): String = instance.getMessage(key, *params)
}
