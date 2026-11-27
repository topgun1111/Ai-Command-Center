package com.aicommandcenter.app

data class Shortcut(val trigger: String, val target: String)
object IntegrationRules {
    val defaults = listOf(Shortcut("#all", "ALL"), Shortcut("#uni", "UNIVERSAL"), Shortcut("#app", "APP"), Shortcut("#project", "PROJECT"))
    fun validTrigger(s: String) = Regex("#[a-zA-Z0-9_-]{1,32}").matches(s)
    fun find(text: String, shortcuts: List<Shortcut>): Shortcut? {
        if (!text.endsWith(';')) return null
        val token = text.dropLast(1).takeLastWhile { !it.isWhitespace() }
        return shortcuts.firstOrNull { it.trigger == token }
    }
    fun expand(text: String, shortcut: Shortcut, block: String): String? {
        if (block.isBlank() || find(text, listOf(shortcut)) == null) return null
        return CommandEngine.append(text.dropLast(shortcut.trigger.length + 1), block)
    }
    fun resolve(commands: List<Command>, app: String, project: String, repo: String?, target: String): String {
        val scope = Scope.values().firstOrNull { it.name == target }
        val selected = if (scope != null) CommandEngine.matching(commands, app, project, scope)
            else CommandEngine.matching(commands, app, project, Scope.ALL).filter { "command:${it.id}" == target }
        return CommandEngine.render(selected, repo)
    }
}
