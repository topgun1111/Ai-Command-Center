package com.aicommandcenter.app

import org.junit.Assert.*
import org.junit.Test

class RepositoryContextTest {
    private val commands = listOf(
        Command("u","Universal","Inspect the latest source."),
        Command("a","App","Run tests.",app="ai.chat"),
        Command("p","Project","Push {{repo}} to {{repo_url}}.",project="p1")
    )
    private val repo="topgun1111/pcshare"
    private val header="Target GitHub repository: $repo"
    @Test fun selectedRepositoryIncludedAcrossScopes() {
        for(target in listOf("ALL","UNIVERSAL","APP","PROJECT","command:u","command:a","command:p")) {
            val block=IntegrationRules.resolve(commands,"ai.chat","p1",repo,target)
            assertTrue(target,block.startsWith("$header\n\n"))
            assertEquals(1,block.lineSequence().count { it==header })
        }
    }
    @Test fun noProjectAndNoMatchingCommands() {
        assertEquals("Inspect the latest source.",IntegrationRules.resolve(commands,"ai.chat","",null,"UNIVERSAL"))
        assertEquals("",IntegrationRules.resolve(commands,"ai.other","p1",repo,"APP"))
        assertEquals("",CommandEngine.render(emptyList(),repo))
        assertEquals("",CommandEngine.render(listOf(Command("b","Blank"," ")),repo))
    }
    @Test fun substitutesAndPreservesDraft() {
        val block=IntegrationRules.resolve(commands,"ai.chat","p1",repo,"ALL")
        assertTrue(block.contains("Push $repo to https://github.com/$repo."))
        val draft="Fix the bug. "
        val full=CommandEngine.append(draft,block)
        assertEquals("$draft\n\n$block",full)
        assertEquals(full,CommandEngine.append(full,block))
        assertEquals("Inspect the latest source.",commands[0].body)
    }
    @Test fun updatesTargetWithoutEditingSavedCommand() {
        val first=CommandEngine.render(listOf(commands[0]),repo)
        val second=CommandEngine.render(listOf(commands[0]),"topgun1111/mpv")
        assertTrue(first.contains(repo))
        assertTrue(second.startsWith("Target GitHub repository: topgun1111/mpv\n"))
        assertFalse(second.contains(repo))
        assertEquals("Inspect the latest source.",commands[0].body)
    }
}
