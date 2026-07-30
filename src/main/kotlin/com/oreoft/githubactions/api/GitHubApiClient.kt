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
class GitHubApiClient(private val token: String) {

    private companion object {
        const val BASE_URL = "https://api.github.com"
        const val API_VERSION = "2022-11-28"

        /** 共享单例 HttpClient，线程安全，内置连接池 */
        val httpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
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
     * 列出指定 workflow 的最近运行记录（最多 10 条）。
     * @throws GitHubApiException 当 API 返回非 2xx 响应时
     */
    fun listWorkflowRuns(owner: String, repo: String, workflowId: Long): List<GitHubWorkflowRun> {
        val body = get("/repos/$owner/$repo/actions/workflows/$workflowId/runs?per_page=10")
        return json.decodeFromString<WorkflowRunsResponse>(body).workflowRuns
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
            .uri(URI.create("$BASE_URL$path"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .timeout(Duration.ofSeconds(30))

    private fun send(request: HttpRequest): String {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val detail = response.body().take(300)
            throw GitHubApiException(
                "GitHub API ${response.statusCode()} for ${request.uri().path}: $detail"
            )
        }
        return response.body()
    }
}

/** GitHub API 返回非 2xx 时抛出的异常 */
class GitHubApiException(message: String) : Exception(message)
