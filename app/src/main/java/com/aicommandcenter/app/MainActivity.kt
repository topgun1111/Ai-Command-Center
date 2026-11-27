package com.aicommandcenter.app

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.*
import android.graphics.Color
import android.net.Uri
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var updater: Updater
    private lateinit var integrations: Integrations
    private var integrationPage = ""
    private var updateBusy = false
    private var page = 0
    private var draft = ""
    private var composerApp = ""
    private var composerProject = ""
    private var editor: EditText? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(s: String, size: Float = 16f) = TextView(this).apply { text = s; textSize = size; setTextColor(Color.rgb(235,241,255)); setPadding(dp(4),dp(6),dp(4),dp(6)) }
    private fun button(s: String, action: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { action() } }
    private fun field(hint: String, value: String = "", multi: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else InputType.TYPE_CLASS_TEXT; if (multi) { minLines = 4; gravity = android.view.Gravity.TOP } else setSingleLine(true)
    }
    private fun notify(s: String) = Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun capture() { editor?.let { draft = it.text.toString(); store.prefs.edit().putString("draft",draft).apply() }; editor = null }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); store = Store(this); integrations = Integrations(this); integrationPage = savedInstanceState?.getString("integrationPage").orEmpty(); updater = Updater(applicationContext); draft = savedInstanceState?.getString("draft") ?: store.prefs.getString("draft", "").orEmpty(); page = savedInstanceState?.getInt("page") ?: 0; composerApp = savedInstanceState?.getString("composerApp").orEmpty(); composerProject = savedInstanceState?.getString("composerProject").orEmpty(); render()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("draft",editor?.text?.toString() ?: draft); outState.putInt("page",page); outState.putString("integrationPage",integrationPage); outState.putString("composerApp",composerApp); outState.putString("composerProject",composerProject) }
    override fun onPause() { editor?.let { store.prefs.edit().putString("draft",it.text.toString()).apply() }; super.onPause() }
    override fun onResume() { super.onResume(); if ((page == 0 || page == 5) && ::root.isInitialized) render() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) /* Native layouts resize without destroying the editor. */ }
    private fun label(pkg: String): String = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault(pkg)
    private fun serviceEnabled(): Boolean = (getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager).getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name == CommandService::class.java.name }
    private fun render() {
        capture(); root = column().apply { setPadding(dp(12),dp(8),dp(12),dp(8)); setBackgroundColor(Color.rgb(17,24,39)) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val l: Int; val t: Int; val r: Int; val b: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) { val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()); l=bars.left; t=bars.top; r=bars.right; b=bars.bottom } else { l=insets.systemWindowInsetLeft; t=insets.systemWindowInsetTop; r=insets.systemWindowInsetRight; b=insets.systemWindowInsetBottom }
            v.setPadding(dp(12)+l,dp(8)+t,dp(12)+r,dp(8)+b); insets
        }
        root.addView(text("AI Command Center",26f)); val nav = HorizontalScrollView(this); val row = LinearLayout(this)
        listOf("Setup","AI apps","Projects","Commands","Composer","Updates","Integrations").forEachIndexed { i,s -> row.addView(button(if (i == page) "• $s" else s) { page=i; integrationPage=""; render() }) }; nav.addView(row); root.addView(nav)
        val scroll = ScrollView(this).apply { isFillViewport = true }; content = column(); scroll.addView(content); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); setContentView(root); root.requestApplyInsets()
        when (page) { 0 -> setup(); 1 -> apps(); 2 -> projects(); 3 -> commands(); 4 -> composer(); 5 -> updates(); 6 -> integrationSettings() }
    }
    private fun setup() {
        val s=store.load(); content.addView(text(if(serviceEnabled()) "Accessibility service: enabled" else "Accessibility service: disabled",20f))
        content.addView(text("Integration controls appear only in selected foreground apps. Use a command button or an explicitly enabled shortcut to append instructions to the focused editable field. The service can access screen contents; this app does not store or transmit those contents. It never sends a message automatically."))
        content.addView(button("Enable / manage accessibility") { AlertDialog.Builder(this).setTitle("Accessibility access").setMessage("Allow Android accessibility access to detect selected foreground apps and insert commands into the focused non-password input only after your tap or enabled shortcut. You can disable it at any time. Network access is used only for app updates; target-app text is not sent.").setNegativeButton("Cancel",null).setPositiveButton("Continue") { _,_-> store.prefs.edit().putBoolean("consent",true).apply(); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show() })
        content.addView(Switch(this).apply { text="Master enable: AI integrations"; isChecked=s.enabled; setOnCheckedChangeListener { _,checked -> store.change { it.copy(enabled=checked) } } })
        content.addView(text("If Android blocks accessibility for a sideloaded app, open App info and look for Allow restricted settings (if available). Your phone may require additional confirmation."))
        content.addView(button("Open app info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) })
        content.addView(button("Export profiles as JSON") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"ai-command-center.json"),10) })
        content.addView(button("Import profiles (replace existing)") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),11) })
        content.addView(text("${s.apps.size} selected apps · ${s.projects.size} projects · ${s.commands.size} commands"))
    }
    private fun integrationSettings() {
        val methods=linkedMapOf("floating" to "Floating button", "edge" to "Edge swipe", "shortcuts" to "Text shortcuts", "selection" to "Text selection menu", "tile" to "Quick Settings tile", "clipboard" to "Gboard clipboard")
        if(integrationPage !in methods) {
            content.addView(text("Integration methods",22f)); content.addView(text("Keep Gboard as your keyboard. Each method is independently enabled. Select allowed AI apps and enable the master switch in Setup."))
            methods.forEach { (key,name) -> content.addView(button("$name · ${if(integrations.enabled(key)) "On" else "Off"}") { integrationPage=key; render() }) }
            return
        }
        val key=integrationPage
        content.addView(button("← All integration settings") { integrationPage=""; render() })
        content.addView(text(methods.getValue(key),22f))
        content.addView(Switch(this).apply {
            text="Enable ${methods.getValue(key)}"; isChecked=integrations.enabled(key)
            setOnCheckedChangeListener { _,checked ->
                if(key=="selection") packageManager.setComponentEnabledSetting(ComponentName(this@MainActivity,ProcessTextActivity::class.java),if(checked) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP)
                integrations.enable(key,checked); render()
            }
        })
        fun choices(pref:String, fallback:String, values:List<Pair<String,String>>) {
            content.addView(button(values.find { it.first==integrations.setting(pref,fallback) }?.second ?: fallback) {
                AlertDialog.Builder(this).setTitle("Choose setting").setSingleChoiceItems(values.map { it.second }.toTypedArray(), values.indexOfFirst { it.first==integrations.setting(pref,fallback) }) { d,i -> integrations.set(pref,values[i].first); d.dismiss(); render() }.setNegativeButton("Cancel",null).show()
            })
        }
        when(key) {
            "floating" -> { content.addView(text("Drag the AI button vertically to reposition it. Tap to open the command menu. Turning it off does not disable other methods.")); content.addView(button("Reset floating button position") { store.prefs.edit().putFloat("bubble_y",.45f).apply(); integrations.set("floating_reset",System.nanoTime().toString()); notify("Position reset") }) }
            "edge" -> { content.addView(text("Swipe inward from the small translucent handle about a quarter of the way down the screen. It is inset from the system back-gesture area.")); choices("edge_side","right",listOf("right" to "Right side","left" to "Left side")); choices("edge_width","18",listOf("12" to "Narrow (12 dp)","18" to "Normal (18 dp)","28" to "Wide (28 dp)")) }
            "shortcuts" -> { content.addView(text("Type a shortcut at the end of a draft followed by a semicolon, e.g. #all; . Only enabled shortcuts in selected AI apps are expanded. Gboard composition and unsupported fields may prevent expansion.")); content.addView(button("Add text shortcut") { editShortcut(null) }); integrations.shortcuts().forEach { item -> content.addView(button("${item.trigger}; → ${shortcutLabel(item.target)}") { editShortcut(item) }) }; content.addView(button("Restore default shortcuts") { AlertDialog.Builder(this).setMessage("Replace all shortcuts with defaults?").setNegativeButton("Cancel",null).setPositiveButton("Restore") { _,_-> integrations.saveShortcuts(IntegrationRules.defaults); render() }.show() }) }
            "selection" -> content.addView(text("Select editable text in a selected AI app and choose AI Commands in the Android selection menu (possibly under ⋮). The selected text is replaced with the same text plus instructions. Some apps do not support this menu. No accessibility permission is needed for this method."))
            "tile" -> { content.addView(text("Edit Android Quick Settings and add the AI Commands tile. Open a selected AI app, swipe down, tap the tile, then return to that app within 8 seconds. Android 12+ attempts to close the shade automatically.")); choices("tile_action","menu",listOf("menu" to "Open command menu","all" to "Append all applicable commands")) }
            "clipboard" -> clipboardSettings()
        }
    }
    private fun shortcutLabel(target:String): String = if(target.startsWith("command:")) store.load().commands.find { "command:${it.id}"==target }?.title ?: "Deleted command" else target.lowercase().replaceFirstChar { it.uppercase() }
    private fun editShortcut(old:Shortcut?) {
        val list=integrations.shortcuts(); val name=field("#shortcut",old?.trigger.orEmpty())
        val targets=Scope.values().map { it.name }+store.load().commands.map { "command:${it.id}" }
        val target=spinner(targets.map { shortcutLabel(it) },targets.indexOf(old?.target).coerceAtLeast(0))
        val layout=column().apply { setPadding(dp(16),0,dp(16),0); addView(name); addView(text("Commands to insert")); addView(target) }
        val builder=AlertDialog.Builder(this).setTitle("Text shortcut").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Save",null)
        if(old!=null) builder.setNeutralButton("Delete") { _,_-> integrations.saveShortcuts(list.filterNot { it==old }); render() }
        val dialog=builder.create(); dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val trigger=name.text.toString().trim()
            if(!IntegrationRules.validTrigger(trigger) || list.any { it.trigger==trigger && it!=old } || (old==null && list.size>=200)) { name.error="Use a unique #name (letters, digits, _ or -; maximum 32 characters after #)"; return@setOnClickListener }
            integrations.saveShortcuts(list.filterNot { it==old }+Shortcut(trigger,targets[target.selectedItemPosition])); dialog.dismiss(); render()
        } }; dialog.show()
    }
    private fun clipboardSettings() {
        content.addView(text("Prepare a command block, copy it, then paste using Gboard's clipboard. No keyboard replacement or background clipboard reading."))
        val s=store.load(); val packages=s.apps.sortedBy { label(it) }
        if(packages.isEmpty()) { content.addView(text("Select an AI app first.")); return }
        val app=spinner(packages.map { label(it) },packages.indexOf(integrations.setting("clipboard_app","")).coerceAtLeast(0)); content.addView(text("Target app")); content.addView(app)
        val targets=Scope.values().map { it.name }+s.commands.map { "command:${it.id}" }
        val scope=spinner(targets.map { shortcutLabel(it) },targets.indexOf(integrations.setting("clipboard_scope","ALL")).coerceAtLeast(0)); content.addView(text("Command scope (uses target app's active project)")); content.addView(scope)
        fun prepare(open:Boolean) {
            val latest=store.load(); val pkg=packages[app.selectedItemPosition]; val target=targets[scope.selectedItemPosition]
            if(!latest.enabled || !integrations.enabled("clipboard") || pkg !in latest.apps) { notify("Enable clipboard integration and the master switch first"); return }
            val block=integrations.block(latest,pkg,target); if(block.isBlank()) { notify("No matching commands"); return }
            integrations.set("clipboard_app",pkg); integrations.set("clipboard_scope",target); copy("\n\n$block"); notify("Copied. Paste at the end of your draft with Gboard.")
            if(open) packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } ?: notify("Could not open target app")
        }
        content.addView(button("Copy commands") { prepare(false) }); content.addView(button("Copy and open AI app") { prepare(true) })
    }
    private fun updateTask(task: () -> Unit) {
        if (updateBusy) return
        updateBusy = true; render()
        Thread {
            try { task() } catch (e: Exception) { runOnUiThread { if (!isDestroyed) notify(e.message ?: "Update failed") } }
            finally { runOnUiThread { updateBusy = false; if (!isDestroyed && page == 5) render() } }
        }.start()
    }
    private fun updates() {
        content.addView(text("Installed version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 20f))
        content.addView(text("Updates use signed APKs from public GitHub Releases. Wi-Fi recommends full APKs; mobile data recommends smaller compatible partial updates. Your profiles and drafts are kept when the package and signing key match. Android asks for installation confirmation."))
        if (!updater.configured) { content.addView(text("No update repository configured in this build.")); return }
        val status = runCatching { updater.status() }.getOrElse { Updater.Status(it.message ?: "Unable to read download status") }
        content.addView(text(status.message))
        content.addView(button(if (updateBusy) "Working…" else "Check for updates") {
            updateTask {
                val release = updater.check()
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    if (release == null) notify("You have the latest version.") else {
                        val delta = release.delta
                        val dialog = AlertDialog.Builder(this).setTitle("Update ${release.tag}")
                        val fullLabel = "Full APK (${(release.full.size + 1023) / 1024} KB)"
                        if (delta != null) {
                            val patchLabel = "Partial (${(delta.size + 1023) / 1024} KB, ${(100 - delta.size * 100 / release.full.size)}% less data)"
                            val options = if (release.preferDelta) arrayOf("$patchLabel — recommended", fullLabel) else arrayOf("$fullLabel — recommended", patchLabel)
                            var choice = 0
                            dialog.setSingleChoiceItems(options, 0) { _, index -> choice = index }
                                .setPositiveButton("Download") { _, _ -> updateTask { updater.start(release, if (release.preferDelta) choice == 0 else choice == 1) } }
                        } else dialog.setMessage("No smaller patch matches your installed APK. Download $fullLabel?")
                            .setPositiveButton("Download full APK") { _, _ -> updateTask { updater.start(release, false) } }
                        dialog.setNegativeButton("Cancel", null).show()
                    }
                }
            }
        }.apply { isEnabled = !updateBusy && updater.id < 0 })
        if (updater.id >= 0) {
            content.addView(button("Refresh download status") { render() }.apply { isEnabled = !updateBusy })
            content.addView(button("Cancel / clear download") { runCatching { updater.cancel() }.onFailure { notify(it.message ?: "Cannot cancel") }; render() }.apply { isEnabled = !updateBusy })
        }
        if (status.ready) content.addView(button("Verify and install update") {
            if (!packageManager.canRequestPackageInstalls()) { runCatching { updater.requestInstallPermission(this) }.onFailure { notify(it.message ?: "Installation settings unavailable") }; return@button }
            updateTask {
                val apk = updater.verify()
                runOnUiThread { if (!isDestroyed && !isFinishing) runCatching { updater.install(this, apk) }.onFailure { notify(it.message ?: "Installer unavailable") } }
            }
        }.apply { isEnabled = !updateBusy })
        content.addView(button("Open GitHub Releases in browser") { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updater.releasesUrl))) }.onFailure { notify("No browser available") } })
    }
    private fun apps() {
        content.addView(text("Select apps to allow AI Command Center integrations in. Only choose AI apps you want to control."))
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed=packageManager.queryIntentActivities(intent,0).map { it.activityInfo.packageName }.distinct().filter { it!=packageName }.sortedBy { label(it).lowercase() }
        val selected=store.load().apps
        (installed + selected).distinct().forEach { pkg -> content.addView(CheckBox(this).apply { text="${label(pkg)}\n$pkg"; isChecked=pkg in selected; setOnCheckedChangeListener { _,checked ->
            try { store.change { it.copy(apps=if(checked) it.apps+pkg else it.apps-pkg) } } catch(e:IllegalArgumentException) { notify(e.message ?: "Profile limit reached"); render() }
        } }) }
        content.addView(button("Assign active GitHub project to an AI app") { selectAppProject() })
        content.addView(text("Unchecking an app disables its integrations but keeps all its commands and project settings."))
    }
    private fun selectAppProject() {
        val s=store.load(); val packages=s.apps.sortedBy { label(it) }
        if(packages.isEmpty()) { notify("Select an AI app first"); return }
        AlertDialog.Builder(this).setTitle("Choose AI app").setItems(packages.map { label(it) }.toTypedArray()) { _,i ->
            val pkg=packages[i]; val latest=store.load(); val projects=listOf(Project("","No project"))+latest.projects
            AlertDialog.Builder(this).setTitle("Active project for ${label(pkg)}").setSingleChoiceItems(projects.map { it.repo }.toTypedArray(),projects.indexOfFirst { it.id==latest.active[pkg].orEmpty() }) { d,j ->
                val id=projects[j].id
                store.change { it.copy(active=if(id.isEmpty()) it.active-pkg else it.active+(pkg to id)) }; d.dismiss(); notify("Active project saved")
            }.setNegativeButton("Cancel",null).show()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun projects() {
        content.addView(text("GitHub projects use owner/repository identifiers. No GitHub token is needed; project selection determines which instructions appear.")); content.addView(button("Add GitHub project") { editProject(null) })
        store.load().projects.forEach { p -> content.addView(button(p.repo) { editProject(p) }) }
    }
    private fun editProject(p: Project?) {
        val input=field("owner/repository",p?.repo.orEmpty()); val d=AlertDialog.Builder(this).setTitle(if(p==null) "Add project" else "Edit project").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null)
        if(p!=null) d.setNeutralButton("Delete") { _,_-> AlertDialog.Builder(this).setMessage("Delete this project and all its commands?").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_-> store.change { it.copy(projects=it.projects.filterNot { x->x.id==p.id },commands=it.commands.filterNot { c->c.project==p.id },active=it.active.filterValues { v->v!=p.id }) }; render() }.show() }
        val dialog=d.create(); dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { val repo=input.text.toString().trim().removePrefix("https://github.com/").trimEnd('/'); if(!CommandEngine.validRepo(repo)) { input.error="Enter owner/repository"; return@setOnClickListener }; val s=store.load(); if(s.projects.any { it.repo.equals(repo,true) && it.id!=p?.id }) { input.error="Project already exists"; return@setOnClickListener }; val item=Project(p?.id ?: store.id(),repo); try { store.save(s.copy(projects=if(p==null) s.projects+item else s.projects.map { if(it.id==p.id) item else it })) } catch(e:IllegalArgumentException) { input.error=e.message ?: "Profile storage limit reached"; return@setOnClickListener }; dialog.dismiss(); render() } }; dialog.show()
    }
    private fun scopeLabel(c: Command,s: Store.State): String = listOfNotNull(c.app.takeIf { it.isNotEmpty() }?.let { label(it) },s.projects.find { it.id==c.project }?.repo).joinToString(" / ").ifEmpty { "Universal" }
    private fun commands() {
        val s=store.load(); content.addView(text("Universal commands apply everywhere. Choose an app, a project, or both to restrict a command. Use {{repo}} and {{repo_url}} in command bodies.")); content.addView(button("Add command") { editCommand(null) })
        s.commands.forEach { c -> content.addView(button("${c.title} · ${scopeLabel(c,s)}") { editCommand(c) }); content.addView(text(c.body.take(180),14f)) }
    }
    private fun spinner(labels: List<String>, initial: Int): Spinner = Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels); setSelection(initial.coerceIn(0,labels.lastIndex)) }
    private fun editCommand(c: Command?) {
        val s=store.load(); val packages=listOf("")+(s.apps+s.commands.map { it.app }.filter { it.isNotEmpty() }).sortedBy { label(it) }; val projects=listOf("")+s.projects.map { it.id }; val appSpinner=spinner(packages.map { if(it.isEmpty()) "All AI apps" else label(it) },packages.indexOf(c?.app.orEmpty()).coerceAtLeast(0)); val projectSpinner=spinner(projects.map { id->s.projects.find { it.id==id }?.repo ?: "No project restriction" },projects.indexOf(c?.project.orEmpty()).coerceAtLeast(0)); val name=field("Command button title",c?.title.orEmpty()); val body=field("Full instructions",c?.body.orEmpty(),true)
        val layout=column().apply { setPadding(dp(16),0,dp(16),0); addView(name); addView(text("App")); addView(appSpinner); addView(text("GitHub project")); addView(projectSpinner); addView(body) }; val scroll=ScrollView(this).apply { addView(layout) }
        val builder=AlertDialog.Builder(this).setTitle(if(c==null) "New command" else "Edit command").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null)
        if(c!=null) builder.setNeutralButton("Delete") { _,_-> store.change { it.copy(commands=it.commands.filterNot { x->x.id==c.id }) }; render() }
        val dialog=builder.create(); dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { val title=name.text.toString().trim(); val instructions=body.text.toString().trim(); if(title.isEmpty()||title.length>120) { name.error="1–120 characters required"; return@setOnClickListener }; if(instructions.isEmpty()||instructions.length>100000) { body.error="1–100,000 characters required"; return@setOnClickListener }; val item=Command(c?.id?:store.id(),title,instructions,packages[appSpinner.selectedItemPosition],projects[projectSpinner.selectedItemPosition]); try { store.change { it.copy(commands=if(c==null) it.commands+item else it.commands.map { x->if(x.id==c.id) item else x }) } } catch(e:IllegalArgumentException) { body.error=e.message ?: "Profile storage limit reached"; return@setOnClickListener }; dialog.dismiss(); render() } }; dialog.show()
    }
    private fun composer() {
        val s=store.load(); val packages=listOf("")+s.apps.sortedBy { label(it) }; val projects=listOf("")+s.projects.map { it.id }; val appSpinner=spinner(packages.map { if(it.isEmpty()) "Universal only / any app" else label(it) },packages.indexOf(composerApp).coerceAtLeast(0)); val projectSpinner=spinner(projects.map { id->s.projects.find { it.id==id }?.repo ?: "No project" },projects.indexOf(composerProject).coerceAtLeast(0)); content.addView(text("Target app")); content.addView(appSpinner); content.addView(text("GitHub project")); content.addView(projectSpinner); val input=field("Write your message",draft,true); editor=input; content.addView(input)
        fun complete(): String { composerApp=packages[appSpinner.selectedItemPosition]; composerProject=projects[projectSpinner.selectedItemPosition]; val block=CommandEngine.render(CommandEngine.matching(s.commands,composerApp,composerProject,Scope.ALL),s.projects.find { it.id==composerProject }?.repo); return CommandEngine.append(input.text.toString(),block) }
        content.addView(button("Preview complete prompt") { AlertDialog.Builder(this).setTitle("Complete prompt").setMessage(complete()).setPositiveButton("Close",null).show() }); content.addView(button("Copy complete prompt") { copy(complete()); notify("Copied. Paste in your AI conversation.") }); content.addView(button("Share complete prompt") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,complete()),"Share prompt")) }); content.addView(button("Copy and open selected AI app") { val value=complete(); val launch=if(composerApp.isEmpty()) null else packageManager.getLaunchIntentForPackage(composerApp); if(launch==null) { notify("Select an installed AI app first"); return@button }; copy(value); startActivity(launch) })
        val listener=object: AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent:AdapterView<*>?) {} ; override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { composerApp=packages[appSpinner.selectedItemPosition.coerceAtLeast(0)]; composerProject=projects[projectSpinner.selectedItemPosition.coerceAtLeast(0)] } }; appSpinner.onItemSelectedListener=listener; projectSpinner.onItemSelectedListener=listener
    }
    private fun copy(s: String) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AI prompt",s)) }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(resultCode!=RESULT_OK) return; val uri=data?.data?:return; try { when(requestCode) { 10 -> { contentResolver.openOutputStream(uri,"wt")?.use { it.write(store.encode(store.load()).toByteArray(Charsets.UTF_8)) } ?: error("Cannot open destination"); notify("Profiles exported") }; 11 -> { val raw=contentResolver.openInputStream(uri)?.use { stream -> val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); while(true) { val n=stream.read(buffer); if(n<0) break; require(output.size()+n<=2_000_000) { "Backup too large" }; output.write(buffer,0,n) }; output.toString("UTF-8") } ?: error("Cannot read backup"); val imported=store.decode(raw); AlertDialog.Builder(this).setTitle("Replace current profiles?").setMessage("${imported.apps.size} apps, ${imported.projects.size} projects, ${imported.commands.size} commands. Review selected apps after import.").setNegativeButton("Cancel",null).setPositiveButton("Replace") { _,_-> store.save(imported.copy(enabled=false, apps=imported.apps-packageName, commands=imported.commands.filterNot { it.app==packageName }, active=imported.active-packageName)); render(); notify("Imported. Enable integrations in Setup when ready.") }.show() } } } catch(e:Exception) { notify(e.message ?: "File operation failed") } }
}
