package com.tombytee.releasefetch.domain

import java.net.URI

sealed class UrlParseResult {
    data class Success(val identifier: RepoIdentifier) : UrlParseResult()
    data class Error(val message: String) : UrlParseResult()
}

object GitHubUrlParser {
    private val validOwnerRegex = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")
    private val validRepoRegex = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val reservedTopLevelPaths = setOf(
        "about", "explore", "features", "issues", "login", "marketplace",
        "notifications", "orgs", "pricing", "pulls", "search", "settings",
        "signup", "sponsors", "topics", "trending",
    )

    fun parse(rawInput: String): UrlParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return UrlParseResult.Error("Paste a GitHub repository link or enter owner/repo.")
        }

        // Normalize bare github.com/... inputs by prepending https://
        val candidate = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("github.com/", ignoreCase = true) ||
                trimmed.startsWith("www.github.com/", ignoreCase = true) -> "https://$trimmed"
            trimmed.startsWith("git@github.com:", ignoreCase = true) -> {
                val sshPath = trimmed.substringAfter("git@github.com:")
                "https://github.com/$sshPath"
            }
            !trimmed.contains("://") && trimmed.contains("/") -> {
                val segments = trimmed.trim('/').split('/')
                if (segments.size >= 2 && !segments[0].contains('.')) {
                    return validatePair(segments[0], segments[1])
                }
                "https://$trimmed"
            }
            else -> return UrlParseResult.Error("Enter a valid GitHub repository URL (e.g. https://github.com/owner/repo).")
        }

        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return UrlParseResult.Error("Invalid URL format. Check the repository link and try again.")
        }

        val host = uri.host?.lowercase()?.removePrefix("www.") ?: ""
        if (host != "github.com") {
            return UrlParseResult.Error("Only github.com repository links are supported.")
        }

        val segments = (uri.path ?: "")
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }

        if (segments.size < 2) {
            return UrlParseResult.Error("Link must include both owner and repository name (github.com/owner/repo).")
        }

        return validatePair(segments[0], segments[1])
    }

    private fun validatePair(rawOwner: String, rawRepo: String): UrlParseResult {
        val owner = rawOwner.trim()
        val repo = rawRepo.trim().removeSuffix(".git")

        if (owner.lowercase() in reservedTopLevelPaths) {
            return UrlParseResult.Error("That looks like a GitHub navigation page rather than a repository.")
        }
        if (!validOwnerRegex.matches(owner)) {
            return UrlParseResult.Error("Invalid GitHub owner name: \"$owner\".")
        }
        if (!validRepoRegex.matches(repo) || repo == "." || repo == "..") {
            return UrlParseResult.Error("Invalid GitHub repository name: \"$repo\".")
        }

        return UrlParseResult.Success(RepoIdentifier(owner = owner, repo = repo))
    }
}
