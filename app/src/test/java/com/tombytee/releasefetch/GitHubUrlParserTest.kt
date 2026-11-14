package com.tombytee.releasefetch

import com.tombytee.releasefetch.domain.GitHubUrlParser
import com.tombytee.releasefetch.domain.RepoIdentifier
import com.tombytee.releasefetch.domain.UrlParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUrlParserTest {

    @Test
    fun `parses standard https github repository url`() {
        val result = GitHubUrlParser.parse("https://github.com/termux/termux-app")
        assertEquals(UrlParseResult.Success(RepoIdentifier("termux", "termux-app")), result)
    }

    @Test
    fun `parses url with releases path and git suffix`() {
        val result = GitHubUrlParser.parse("https://github.com/AntennaPod/AntennaPod/releases/tag/3.5.0")
        assertEquals(UrlParseResult.Success(RepoIdentifier("AntennaPod", "AntennaPod")), result)

        val gitResult = GitHubUrlParser.parse("https://github.com/syncthing/syncthing.git")
        assertEquals(UrlParseResult.Success(RepoIdentifier("syncthing", "syncthing")), gitResult)
    }

    @Test
    fun `parses owner slash repo shorthand`() {
        val result = GitHubUrlParser.parse("  localsend/localsend  ")
        assertEquals(UrlParseResult.Success(RepoIdentifier("localsend", "localsend")), result)
    }

    @Test
    fun `rejects non github host and reserved navigation paths`() {
        val nonGithub = GitHubUrlParser.parse("https://gitlab.com/user/repo")
        assertTrue(nonGithub is UrlParseResult.Error)

        val reserved = GitHubUrlParser.parse("https://github.com/explore/android")
        assertTrue(reserved is UrlParseResult.Error)

        val blank = GitHubUrlParser.parse("   ")
        assertTrue(blank is UrlParseResult.Error)
    }
}
