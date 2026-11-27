package com.aicommandcenter.app

data class Project(val id: String, val repo: String)
data class Command(val id: String, val title: String, val body: String, val app: String = "", val project: String = "")
enum class Scope { UNIVERSAL, APP, PROJECT, ALL }
object CommandEngine {
    fun matching(commands: List<Command>, app: String, project: String, scope: Scope): List<Command> =
        commands.filter { c ->
            val appMatches = c.app.isEmpty() || c.app == app
            val projectMatches = c.project.isEmpty() || c.project == project
            appMatches && projectMatches && when (scope) {
                Scope.UNIVERSAL -> c.app.isEmpty() && c.project.isEmpty()
                Scope.APP -> c.app.isNotEmpty() && c.project.isEmpty()
                Scope.PROJECT -> c.project.isNotEmpty()
                Scope.ALL -> true
            }
        }.sortedBy { if (it.project.isNotEmpty()) 2 else if (it.app.isNotEmpty()) 1 else 0 }

    // Add the active repository once to each generated command block, including universal commands.
    fun render(commands: List<Command>, repo: String?): String {
        val body = commands.map { c ->
            if (repo != null) c.body.trim().replace("{{repo}}", repo).replace("{{repo_url}}", "https://github.com/$repo") else c.body.trim()
        }.filter { it.isNotBlank() }.distinct().joinToString("\n\n")
        if (body.isBlank() || repo == null || !validRepo(repo)) return body
        val header = "Target GitHub repository: $repo"
        return if (body == header || body.startsWith("$header\n")) body else "$header\n\n$body"
    }

    // Preserve the draft exactly. An identical trailing command block is not appended twice.
    fun append(draft: String, addition: String): String {
        if (addition.isBlank()) return draft
        val block = addition.trim()
        if (draft.trimEnd() == block || draft.trimEnd().endsWith("\n\n$block")) return draft
        if (draft.isEmpty()) return block
        val separator = when { draft.endsWith("\n\n") -> ""; draft.endsWith("\n") -> "\n"; else -> "\n\n" }
        return draft + separator + block
    }
    fun validRepo(repo: String): Boolean = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}").matches(repo) && repo.substringAfter('/') !in listOf(".", "..")
}
