package com.oreoft.githubactions.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings 配置页，路径：Settings / Preferences → Tools → GitHub Actions Controller
 *
 * 提供 GitHub Personal Access Token 的输入和存储，
 * Token 所需权限：`repo`（读取 workflows）+ `workflow`（触发 workflow dispatch）。
 */
class GitHubActionsConfigurable : Configurable {

    private var tokenField: JBPasswordField? = null
    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = "GitHub Actions Controller"

    override fun createComponent(): JComponent {
        val field = JBPasswordField().also { tokenField = it }

        rootPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("GitHub Personal Access Token:"), field, 1, false)
            .addComponentToRightColumn(
                JBLabel(
                    "<html><small style='color:gray'>" +
                    "需要权限：<b>repo</b>、<b>workflow</b>。" +
                    "前往 GitHub → Settings → Developer settings → Personal access tokens 创建。" +
                    "</small></html>"
                ).apply { border = JBUI.Borders.emptyTop(4) },
                1
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return rootPanel!!
    }

    override fun isModified(): Boolean {
        val stored = GitHubActionsSettings.getInstance().getToken() ?: ""
        val current = tokenField?.let { String(it.password) } ?: ""
        return current != stored
    }

    override fun apply() {
        val token = tokenField?.let { String(it.password) } ?: ""
        GitHubActionsSettings.getInstance().setToken(token)
    }

    override fun reset() {
        val stored = GitHubActionsSettings.getInstance().getToken() ?: ""
        tokenField?.text = stored
    }

    override fun disposeUIResources() {
        rootPanel = null
        tokenField = null
    }
}
