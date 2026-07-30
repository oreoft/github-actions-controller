# GitHub Actions Runs 只读展示 — 设计文档

日期:2026-07-30

## 背景与本轮目标

插件外壳(Git 面板里的 "GitHub Actions" tab)已经验证可行。本轮要把 tab 内容从占位符换成真实数据:复刻用户提供的截图 ——

- 左侧:workflow runs 列表,带 Workflow/Actor/Status/Branch/Event 筛选,支持"加载更多"翻页。
- 右侧:选中一个 run 后,展示它的 job 列表(状态、耗时),点击某个 step 时懒加载并展示该 step 的日志文本。

本轮**不做**:触发 workflow(`workflow_dispatch`)、YAML 解析动态表单、自动轮询刷新。这些是下一轮迭代的范围。

## 已确认的关键决策

| 决策点 | 选择 |
|---|---|
| HTTP 客户端 | IntelliJ Platform 自带 `com.intellij.util.io.HttpRequests`,自动处理代理/超时,零新增依赖 |
| JSON 解析 | Gson(平台自带) |
| 右侧详情呈现 | `JTree`:root 隐藏,一级 job,二级 step,step 节点展开时懒加载日志作为叶子内容 |
| 日志加载粒度 | 点击某 job 下任意一个 step → 拉取整个 job 的原始日志(GitHub API 没有单 step 日志端点)→ 按 `##[group]`/`##[endgroup]` 标记切成每个 step 的片段 → 内存缓存,同 job 下再点别的 step 不用重新请求 |
| 筛选 | 本轮就做(Workflow/Actor/Status/Branch/Event) |
| 刷新方式 | 手动 Refresh 按钮,不做自动轮询 |
| 分页 | 每页 30 条,"加载更多"按钮追加下一页 |

## 仓库与账号识别(复用 GitHub 插件现成设施)

不自己写 git remote URL 解析。直接复用 bundled GitHub 插件已经维护好的机制:

- `project.service<GHHostedRepositoriesManager>().knownRepositoriesFlow` —— 已经把当前项目的所有 git remote 映射到已知的 GitHub server,返回 `Set<GHGitRepositoryMapping>`,每个 mapping 提供 `.repository: GHRepositoryCoordinates`(server + owner/repo)。若有多个 mapping(多 remote/多仓库),本轮先取第一个,UI 上展示当前识别到的 `owner/repo`,不做选择器。
- 账号匹配:`GHAccountsUtil.accounts`(全部已登录账号)按 server 过滤,优先用 `GHAccountsUtil.getDefaultAccount(project)`(项目默认账号)。
- Token:`GHCompatibilityUtil.getOrRequestToken(account, project)`(后台线程调用,内部先静默查已存 credential,查不到再弹交互式登录)。

插件需要新增依赖 `org.jetbrains.plugins.github`(GitHub 插件 id,已通过源码核实)。

## 包结构

```
com.oreoft.githubactions
├── repo/
│   └── GitHubRepoContext.kt        // 解析出的 owner/repo/server/account/token,连同解析失败的原因
├── api/
│   ├── GitHubActionsApiClient.kt   // HttpRequests 封装:list runs / list jobs / job logs / list workflows
│   ├── model/                      // RunSummary, JobSummary, StepSummary, WorkflowSummary (Gson data class)
│   └── JobLogParser.kt             // 按 ##[group]/##[endgroup] 切分原始日志文本为逐 step 片段
├── ui/
│   ├── ActionsTabContentProvider.kt  // 替换现有占位实现,组装 splitter
│   ├── RunsListPanel.kt             // 左侧:筛选下拉 + JBList + 加载更多
│   ├── RunDetailPanel.kt            // 右侧:JTree(job → step → 懒加载日志)
│   └── ActionsPanelState.kt         // 未识别到仓库/账号/请求出错时的空状态展示
└── GitHubActionsService.kt          // @Service(PROJECT):持有当前 repo/account、runs 缓存、job 日志缓存
```

## 数据流

1. Tab 首次展示 → 解析 repo + account(见上)。失败(未检测到仓库 / 没有匹配账号)→ 展示对应的空状态提示,而不是抛异常或空白。
2. 解析成功 → 后台线程并发拉取:`GET /actions/workflows`(填充 Workflow 筛选下拉的可选项)+ 第一页 `GET /actions/runs?per_page=30`。
3. 用户改变任意筛选项 → 带上对应 query 参数(`workflow_id` / `actor` / `status` / `branch` / `event`)重新拉取第 1 页,替换列表。
4. 点击"加载更多" → 用同样的筛选条件请求下一页,追加到列表末尾。
5. 点击某一条 run → 请求 `GET /actions/runs/{run_id}/jobs`,用返回的 job + step 元数据(名称/状态/耗时,不含日志)填充右侧 JTree。
6. 展开/点击某个 step 节点 → 检查该 job 的日志是否已缓存;没有则请求 `GET /actions/jobs/{job_id}/logs`(原始文本),用 `JobLogParser` 按 group 标记切片并缓存;若切片数量和 step 数量对不上(比如自定义 runner 输出格式不同),兜底展示整份原始日志并提示"无法精确定位到该 step,已展示完整日志",不静默展示错误内容。
7. 点击 Refresh → 清空 runs 缓存和 job 日志缓存,按当前筛选条件重新拉取第 1 页。

## 错误处理

- 401/403:在 tab 顶部展示一条错误 banner,包含 GitHub 返回的原始 message,并给出提示(比如 token 缺少 `workflow` scope、仓库无权限)。
- 403 且 `X-RateLimit-Remaining: 0`:提示限流,展示 `X-RateLimit-Reset` 换算后的恢复时间。
- 404:提示"未找到该仓库或无访问权限",区别于普通请求失败。
- 所有网络请求跑在后台线程(`Task.Backgroundable` 或等价机制),UI 更新一律 `invokeLater` 回 EDT;任何异常都要落到同一个错误 banner 展示逻辑,不允许吞掉或让 EDT 抛栈。

## 测试策略

Swing UI 本身不做自动化测试(不现实也没必要),但以下纯逻辑值得写单元测试:

- `JobLogParser`:用真实抓取的日志文本片段做 fixture,验证按 group 标记切分的正确性,以及切分数量不匹配时的兜底逻辑。
- DTO 反序列化:用 GitHub API 真实响应样例(简化后)验证 Gson 映射到 `RunSummary`/`JobSummary`/`StepSummary`/`WorkflowSummary` 的字段正确性。
- 仓库/账号识别 + API 客户端不做单元测试(强依赖 IDE 运行时服务和网络),靠 `runIde` 手动验证,和目前验证空壳 tab 的方式一致。

## 本轮范围之外(留到下一轮)

- 触发 `workflow_dispatch`
- 解析 workflow YAML 里的 `inputs` 并动态生成表单
- 自动轮询刷新进行中的 run
- Cancel / Rerun / Rerun failed jobs
