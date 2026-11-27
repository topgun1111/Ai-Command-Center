package com.aicommandcenter.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Store(context: Context) {
    val prefs = context.getSharedPreferences("command_center", Context.MODE_PRIVATE)
    data class State(val apps: Set<String> = emptySet(), val projects: List<Project> = emptyList(), val commands: List<Command> = emptyList(), val active: Map<String,String> = emptyMap(), val enabled: Boolean = true)
    private var cachedRaw: String? = null
    private var cachedState: State = State()
    fun load(): State {
        val raw = prefs.getString("state", null)
        if (raw != cachedRaw) {
            cachedState = if (raw == null) State() else runCatching { decode(raw) }.getOrElse { State(enabled=false) }
            cachedRaw = raw
        }
        return cachedState
    }
    fun save(state: State) { val encoded = encode(state); decode(encoded); prefs.edit().putString("state", encoded).apply() }
    fun change(transform: (State) -> State) = save(transform(load()))
    fun id(): String = UUID.randomUUID().toString()
    fun encode(s: State): String = JSONObject().apply {
        put("version", 1); put("enabled", s.enabled); put("apps", JSONArray(s.apps.toList()))
        put("projects", JSONArray().apply { s.projects.forEach { put(JSONObject().put("id",it.id).put("repo",it.repo)) } })
        put("commands", JSONArray().apply { s.commands.forEach { put(JSONObject().put("id",it.id).put("title",it.title).put("body",it.body).put("app",it.app).put("project",it.project)) } })
        put("active", JSONObject(s.active))
    }.toString(2)
    fun decode(raw: String): State {
        require(raw.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Backup is too large" }
        val o = JSONObject(raw); require(o.getInt("version") == 1) { "Unsupported backup version" }
        val a = o.getJSONArray("apps"); val apps = (0 until a.length()).map { a.getString(it) }.toSet()
        require(apps.size <= 500 && apps.all { it.length <= 255 && it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) })
        val p = o.getJSONArray("projects"); require(p.length() <= 500)
        val projects = (0 until p.length()).map { p.getJSONObject(it).let { v -> Project(v.getString("id"), v.getString("repo")) } }
        require(projects.all { it.id.isNotBlank() && it.id.length <= 100 && CommandEngine.validRepo(it.repo) } && projects.map { it.id }.distinct().size == projects.size)
        val c = o.getJSONArray("commands"); require(c.length() <= 2000)
        val commands = (0 until c.length()).map { c.getJSONObject(it).let { v -> Command(v.getString("id"),v.getString("title"),v.getString("body"),v.optString("app"),v.optString("project")) } }
        val ids = projects.map { it.id }.toSet()
        require(commands.all { it.id.isNotBlank() && it.id.length <= 100 && it.title.isNotBlank() && it.title.length <= 120 && it.body.isNotBlank() && it.body.length <= 100_000 && (it.app.isEmpty() || it.app.length <= 255 && it.app.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) && (it.project.isEmpty() || it.project in ids) } && commands.map { it.id }.distinct().size == commands.size)
        val act = o.getJSONObject("active"); val active = act.keys().asSequence().associateWith { act.getString(it) }.filter { it.value in ids && it.key.length <= 255 && it.key.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
        return State(apps,projects,commands,active,o.optBoolean("enabled",true))
    }
}
