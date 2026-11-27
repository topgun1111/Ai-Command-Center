package com.aicommandcenter.app

import java.util.Locale

object GitHubRepoRules {
    const val MAX_PROJECTS=500
    private val ownerPattern=Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
    fun normalizeOwner(raw:String):String? {
        val input=raw.trim().removePrefix("https://github.com/").trimEnd('/').removePrefix("@")
        return input.takeIf { ownerPattern.matches(it) && "--" !in it }
    }
    fun newNames(existing:List<String>,selected:List<String>):List<String> {
        val seen=existing.map { it.lowercase(Locale.ROOT) }.toMutableSet()
        val additions=selected.filter { CommandEngine.validRepo(it) && seen.add(it.lowercase(Locale.ROOT)) }
        require(existing.size+additions.size<=MAX_PROJECTS) { "Maximum 500 projects. Select fewer repositories." }
        return additions
    }
}
