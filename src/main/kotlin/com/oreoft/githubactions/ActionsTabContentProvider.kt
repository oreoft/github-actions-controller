package com.oreoft.githubactions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 外壳 tab：只负责在 Git 面板里占一个位置，暂无任何业务逻辑。
 */
class ActionsTabContentProvider(private val project: Project) : ChangesViewContentProvider {
    override fun initContent(): JComponent =
        JPanel(BorderLayout()).apply {
            add(JLabel("GitHub Actions — Coming Soon", SwingConstants.CENTER), BorderLayout.CENTER)
        }
}
