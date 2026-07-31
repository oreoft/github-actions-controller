package com.oreoft.githubactions.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.oreoft.githubactions.GitHubActionsBundle.message
import com.oreoft.githubactions.api.GitHubApiClient
import com.oreoft.githubactions.api.GitHubApiException
import com.oreoft.githubactions.api.GitHubWorkflow
import com.oreoft.githubactions.api.GitHubWorkflowRun
import com.oreoft.githubactions.api.OwnerRepo
import com.oreoft.githubactions.api.GitHubJob
import com.oreoft.githubactions.auth.GitHubAuthService
import com.oreoft.githubactions.git.GitRepoDetector
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

private val rateLimitResetFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * 把异常转成给用户看的本地化错误文案。
 * 对 [GitHubApiException] 按状态码区分 401/403(含限流)/404，其余异常（网络超时等）退化为通用错误文案。
 */
private fun describeApiError(ex: Exception): String {
    val apiEx = ex as? GitHubApiException ?: return message("status.error", ex.message ?: "")
    val resetEpochSeconds = apiEx.rateLimitResetEpochSeconds
    return when {
        resetEpochSeconds != null ->
            message("status.error.ratelimit", rateLimitResetFormatter.format(Instant.ofEpochSecond(resetEpochSeconds)))

        apiEx.statusCode == 401 -> message("status.error.unauthorized")
        apiEx.statusCode == 403 -> message("status.error.forbidden", apiEx.githubMessage)
        apiEx.statusCode == 404 -> message("status.error.notfound")
        else -> message("status.error", apiEx.githubMessage)
    }
}

/**
 * GitHub Actions 主面板。
 * 所有 UI 文本通过 [com.oreoft.githubactions.GitHubActionsBundle] 获取，支持多语言。
 */
class ActionsPanel(private val project: Project) : JPanel(BorderLayout()) {

    // ─── Models ────────────────────────────────────────────────────────────────
    private val workflowModel = DefaultListModel<GitHubWorkflow>()
    private val runModel = DefaultListModel<GitHubWorkflowRun>()

    // ─── Views ─────────────────────────────────────────────────────────────────
    private val workflowList = JBList(workflowModel).apply {
        cellRenderer = WorkflowCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = message("list.workflows.empty")
    }
    private val runList = JBList(runModel).apply {
        cellRenderer = RunCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = message("list.runs.empty")
    }
    private val statusLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    // ─── State ─────────────────────────────────────────────────────────────────
    private var ownerRepo: OwnerRepo? = null
    private var selectedWorkflow: GitHubWorkflow? = null
    private var currentAccount: GithubAccount? = null
    
    private var currentRunPage = 1
    private var hasMoreRuns = false
    private var isLoadingRuns = false

    /**
     * 每发起一次 runs 请求（初次加载/加载更多）就自增。
     * 响应回到 EDT 时校验自己的编号是否还是最新的，
     * 防止“先选 A 后选 B，A 的响应却比 B 晚回来把 B 的结果覆盖掉”的竞态。
     */
    private var runRequestGeneration = 0
    
    // ─── Right Pane Card Layout ───────────────────────────────────────────────
    private val rightCardLayout = CardLayout()
    private val rightCardPanel = JPanel(rightCardLayout)
    
    private val jobDetailsPanel = JobDetailsPanel()

    init {
        setupUI()
        refresh()
    }

    // ─── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupUI() {
        border = JBUI.Borders.empty()

        val toolbar = createToolbar()
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        workflowList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                selectedWorkflow = workflowList.selectedValue ?: return@addListSelectionListener
                selectedWorkflow?.let { loadRuns(it) }
            }
        }

        runList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val run = runList.selectedValue ?: return
                
                // If it's the "Load More" dummy item
                if (run.id == -1L) {
                    if (e.button == MouseEvent.BUTTON1 && !isLoadingRuns) {
                        loadMoreRuns()
                    }
                    return
                }

                if (e.clickCount == 2) {
                    val or = ownerRepo
                    val acc = currentAccount
                    if (or != null && acc != null) {
                        // Switch to Job Details View
                        jobDetailsPanel.loadJobsForRun(run, or, acc)
                        rightCardLayout.show(rightCardPanel, "JobDetails")
                    }
                }
            }
        })

        val runsPane = buildTitledPane(message("panel.runs.title"), runList) {
            selectedWorkflow?.let { loadRuns(it) }
        }
        rightCardPanel.add(runsPane, "RunsList")
        rightCardPanel.add(jobDetailsPanel, "JobDetails")

        val splitter = OnePixelSplitter(false, 0.35f).apply {
            firstComponent = buildTitledPane(message("panel.workflows.title"), workflowList)
            secondComponent = rightCardPanel
        }
        add(splitter, BorderLayout.CENTER)

        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel, BorderLayout.WEST)
        }, BorderLayout.SOUTH)
    }

    private fun buildTitledPane(title: String, list: JBList<*>, onRefresh: (() -> Unit)? = null): JPanel =
        JPanel(BorderLayout()).apply {
            val titleBar = JPanel(BorderLayout()).apply {
                add(JBLabel("  $title").apply {
                    border = JBUI.Borders.empty(5, 6)
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                }, BorderLayout.WEST)
                if (onRefresh != null) {
                    val refreshAction = object : AnAction(
                        message("action.refresh.text"),
                        message("action.refresh.description"),
                        AllIcons.Actions.Refresh
                    ) {
                        override fun actionPerformed(e: AnActionEvent) = onRefresh()
                    }
                    val toolbar = ActionManager.getInstance()
                        .createActionToolbar("TitledPaneToolbar", DefaultActionGroup(refreshAction), true)
                    toolbar.targetComponent = list
                    add(toolbar.component, BorderLayout.EAST)
                }
            }
            add(titleBar, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {

            add(object : AnAction(
                message("action.refresh.text"),
                message("action.refresh.description"),
                AllIcons.Actions.Refresh
            ) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })

            addSeparator()

            add(object : AnAction(
                message("action.trigger.text"),
                message("action.trigger.description"),
                AllIcons.Actions.Execute
            ) {
                override fun actionPerformed(e: AnActionEvent) = triggerWorkflow()
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedWorkflow != null && currentAccount != null
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })

            addSeparator()

            add(AccountSwitcherAction())
        }

        return ActionManager.getInstance()
            .createActionToolbar("GitHubActionsToolbar", group, true)
    }

    /**
     * 工具栏账号切换下拉框。
     * 列出所有 IDEA GitHub 账号，当前账号勾选，点击切换并自动刷新。
     */
    private inner class AccountSwitcherAction : ComboBoxAction() {

        override fun update(e: AnActionEvent) {
            val acc = currentAccount
            e.presentation.text = acc?.name ?: message("action.account.no.login")
            e.presentation.icon = AllIcons.General.User
            e.presentation.description = message(
                "action.account.description",
                acc?.name ?: message("action.account.no.login")
            )
        }

        override fun createPopupActionGroup(
            button: javax.swing.JComponent,
            dataContext: DataContext
        ): DefaultActionGroup {
            val popupGroup = DefaultActionGroup()
            val accounts = GitHubAuthService.getAccounts()

            if (accounts.isEmpty()) {
                popupGroup.add(object : AnAction(message("action.account.no.accounts")) {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, "GitHub")
                    }
                })
            } else {
                for (account in accounts) {
                    val isCurrent = account == currentAccount
                    popupGroup.add(object : AnAction(
                        message("action.account.item.label", account.name, account.server.host),
                        message("action.account.item.description"),
                        if (isCurrent) AllIcons.Actions.Checked else AllIcons.General.User
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            if (!isCurrent) {
                                currentAccount = account
                                refresh()
                            }
                        }
                    })
                }
                popupGroup.addSeparator()
                popupGroup.add(object : AnAction(
                    message("action.account.manage"),
                    message("action.account.manage.description"),
                    AllIcons.General.Settings
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, "GitHub")
                    }
                })
            }
            return popupGroup
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    // ─── Data Loading ──────────────────────────────────────────────────────────

    fun refresh() {
        ownerRepo = GitRepoDetector.detect(project)
        val ownerRepoLocal = ownerRepo
        if (ownerRepoLocal == null) {
            setStatus(message("status.no.remote"))
            return
        }

        val accounts = GitHubAuthService.getAccounts()
        if (accounts.isEmpty()) {
            setStatus(message("status.no.account"))
            workflowModel.clear()
            runModel.clear()
            return
        }

        // 优先匹配 remote host 的账号；若 currentAccount 已设置且在列表中则保持不变
        val host = ownerRepoLocal.host
        if (currentAccount == null || currentAccount !in accounts) {
            currentAccount = GitHubAuthService.findAccountFor(host) ?: accounts.first()
        }
        val account = currentAccount!!

        setStatus(message("status.loading", account.name, ownerRepoLocal))

        runInBackground {
            val token = GitHubAuthService.getToken(account)
            if (token == null) {
                onEdt { setStatus(message("status.token.missing")) }
                return@runInBackground
            }
            try {
                val workflows =
                    GitHubApiClient(token, ownerRepoLocal.host).listWorkflows(ownerRepoLocal.owner, ownerRepoLocal.repo)
                onEdt {
                    workflowModel.clear()
                    workflows.forEach { workflowModel.addElement(it) }
                    setStatus(message("status.workflows.loaded", workflows.size, account.name, ownerRepoLocal))
                    if (workflows.isNotEmpty() && workflowList.selectedIndex < 0) {
                        workflowList.selectedIndex = 0
                    }
                }
            } catch (ex: Exception) {
                onEdt { setStatus(describeApiError(ex)) }
            }
        }
    }

    private fun loadRuns(workflow: GitHubWorkflow) {
        val (owner, repo, host) = ownerRepo ?: return
        val account = currentAccount ?: return

        runModel.clear()
        currentRunPage = 1
        hasMoreRuns = false
        isLoadingRuns = true
        setStatus(message("status.runs.loading", workflow.name))
        val requestGen = ++runRequestGeneration

        runInBackground {
            val token = GitHubAuthService.getToken(account) ?: run {
                onEdt {
                    if (requestGen != runRequestGeneration) return@onEdt
                    isLoadingRuns = false
                    setStatus(message("status.token.expired"))
                }
                return@runInBackground
            }
            try {
                val page = GitHubApiClient(token, host).listWorkflowRuns(owner, repo, workflow.id, currentRunPage, 20)
                onEdt {
                    // 这段响应对应的选择已经过期(用户切到别的 workflow 了)，丢弃，不能覆盖当前展示的内容
                    if (requestGen != runRequestGeneration) return@onEdt
                    hasMoreRuns = page.hasMore
                    runModel.clear()
                    page.runs.forEach { runModel.addElement(it) }
                    if (hasMoreRuns) {
                        runModel.addElement(createLoadMoreDummyItem())
                    }
                    setStatus(
                        if (page.runs.isEmpty()) message("status.runs.empty", workflow.name)
                        else message("status.runs.loaded", page.runs.size, workflow.name)
                    )
                    rightCardLayout.show(rightCardPanel, "RunsList")
                    isLoadingRuns = false
                }
            } catch (ex: Exception) {
                onEdt {
                    if (requestGen != runRequestGeneration) return@onEdt
                    isLoadingRuns = false
                    setStatus(describeApiError(ex))
                }
            }
        }
    }

    private fun loadMoreRuns() {
        val workflow = selectedWorkflow ?: return
        val (owner, repo, host) = ownerRepo ?: return
        val account = currentAccount ?: return

        isLoadingRuns = true
        currentRunPage++
        val requestGen = ++runRequestGeneration

        // Remove the dummy item while loading
        if (runModel.size() > 0 && runModel.lastElement().id == -1L) {
            runModel.removeElementAt(runModel.size() - 1)
        }

        setStatus(message("status.runs.loading", workflow.name))

        runInBackground {
            val token = GitHubAuthService.getToken(account) ?: run {
                onEdt {
                    if (requestGen != runRequestGeneration) return@onEdt
                    isLoadingRuns = false
                    setStatus(message("status.token.expired"))
                }
                return@runInBackground
            }
            try {
                val page = GitHubApiClient(token, host).listWorkflowRuns(owner, repo, workflow.id, currentRunPage, 20)
                onEdt {
                    if (requestGen != runRequestGeneration) return@onEdt
                    hasMoreRuns = page.hasMore
                    page.runs.forEach { runModel.addElement(it) }
                    if (hasMoreRuns) {
                        runModel.addElement(createLoadMoreDummyItem())
                    }
                    setStatus(message("status.runs.loaded", runModel.size() - if(hasMoreRuns) 1 else 0, workflow.name))
                    isLoadingRuns = false
                }
            } catch (ex: Exception) {
                onEdt {
                    if (requestGen != runRequestGeneration) return@onEdt
                    isLoadingRuns = false
                    currentRunPage-- // revert page bump
                    setStatus(describeApiError(ex))
                }
            }
        }
    }

    private fun createLoadMoreDummyItem() = GitHubWorkflowRun(
        id = -1L,
        status = "load_more",
        headBranch = "",
        createdAt = "",
        runNumber = 0,
        htmlUrl = ""
    )

    // ─── Actions ───────────────────────────────────────────────────────────────

    private fun triggerWorkflow() {
        val wf = selectedWorkflow ?: return
        val (owner, repo, host) = ownerRepo ?: return
        val account = currentAccount ?: return

        val defaultBranch = GitRepoDetector.detectCurrentBranch(project) ?: message("dialog.trigger.default.branch")
        val branch = Messages.showInputDialog(
            project,
            message("dialog.trigger.message"),
            message("dialog.trigger.title", wf.name),
            AllIcons.Actions.Execute,
            defaultBranch,
            null
        ) ?: return
        if (branch.isBlank()) return

        setStatus(message("status.trigger.sending", wf.name, branch))

        runInBackground {
            val token = GitHubAuthService.getToken(account) ?: run {
                onEdt { setStatus(message("status.token.expired")) }
                return@runInBackground
            }
            try {
                GitHubApiClient(token, host).triggerWorkflow(owner, repo, wf.id, branch)
                onEdt {
                    setStatus(message("status.trigger.success", wf.name, branch))
                    Timer(2000) { loadRuns(wf) }.apply { isRepeats = false; start() }
                }
            } catch (ex: Exception) {
                onEdt {
                    setStatus(message("status.trigger.failed"))
                    Messages.showErrorDialog(
                        project,
                        describeApiError(ex),
                        message("dialog.trigger.error.title")
                    )
                }
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun setStatus(msg: String) { statusLabel.text = msg }
    private fun runInBackground(block: () -> Unit) { AppExecutorUtil.getAppExecutorService().submit(block) }
    private fun onEdt(block: () -> Unit) { SwingUtilities.invokeLater(block) }
}

// ─── Cell Renderers ───────────────────────────────────────────────────────────

class WorkflowCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val wf = value as? GitHubWorkflow
        val label = super.getListCellRendererComponent(
            list, wf?.name ?: value, index, isSelected, cellHasFocus
        ) as JLabel
        label.border = JBUI.Borders.empty(5, 8)
        label.icon = if (wf?.state == "active") AllIcons.RunConfigurations.TestPassed
                     else AllIcons.RunConfigurations.TestIgnored
        return label
    }
}

class RunCellRenderer : DefaultListCellRenderer() {
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val run = value as? GitHubWorkflowRun
        val text = run?.let {
            val t = runCatching { fmt.format(Instant.parse(it.createdAt)) }.getOrDefault("")
            val msg = it.headCommit?.message?.lines()?.firstOrNull()?.take(60) ?: ""
            "#${it.runNumber}  [${it.headBranch}]  $msg  · $t"
        } ?: value

        val label = super.getListCellRendererComponent(
            list, text, index, isSelected, cellHasFocus
        ) as JLabel
        
        if (run?.id == -1L) {
            label.text = "  ${message("action.load.more")}"
            label.icon = null
            label.font = label.font.deriveFont(Font.ITALIC)
            label.foreground = UIUtil.getInactiveTextColor()
            return label
        }
        
        label.border = JBUI.Borders.empty(5, 8)
        label.icon = runStatusIcon(run)
        label.toolTipText = run?.let {
            "status: ${it.status}  conclusion: ${it.conclusion ?: "—"}  double-click to open"
        }
        return label
    }

    private fun runStatusIcon(run: GitHubWorkflowRun?): Icon = when {
        run == null -> AllIcons.RunConfigurations.TestIgnored
        run.status == "in_progress" || run.status == "waiting" -> AllIcons.RunConfigurations.TestPaused
        run.status == "queued" -> AllIcons.RunConfigurations.TestNotRan
        run.conclusion == "success" -> AllIcons.RunConfigurations.TestPassed
        run.conclusion == "failure" -> AllIcons.RunConfigurations.TestFailed
        run.conclusion == "cancelled" -> AllIcons.Actions.Cancel
        run.conclusion == "skipped" -> AllIcons.RunConfigurations.TestSkipped
        run.conclusion == "action_required" -> AllIcons.General.BalloonWarning
        else -> AllIcons.RunConfigurations.TestIgnored
    }
}

// ─── Job Details Panel ────────────────────────────────────────────────────────

class JobDetailsPanel : JPanel(BorderLayout()) {
    
    private val jobModel = DefaultListModel<GitHubJob>()
    private val jobList = JBList(jobModel).apply {
        cellRenderer = JobCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val logTextArea = JTextArea().apply {
        isEditable = false
        val globalScheme = EditorColorsManager.getInstance().globalScheme
        font = Font(globalScheme.editorFontName, Font.PLAIN, globalScheme.editorFontSize)
        background = globalScheme.defaultBackground
        foreground = globalScheme.defaultForeground
        margin = JBUI.insets(5)
    }
    private val headerLabel = JBLabel("").apply {
        font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(0, 10, 0, 0)
    }
    private val loadingIcon = AsyncProcessIcon("LoadingJobLogs").apply {
        isVisible = false
        border = JBUI.Borders.empty(0, 10, 0, 10)
    }
    
    private var currentRun: GitHubWorkflowRun? = null
    private var currentOwnerRepo: OwnerRepo? = null
    private var currentAccount: GithubAccount? = null

    init {
        val backAction = object : AnAction(message("action.back.to.runs"), "", AllIcons.Actions.Back) {
            override fun actionPerformed(e: AnActionEvent) {
                val parentCard = this@JobDetailsPanel.parent
                if (parentCard?.layout is CardLayout) {
                    (parentCard.layout as CardLayout).show(parentCard, "RunsList")
                }
            }
        }
        val refreshAction = object : AnAction(
            message("action.refresh.text"),
            message("action.refresh.description"),
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("JobDetailsToolbar", DefaultActionGroup(backAction, refreshAction), true).apply {
                targetComponent = this@JobDetailsPanel
            }
        
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
            add(toolbar.component, BorderLayout.WEST)
            
            val centerPanel = JPanel(BorderLayout()).apply {
                add(headerLabel, BorderLayout.WEST)
                add(loadingIcon, BorderLayout.EAST)
            }
            add(centerPanel, BorderLayout.CENTER)
        }
        
        val splitter = OnePixelSplitter(true, 0.3f).apply {
            firstComponent = JBScrollPane(jobList).apply {
                border = JBUI.Borders.empty()
            }
            secondComponent = JBScrollPane(logTextArea).apply {
                border = JBUI.Borders.empty()
            }
        }
        
        add(headerPanel, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        
        jobList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                jobList.selectedValue?.let { loadJobLog(it) }
            }
        }
    }

    fun loadJobsForRun(
        run: GitHubWorkflowRun,
        ownerRepo: OwnerRepo,
        account: GithubAccount,
        preserveSelectedJobId: Long? = null
    ) {
        currentRun = run
        currentOwnerRepo = ownerRepo
        currentAccount = account

        headerLabel.text = "#${run.runNumber} [${run.headBranch}]"
        if (preserveSelectedJobId == null) {
            jobModel.clear()
            logTextArea.text = message("status.jobs.loading")
        }

        showLoading()
        AppExecutorUtil.getAppExecutorService().submit {
            val token = GitHubAuthService.getToken(account) ?: run {
                SwingUtilities.invokeLater {
                    logTextArea.text = message("status.token.expired")
                    hideLoading()
                }
                return@submit
            }
            try {
                val jobs =
                    GitHubApiClient(token, ownerRepo.host).listWorkflowJobs(ownerRepo.owner, ownerRepo.repo, run.id)
                SwingUtilities.invokeLater {
                    jobModel.clear()
                    jobs.forEach { jobModel.addElement(it) }
                    if (jobs.isNotEmpty()) {
                        val targetIndex = preserveSelectedJobId
                            ?.let { id -> (0 until jobModel.size()).firstOrNull { jobModel.getElementAt(it).id == id } }
                            ?: 0
                        if (jobList.selectedIndex == targetIndex) {
                            // 选中项没变，selection listener 不会触发，需要手动重新加载日志
                            loadJobLog(jobModel.getElementAt(targetIndex))
                        } else {
                            jobList.selectedIndex = targetIndex
                        }
                    } else {
                        logTextArea.text = ""
                    }
                    hideLoading()
                }
            } catch (ex: Exception) {
                SwingUtilities.invokeLater {
                    logTextArea.text = describeApiError(ex)
                    hideLoading()
                }
            }
        }
    }

    private fun refresh() {
        val run = currentRun ?: return
        val ownerRepo = currentOwnerRepo ?: return
        val account = currentAccount ?: return
        loadJobsForRun(run, ownerRepo, account, preserveSelectedJobId = jobList.selectedValue?.id)
    }

    private fun loadJobLog(job: GitHubJob) {
        logTextArea.text = message("status.logs.loading")
        val ownerRepo = currentOwnerRepo ?: return
        val account = currentAccount ?: return
        
        showLoading()
        AppExecutorUtil.getAppExecutorService().submit {
            val token = GitHubAuthService.getToken(account) ?: run {
                SwingUtilities.invokeLater {
                    logTextArea.text = message("status.token.expired")
                    hideLoading()
                }
                return@submit
            }
            try {
                val logText = GitHubApiClient(token, ownerRepo.host).getJobLog(ownerRepo.owner, ownerRepo.repo, job.id)
                SwingUtilities.invokeLater {
                    logTextArea.text = logText
                    logTextArea.caretPosition = 0
                    hideLoading()
                }
            } catch (ex: Exception) {
                SwingUtilities.invokeLater {
                    logTextArea.text = if (ex is GitHubApiException && ex.statusCode == 404) {
                        message("status.logs.not.found")
                    } else {
                        describeApiError(ex)
                    }
                    hideLoading()
                }
            }
        }
    }

    private fun showLoading() {
        loadingIcon.resume()
        loadingIcon.isVisible = true
    }

    private fun hideLoading() {
        loadingIcon.suspend()
        loadingIcon.isVisible = false
    }
}

class JobCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val job = value as? GitHubJob
        val label = super.getListCellRendererComponent(
            list, job?.name ?: value, index, isSelected, cellHasFocus
        ) as JLabel
        label.border = JBUI.Borders.empty(5, 8)
        
        label.icon = when {
            job == null -> AllIcons.RunConfigurations.TestIgnored
            job.status == "in_progress" || job.status == "waiting" -> AllIcons.RunConfigurations.TestPaused
            job.status == "queued" -> AllIcons.RunConfigurations.TestNotRan
            job.conclusion == "success" -> AllIcons.RunConfigurations.TestPassed
            job.conclusion == "failure" -> AllIcons.RunConfigurations.TestFailed
            job.conclusion == "cancelled" -> AllIcons.Actions.Cancel
            job.conclusion == "skipped" -> AllIcons.RunConfigurations.TestSkipped
            else -> AllIcons.RunConfigurations.TestIgnored
        }
        return label
    }
}
