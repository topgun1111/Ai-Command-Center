package com.aicommandcenter.app

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** The caller must be a selected app. No access to unrelated selections or passwords. */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store=Store(this); val s=store.load(); val integration=Integrations(this)
        val pkg=callingPackage.orEmpty()
        val selected=intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if(intent.action!=Intent.ACTION_PROCESS_TEXT || !s.enabled || !integration.enabled("selection") || pkg !in s.apps || pkg==packageName || selected.isNullOrEmpty() || selected.length>200000 || intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true)) {
            Toast.makeText(this,"AI Commands requires editable text in a selected AI app.",Toast.LENGTH_LONG).show(); finish(); return
        }
        val project=s.active[pkg].orEmpty(); val repo=s.projects.find { it.id==project }?.repo
        val options=mutableListOf<Pair<String,String>>()
        for(scope in Scope.values()) {
            val block=integration.block(s,pkg,scope.name)
            if(block.isNotBlank()) options.add(scope.name.lowercase().replaceFirstChar { it.uppercase() }+" commands" to block)
        }
        CommandEngine.matching(s.commands,pkg,project,Scope.ALL).forEach { options.add(it.title to CommandEngine.render(listOf(it),repo)) }
        if(options.isEmpty()) { Toast.makeText(this,"No matching commands",Toast.LENGTH_SHORT).show(); finish(); return }
        AlertDialog.Builder(this).setTitle("Append AI commands").setItems(options.map { it.first }.toTypedArray()) { _,i ->
            val latest=store.load()
            val result=CommandEngine.append(selected,options[i].second)
            if(latest.enabled && integration.enabled("selection") && pkg in latest.apps && result.length<=200000) setResult(RESULT_OK,Intent().putExtra(Intent.EXTRA_PROCESS_TEXT,result))
            finish()
        }.setNegativeButton("Cancel") { _,_->finish() }.setOnCancelListener { finish() }.show()
    }
}

class CommandTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { state=if(Integrations(this@CommandTileService).enabled("tile") && Store(this@CommandTileService).load().enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }
    override fun onClick() {
        super.onClick()
        if(isLocked) { Toast.makeText(this,"Unlock your phone first",Toast.LENGTH_SHORT).show(); return }
        val accepted=CommandService.connected?.requestTile()==true
        Toast.makeText(this,if(accepted) "Return to the selected AI app to apply commands" else "Enable Quick Settings and accessibility, then open a selected AI app first",Toast.LENGTH_SHORT).show()
    }
}
