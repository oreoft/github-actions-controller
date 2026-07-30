package com.oreoft.githubactions.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Response wrappers ────────────────────────────────────────────────────────

@Serializable
data class WorkflowsResponse(
    @SerialName("total_count") val totalCount: Int,
    val workflows: List<GitHubWorkflow>
)

@Serializable
data class WorkflowRunsResponse(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("workflow_runs") val workflowRuns: List<GitHubWorkflowRun>
)

@Serializable
data class JobsResponse(
    @SerialName("total_count") val totalCount: Int,
    val jobs: List<GitHubJob>
)

// ─── Domain models ────────────────────────────────────────────────────────────

@Serializable
data class GitHubWorkflow(
    val id: Long,
    val name: String,
    /** "active" | "disabled_manually" | "disabled_inactivity" */
    val state: String,
    val path: String,
    @SerialName("html_url") val htmlUrl: String
)

@Serializable
data class GitHubWorkflowRun(
    val id: Long,
    val name: String? = null,
    /** "queued" | "in_progress" | "completed" | "waiting" */
    val status: String,
    /** "success" | "failure" | "cancelled" | "skipped" | "action_required" | null */
    val conclusion: String? = null,
    @SerialName("head_branch") val headBranch: String,
    @SerialName("head_commit") val headCommit: HeadCommit? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("run_number") val runNumber: Int,
    @SerialName("html_url") val htmlUrl: String
)

@Serializable
data class HeadCommit(
    val id: String = "",
    val message: String = ""
)

@Serializable
data class TriggerRequest(
    val ref: String
)

@Serializable
data class GitHubJob(
    val id: Long,
    val name: String,
    /** "queued" | "in_progress" | "completed" | "waiting" */
    val status: String,
    /** "success" | "failure" | "cancelled" | "skipped" | null */
    val conclusion: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null
)

// ─── Internal helpers ─────────────────────────────────────────────────────────

/** 解析 GitHub remote URL 后的 owner / repo 对 */
data class OwnerRepo(val owner: String, val repo: String, val host: String = "github.com") {
    override fun toString() = "$owner/$repo"
}
