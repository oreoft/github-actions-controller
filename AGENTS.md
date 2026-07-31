# AGENTS.md

本文件是本项目给 AI agent(Claude Code 等)看的工作说明,持续维护。
`CLAUDE.md` 里通过 `@AGENTS.md` 引用本文件,后续项目相关的约定、注意事项都写在这里,不要分散到两个文件里。

## 项目是什么

一个 JetBrains IDE 插件(Kotlin + IntelliJ Platform Plugin SDK),在 IDE 的 Git 工具窗口里加了个
"GitHub Actions" tab,可以看 workflow / run / job 日志、手动触发 workflow_dispatch。详见 README.md。

关键文件:

- `src/main/kotlin/com/oreoft/githubactions/ui/ActionsPanel.kt` — 主 UI(workflow 列表 / runs 列表 /
  job 详情面板,三者用 CardLayout 切换)
- `src/main/kotlin/com/oreoft/githubactions/api/GitHubApiClient.kt` — 封装 GitHub REST API 调用
- `src/main/kotlin/com/oreoft/githubactions/GitHubActionsBundle.kt` +
  `src/main/resources/messages/GitHubActionsBundle*.properties` — i18n 文案(英文 + 简体中文两份)
- `src/main/resources/META-INF/plugin.xml` — 插件描述、版本 change notes

没有测试目录,项目目前没有单元测试。

## 每次改完代码,必须自动打包验证

**这是硬性要求,不是可选项**:每次修改完 Kotlin 代码后,在汇报"改完了"之前,必须自己跑一次打包命令,
不要等用户要求,也不要只满足于 `compileKotlin` 编译通过就算完事。

```bash
# 先跑编译,快速排除语法/类型错误
./gradlew compileKotlin -q

# 再跑打包,产出可安装的插件 zip,方便用户手动装到 IDE 里验证
./gradlew buildPlugin -q
```

- 打包产物在 `build/distributions/*.zip`。
- 如果打包失败,先修复问题,再重新打包,不要把编译通过当成"打包通过"来汇报。
- 默认走本地 IDE(`useLocalIde=true`,读 `/Applications/IntelliJ IDEA.app`),这台机器上够用,不需要加
  `-PuseLocalIde=false`(那个是给没有本地 IDEA 装的机器/CI 用的,会从 JetBrains 仓库下载 IDE,慢很多)。
- 这是纯本地打包验证,不涉及发布,不需要额外确认就可以执行。

## 代码约定

- **所有 UI 文案必须走 i18n**:通过 `GitHubActionsBundle.message("key", ...)` 取,新增文案要同时在
  `GitHubActionsBundle.properties`(英文)和 `GitHubActionsBundle_zh_CN.properties`(简体中文)里加对应的
  key,不要硬编码字符串到 UI 组件里。
- 网络请求相关的操作都丢到 `AppExecutorUtil.getAppExecutorService()` 的后台线程去跑,回到 UI 用
  `SwingUtilities.invokeLater` / `onEdt`,不要在 EDT 线程里直接发 HTTP 请求。
- `GitHubApiClient` 的错误统一走 `GitHubApiException`,按 `statusCode` 区分处理(401/403/404/限流),
  见 `ActionsPanel.kt` 里的 `describeApiError`。

## 已知的 GitHub API 限制

- Run/Job 处于 `in_progress` / `queued` 状态时,`GET .../actions/jobs/{job_id}/logs` 会返回 404 —— GitHub
  只有 job 跑完(completed)才会把日志归档,这是官方 API 本身的限制,不是 bug,不要试图"修复"它。代码里
  已经专门捕获这个 404 并显示 `status.logs.not.found` 提示。
- 想要"运行中查看进度",只能轮询 job 的 `status` / `steps` 状态,做不到运行中的实时日志流(网页版用的是
  GitHub 内部私有接口,没有对外开放)。

## 维护这份文件

以后觉得哪些项目相关的背景、决策、坑值得记住,直接加到这份 AGENTS.md 里(不要另开新文件),保持它是
唯一的 agent 说明文档。内容太琐碎/会很快过期的(比如某次具体改动的细节)不用写,写会长期有效的约定和坑。
