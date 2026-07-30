package com.oreoft.githubactions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.oreoft.githubactions.ui.ActionsPanel
import javax.swing.JComponent

/**
 * Git 面板 "GitHub Actions" Tab 的内容提供者。
 * 每个 Project 独立实例化一个 [ActionsPanel]。
 */
class ActionsTabContentProvider(private val project: Project) : ChangesViewContentProvider {
    override fun initContent(): JComponent = ActionsPanel(project)
}
