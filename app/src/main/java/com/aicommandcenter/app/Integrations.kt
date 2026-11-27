package com.aicommandcenter.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Integrations(context: Context) {
    private val prefs = Store(context).prefs
    fun enabled(key: String) = prefs.getBoolean("integration_$key", key == "floating")
    fun enable(key: String, value: Boolean) { prefs.edit().putBoolean("integration_$key",value).apply() }
    fun setting(key: String, default: String) = prefs.getString("integration_$key", default) ?: default
    fun set(key: String, value: String) { prefs.edit().putString("integration_$key",value).apply() }
    fun shortcuts(): List<Shortcut> = runCatching {
        val raw=prefs.getString("integration_shortcut_list",null) ?: return IntegrationRules.defaults
        val a=JSONArray(raw)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Shortcut(o.getString("trigger"),o.getString("target")) } }.filter { IntegrationRules.validTrigger(it.trigger) }.distinctBy { it.trigger }.take(200)
    }.getOrDefault(IntegrationRules.defaults)
    fun saveShortcuts(items: List<Shortcut>) {
        require(items.size<=200 && items.all { IntegrationRules.validTrigger(it.trigger) } && items.distinctBy { it.trigger }.size==items.size)
        set("shortcut_list",JSONArray().apply { items.forEach { put(JSONObject().put("trigger",it.trigger).put("target",it.target)) } }.toString())
    }
    fun block(s: Store.State, app: String, target: String): String {
        val project=s.active[app].orEmpty()
        return IntegrationRules.resolve(s.commands,app,project,s.projects.find { it.id==project }?.repo,target)
    }
}
