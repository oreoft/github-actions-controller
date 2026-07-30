package com.oreoft.githubactions.api

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 轻量 GitHub REST API 客户端，基于 Java 17 内置 HttpClient。
 * 不依赖任何外部 HTTP 库，避免和 IntelliJ 平台产生类加载冲突。
 *
 * [httpClient] 是 companion object 级别的单例，整个插件生命周期共享一个连接池，
 * 避免每次请求都新建重量级对象。
 */
class GitHubApiClient(private val token: String, host: String = "github.com") {

    /**
     * github.com 走公共 REST API；GitHub Enterprise Server 走各自域名下的 /api/v3。
     * 参考：https://docs.github.com/en/enterprise-server/rest/quickstart
     */
    private val baseUrl: String =
        if (host.equals("github.com", ignoreCase = true)) "https://api.github.com"
        else "https://$host/api/v3"

    private companion object {
        const val API_VERSION = "2022-11-28"

        /** 共享单例 HttpClient，线程安全，内置连接池 */
        val httpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        val json = Json { ignoreUnknownKeys = true }
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * 列出仓库下的所有 workflow。
     * @throws GitHubApiException 当 API 返回非 2xx 响应时
     */
    fun listWorkflows(owner: String, repo: String): List<GitHubWorkflow> {
        val body = get("/repos/$owner/$repo/actions/workflows")
        return json.decodeFromString<WorkflowsResponse>(body).workflows
    }

    /**
     * 列出指定 workflow 的运行记录。
     * [RunsPage.hasMore] 基于响应里真实的 total_count 计算，而不是猜测"本页是否凑满了 perPage"——
     * 后者在 total 恰好是 perPage 整数倍时会多出一次点了也没有结果的空翻页。
     * @throws GitHubApiException 当 API 返回非 2xx 响应时
     */
    fun listWorkflowRuns(owner: String, repo: String, workflowId: Long, page: Int = 1, perPage: Int = 20): RunsPage {
        val body = get("/repos/$owner/$repo/actions/workflows/$workflowId/runs?page=$page&per_page=$perPage")
        val response = json.decodeFromString<WorkflowRunsResponse>(body)
        val hasMore = page.toLong() * perPage < response.totalCount
        return RunsPage(response.workflowRuns, hasMore)
    }

    /**
     * 列出指定 run 的所有 job。
     * @throws GitHubApiException 当 API 返回非 2xx 响应时
     */
    fun listWorkflowJobs(owner: String, repo: String, runId: Long): List<GitHubJob> {
        val body = get("/repos/$owner/$repo/actions/runs/$runId/jobs")
        return json.decodeFromString<JobsResponse>(body).jobs
    }

    /**
     * 获取指定 job 的原始日志文本。
     * @throws GitHubApiException 当 API 返回非 2xx 响应时
     */
    fun getJobLog(owner: String, repo: String, jobId: Long): String {
        return get("/repos/$owner/$repo/actions/jobs/$jobId/logs")
    }

    /**
     * 手动触发 workflow dispatch 事件。
     * 要求 workflow 文件中配置了 `on: workflow_dispatch`。
     * @throws GitHubApiException 当 API 返回非 2xx 响应时
     */
    fun triggerWorkflow(owner: String, repo: String, workflowId: Long, branch: String) {
        // 用 Json 序列化确保 branch 中的特殊字符（引号、反斜杠等）被正确转义
        val body = json.encodeToString(
            TriggerRequest.serializer(),
            TriggerRequest(ref = branch.trim())
        )
        post("/repos/$owner/$repo/actions/workflows/$workflowId/dispatches", body)
    }

    // ─── Private HTTP helpers ──────────────────────────────────────────────────

    private fun get(path: String): String {
        val request = buildRequest(path).GET().build()
        return send(request)
    }

    private fun post(path: String, jsonBody: String) {
        val request = buildRequest(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        send(request)
    }

    private fun buildRequest(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .timeout(Duration.ofSeconds(30))

    private fun send(request: HttpRequest): String {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw toApiException(response)
        }
        return response.body()
    }

    private fun toApiException(response: HttpResponse<String>): GitHubApiException {
        val statusCode = response.statusCode()
        val githubMessage = extractMessage(response.body()) ?: response.body().take(300)
        val rateLimitResetEpochSeconds =
            if (statusCode == 403 && response.headers().firstValue("X-RateLimit-Remaining").orElse(null) == "0") {
                response.headers().firstValue("X-RateLimit-Reset").orElse(null)?.toLongOrNull()
            } else null
        return GitHubApiException(statusCode, githubMessage, rateLimitResetEpochSeconds)
    }

    /** 尝试从 GitHub 错误响应体里取出 message 字段；解析失败（比如网关返回了 HTML）就返回 null，交给调用方兜底 */
    private fun extractMessage(body: String): String? =
        try {
            json.decodeFromString<GitHubErrorResponse>(body).message
        } catch (e: Exception) {
            null
        }
}

/**
 * GitHub API 返回非 2xx 时抛出的异常。
 * @param statusCode HTTP 状态码
 * @param githubMessage GitHub 返回体里的 message 字段；解析不出时退化为截断后的原始 body
 * @param rateLimitResetEpochSeconds 仅当命中限流（403 且 X-RateLimit-Remaining: 0）时有值，是恢复时间的 epoch 秒
 */
class GitHubApiException(
    val statusCode: Int,
    val githubMessage: String,
    val rateLimitResetEpochSeconds: Long? = null
) : Exception("GitHub API $statusCode: $githubMessage")
