package com.aicommandcenter.app
import org.junit.Assert.*
import org.junit.Test
class GitHubRepoRulesTest {
    @Test fun normalizesUsernameAndProfileUrl() {
        assertEquals("topgun1111",GitHubRepoRules.normalizeOwner(" https://github.com/topgun1111/ "))
        assertEquals("Octo-cat",GitHubRepoRules.normalizeOwner("@Octo-cat"))
        for(bad in listOf("","../x","https://evil.com/user","foo/bar","-bad","bad-","a--b","a".repeat(40)))assertNull(GitHubRepoRules.normalizeOwner(bad))
    }
    @Test fun preservesExistingProjectsAndDeduplicates() {
        assertEquals(listOf("a/new"),GitHubRepoRules.newNames(listOf("a/old"),listOf("A/OLD","a/new","A/NEW","bad")))
    }
    @Test fun enforcesProjectLimit() {
        try { GitHubRepoRules.newNames((1..500).map { "a/r$it" },listOf("a/overflow")); fail("Expected limit") } catch(expected:IllegalArgumentException) {}
    }
}
