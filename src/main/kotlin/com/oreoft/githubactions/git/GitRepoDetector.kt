package com.oreoft.githubactions.git

import com.intellij.openapi.project.Project
import com.oreoft.githubactions.api.OwnerRepo
import git4idea.repo.GitRepositoryManager

/**
 * 通过 Git4Idea API 读取当前项目的 Git remote URL，
 * 解析出 GitHub 仓库的 owner / repo / host。
 *
 * 支持格式：
 *   SSH:   git@github.com:owner/repo.git
 *          git@my-enterprise.com:owner/repo.git
 *   HTTPS: https://github.com/owner/repo.git
 *          https://my-enterprise.com/owner/repo
 */
object GitRepoDetector {

    // SSH: git@<host>:<owner>/<repo>[.git]
    private val SSH_PATTERN = Regex(
        """git@([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)/([A-Za-z0-9_.\-]+?)(?:\.git)?$"""
    )

    // HTTPS: https://<host>/<owner>/<repo>[.git]
    private val HTTPS_PATTERN = Regex(
        """https?://([A-Za-z0-9_.\-]+)/([A-Za-z0-9_.\-]+)/([A-Za-z0-9_.\-]+?)(?:\.git)?$"""
    )

    /**
     * 检测项目的 GitHub remote。
     * 优先 `origin`，其次遍历所有 remote。
     * @return 解析成功返回 [OwnerRepo]（含 host），否则返回 null
     */
    fun detect(project: Project): OwnerRepo? {
        val manager = GitRepositoryManager.getInstance(project)

        for (gitRepo in manager.repositories) {
            // 优先 origin
            val remotes = gitRepo.remotes.sortedBy { if (it.name == "origin") 0 else 1 }
            for (remote in remotes) {
                for (url in remote.urls) {
                    parseOwnerRepo(url)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseOwnerRepo(url: String): OwnerRepo? {
        SSH_PATTERN.find(url)?.let { m ->
            return OwnerRepo(owner = m.groupValues[2], repo = m.groupValues[3], host = m.groupValues[1])
        }
        HTTPS_PATTERN.find(url)?.let { m ->
            return OwnerRepo(owner = m.groupValues[2], repo = m.groupValues[3], host = m.groupValues[1])
        }
        return null
    }
}
