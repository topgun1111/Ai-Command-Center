package com.aicommandcenter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import android.app.KeyguardManager
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.*

class CommandService : AccessibilityService() {
    private lateinit var store: Store
    private lateinit var wm: WindowManager
    private lateinit var integrations: Integrations
    private var edge: View? = null
    private var shadeTarget = ""
    private var tileTarget = ""
    private var tileUntil = 0L
    private var shortcutWindow = -1
    private var shortcutPackage = ""
    private var generatedHash: Int? = null
    private var generatedWindow = -1
    private val shortcutCheck = Runnable { expandShortcut() }
    private var insertionPending = false
    private var editorDiagnostic = ""
    // Selection metadata only; never retain editor text or observe keystrokes here.
    @RequiresApi(33)
    private class EditorBridge(service: AccessibilityService) : InputMethod(service) {
        var selectionStart = -1; private set
        var selectionEnd = -1; private set
        var compositionClear = false; private set
        var session = 0L; private set
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            session++
            selectionStart=attribute.initialSelStart; selectionEnd=attribute.initialSelEnd
            compositionClear=false
        }
        override fun onUpdateSelection(oldSelStart:Int,oldSelEnd:Int,newSelStart:Int,newSelEnd:Int,candidatesStart:Int,candidatesEnd:Int) {
            selectionStart=newSelStart; selectionEnd=newSelEnd
            compositionClear=candidatesStart<0 && candidatesEnd<0
        }
        override fun onFinishInput() { session++; selectionStart=-1; selectionEnd=-1; compositionClear=false }
    }
    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod = EditorBridge(this)
    companion object { internal var connected: CommandService? = null }
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var panel: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var current = ""
    private var ready = false
    private var yFraction = .45f
    private val refresh = Runnable { sync() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "state" || key == "consent" || key?.startsWith("integration_")==true) {
            handler.removeCallbacks(shortcutCheck); tileTarget=""
            if(key=="integration_floating_reset") yFraction=store.prefs.getFloat("bubble_y",.45f).coerceIn(0f,1f)
            configureEvents(); hide(); schedule()
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if(intent?.action == Intent.ACTION_SCREEN_OFF) { current=""; shadeTarget=""; tileTarget=""; handler.removeCallbacks(shortcutCheck); hide() } else schedule() }
    }
    private var registered = false
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun background(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat(); setStroke(dp(1),Color.rgb(93,125,171)) }
    override fun onServiceConnected() {
        super.onServiceConnected(); store=Store(this); integrations=Integrations(this); connected=this; wm=getSystemService(WINDOW_SERVICE) as WindowManager; ready=true; yFraction=store.prefs.getFloat("bubble_y",.45f).coerceIn(0f,1f)
        store.prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val filter=IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(screenReceiver,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenReceiver,filter)
        registered=true; configureEvents(); schedule()
    }
    private fun schedule() { if(ready) { handler.removeCallbacks(refresh); handler.post(refresh) } }
    private fun configureEvents() {
        if(!ready) return
        val info=serviceInfo ?: return
        info.eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED
        if(integrations.enabled("shortcuts") && store.load().enabled && store.prefs.getBoolean("consent",false)) info.eventTypes=info.eventTypes or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        if(Build.VERSION.SDK_INT>=33) {
            val enabled=store.load().enabled && store.prefs.getBoolean("consent",false)
            info.flags=if(enabled) info.flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR else info.flags and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR.inv()
        }
        info.flags=info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo=info
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(!ready) return
        schedule()
        if(event==null) return
        if(event.eventType==AccessibilityEvent.TYPE_VIEW_FOCUSED || event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) handler.removeCallbacks(shortcutCheck)
        if(event.eventType==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && integrations.enabled("shortcuts") && store.load().enabled && store.prefs.getBoolean("consent",false) && event.packageName?.toString() in store.load().apps && event.packageName?.toString()==current && !event.isPassword) {
            val source=event.source ?: return
            if(!source.isFocused || !source.isEditable || source.isPassword || !source.isEnabled || !source.isVisibleToUser) return
            shortcutWindow=event.windowId; shortcutPackage=event.packageName.toString()
            handler.removeCallbacks(shortcutCheck); handler.postDelayed(shortcutCheck,450)
        }
    }
    override fun onInterrupt() { current=""; shadeTarget=""; tileTarget=""; handler.removeCallbacks(shortcutCheck); hide() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); hide(); schedule() }
    override fun onDestroy() { if(connected===this) connected=null; ready=false; handler.removeCallbacksAndMessages(null); if(::store.isInitialized) store.prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener); if(registered) unregisterReceiver(screenReceiver); hide(); super.onDestroy() }
    // Only the active application window is eligible. Never reuse a previously focused field.
    private fun activeRoot(): AccessibilityNodeInfo? {
        if(!ready || (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked || !(getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive) return null
        val root=rootInActiveWindow ?: return null
        val w=root.window ?: return null
        if(w.type != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive) return null
        return root
    }
    private fun sync() {
        if(!ready) return
        val s=store.load(); val pkg=runCatching { activeRoot()?.packageName?.toString().orEmpty() }.getOrDefault("")
        val rawPkg=runCatching { rootInActiveWindow?.packageName?.toString().orEmpty() }.getOrDefault("")
        val permitted=s.enabled && store.prefs.getBoolean("consent",false) && pkg.isNotEmpty() && pkg!=packageName && pkg in s.apps
        if(!permitted) {
            handler.removeCallbacks(shortcutCheck)
            if(rawPkg.isNotEmpty() && rawPkg!="com.android.systemui") { shadeTarget=""; tileTarget="" }
            current=""; hide(); return
        }
        if(pkg!=current) { handler.removeCallbacks(shortcutCheck); hide(); current=pkg }
        shadeTarget=pkg
        if(tileTarget.isNotEmpty()) {
            val apply=tileTarget==pkg && android.os.SystemClock.elapsedRealtime()<tileUntil && integrations.enabled("tile")
            tileTarget=""
            if(apply) {
                if(integrations.setting("tile_action","menu")=="all") insert(integrations.block(s,pkg,"ALL")) else openPanel()
                return
            }
        }
        if(panel==null && !insertionPending) {
            if(bubble==null && integrations.enabled("floating")) showBubble()
            if(edge==null && integrations.enabled("edge")) showEdge()
        }
    }
    internal fun requestTile(): Boolean {
        if(!ready || (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked || !integrations.enabled("tile") || !store.load().enabled || !store.prefs.getBoolean("consent",false)) return false
        val pkg=shadeTarget
        val raw=runCatching { rootInActiveWindow?.packageName?.toString().orEmpty() }.getOrDefault("")
        if(pkg !in store.load().apps || (raw.isNotEmpty() && raw!=pkg && raw!="com.android.systemui")) return false
        tileTarget=pkg; tileUntil=android.os.SystemClock.elapsedRealtime()+8000
        if(android.os.Build.VERSION.SDK_INT>=31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        schedule(); return true
    }
    private fun showEdge() {
        val left=integrations.setting("edge_side","right")=="left"
        val width=integrations.setting("edge_width","18").toIntOrNull()?.coerceIn(12,32) ?: 18
        val view=View(this).apply { contentDescription="Swipe inward for AI commands"; setBackgroundColor(0x223B82F6) }
        val lp=layout(dp(width),dp(100)).apply { gravity=Gravity.TOP or if(left) Gravity.LEFT else Gravity.RIGHT; x=dp(18); y=(bounds().height()/4).coerceAtLeast(dp(32)) }
        var start=0f; var fired=false
        view.setOnTouchListener { _,e ->
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { start=e.rawX; fired=false; true }
                MotionEvent.ACTION_MOVE -> { if(!fired && (if(left) e.rawX-start else start-e.rawX)>dp(36)) { fired=true; if(stillActive()) openPanel() }; true }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        try { wm.addView(view,lp); edge=view } catch(_:Exception) { edge=null }
    }
    private fun expandShortcut() {
        if(!ready || !integrations.enabled("shortcuts") || !stillActive() || current!=shortcutPackage) return
        val root=activeRoot() ?: return
        if(root.windowId!=shortcutWindow) return
        val node=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if(!node.refresh() || node.packageName?.toString()!=current || node.windowId!=root.windowId || !node.isFocused || !node.isEditable || !node.isEnabled || !node.isVisibleToUser || node.isPassword || node.isShowingHintText || node.actionList.none { it.id==AccessibilityNodeInfo.ACTION_SET_TEXT }) return
        val draft=node.text?.toString() ?: return
        if(generatedWindow==node.windowId && generatedHash==draft.hashCode()) return
        generatedHash=null
        if(draft.length>200000 || node.textSelectionStart!=draft.length || node.textSelectionEnd!=draft.length) return
        val shortcut=IntegrationRules.find(draft,integrations.shortcuts()) ?: return
        val block=integrations.block(store.load(),current,shortcut.target)
        val result=IntegrationRules.expand(draft,shortcut,block) ?: return
        if(result==draft || result.length>200000 || (node.maxTextLength>0 && result.length>node.maxTextLength)) return
        if(!stillActive() || !node.refresh() || !node.isFocused || node.isPassword || node.text?.toString()!=draft || node.textSelectionEnd!=draft.length || node.textSelectionStart!=draft.length) return
        generatedHash=result.hashCode(); generatedWindow=node.windowId
        val ok=runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,result) }) }.getOrDefault(false)
        if(ok && stillActive() && node.refresh() && node.isFocused && node.text?.toString()==result) runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,result.length); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,result.length) }) }
    }
    private fun bounds(): Rect = if(android.os.Build.VERSION.SDK_INT>=30) wm.currentWindowMetrics.bounds else Rect(0,0,resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)
    private fun maxY() = (bounds().height()-dp(140)).coerceAtLeast(0)
    private fun layout(width:Int,height:Int) = WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.RIGHT; x=dp(8); y=dp(32)+(maxY()*yFraction).toInt() }
    private fun showBubble() {
        val view=TextView(this).apply { text="AI"; textSize=17f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; background=background(Color.rgb(27,62,119),30); contentDescription="AI Command Center: open commands"; elevation=dp(6).toFloat(); isClickable=true }
        val lp=layout(dp(54),dp(54)); params=lp
        view.setOnClickListener { if(stillActive()) openPanel() else sync() }
        var downY=0f; var downX=0f; var initialY=0; var moved=false
        view.setOnTouchListener { v,e -> when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY=e.rawY; downX=e.rawX; initialY=lp.y; moved=false; true }
            MotionEvent.ACTION_MOVE -> { if(kotlin.math.abs(e.rawY-downY)>dp(7) || kotlin.math.abs(e.rawX-downX)>dp(7)) moved=true; if(moved) { lp.y=(initialY+(e.rawY-downY).toInt()).coerceIn(dp(32),dp(32)+maxY()); runCatching { wm.updateViewLayout(v,lp) } }; true }
            MotionEvent.ACTION_UP -> { if(moved) { yFraction=if(maxY()==0) 0f else ((lp.y-dp(32)).toFloat()/maxY()).coerceIn(0f,1f); store.prefs.edit().putFloat("bubble_y",yFraction).apply() } else v.performClick(); true }
            MotionEvent.ACTION_CANCEL -> true
            else -> false
        } }
        try { wm.addView(view,lp); bubble=view } catch(_:Exception) { bubble=null }
    }
    private fun stillActive(): Boolean { val s=store.load(); return ready && s.enabled && store.prefs.getBoolean("consent",false) && current in s.apps && current!=packageName && runCatching { activeRoot()?.packageName?.toString()==current }.getOrDefault(false) }
    private fun remove(v: View?) { if(v!=null && ::wm.isInitialized) runCatching { wm.removeViewImmediate(v) } }
    private fun closePanel() { remove(panel); panel=null }
    private fun hide() { closePanel(); remove(bubble); bubble=null; remove(edge); edge=null; params=null }
    private fun toast(s:String) { Toast.makeText(this,s,Toast.LENGTH_LONG).show() }
    private fun appLabel(pkg:String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault(pkg)
    private fun openPanel() {
        if(!stillActive()) { sync(); return }; hide()
        val s=store.load(); val project=s.active[current].orEmpty(); val repo=s.projects.find { it.id==project }?.repo
        val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(10),dp(8)); background=background(Color.rgb(17,24,39),16) }
        fun title(t:String) { col.addView(TextView(this).apply { text=t; textSize=15f; setTextColor(Color.WHITE); setPadding(dp(4),dp(6),dp(4),dp(6)) }) }
        fun button(t:String,action:()->Unit) { col.addView(Button(this).apply { text=t; isAllCaps=false; minHeight=dp(44); setOnClickListener { action() } },LinearLayout.LayoutParams(-1,-2)) }
        title(appLabel(current)); button("Close ×") { closePanel(); sync() }; title("Project: ${repo ?: "None"}")
        button("Choose GitHub project") { chooseProject() }
        val groups=listOf(Scope.UNIVERSAL to "Universal commands",Scope.APP to "App-specific commands",Scope.PROJECT to "Project commands",Scope.ALL to "Append all matching commands")
        for((scope,name) in groups) { val commands=CommandEngine.matching(s.commands,current,project,scope); if(commands.isNotEmpty()) button("$name (${commands.size})") { insert(CommandEngine.render(commands,repo)) } }
        val all=CommandEngine.matching(s.commands,current,project,Scope.ALL)
        if(all.isEmpty()) title("No matching commands. Open AI Command Center to create some.") else { title("Individual commands"); all.forEach { c -> button(c.title) { insert(CommandEngine.render(listOf(c),repo)) } } }
        button("Open command settings") { if(stillActive()) { hide(); startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        displayPanel(col)
    }
    private fun displayPanel(col:LinearLayout) {
        val scroll=ScrollView(this).apply { addView(col); isFillViewport=false; background=background(Color.rgb(17,24,39),16) }
        val size=bounds(); val width=(size.width()-dp(24)).coerceAtLeast(1).coerceAtMost(dp(330)); val maxHeight=(size.height()-dp(100)).coerceAtLeast(1).coerceAtMost(dp(560))
        col.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val height=col.measuredHeight.coerceIn(1,maxHeight); val lp=layout(width,height).apply { y=dp(32) }
        try { wm.addView(scroll,lp); panel=scroll } catch(_:Exception) { panel=null; sync() }
    }
    private fun chooseProject() {
        if(!stillActive()) { sync(); return }; closePanel(); val pkg=current
        val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(10),dp(8)); background=background(Color.rgb(17,24,39),16) }
        fun button(t:String,action:()->Unit) { col.addView(Button(this).apply { text=t; isAllCaps=false; setOnClickListener { action() } }) }
        button("Back") { openPanel() }
        val projects=listOf(Project("","No project"))+store.load().projects
        projects.forEach { p -> button(p.repo) { if(stillActive() && current==pkg) { store.change { it.copy(active=if(p.id.isEmpty()) it.active-pkg else it.active+(pkg to p.id)) }; openPanel() } else sync() } }
        displayPanel(col)
    }
    private fun eligible(node:AccessibilityNodeInfo,root:AccessibilityNodeInfo,pkg:String):Boolean =
        node.packageName?.toString()==pkg && node.windowId==root.windowId && node.isFocused &&
            node.isEditable && node.isEnabled && node.isVisibleToUser && !node.isPassword

    private fun focusedEditor(root:AccessibilityNodeInfo,pkg:String):AccessibilityNodeInfo? {
        val local=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if(local!=null && eligible(local,root,pkg)) return local
        val global=findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if(global!=null && eligible(global,root,pkg)) return global
        // Some virtual view hierarchies do not implement findFocus correctly.
        // Inspect only a bounded active tree and accept a uniquely input-focused editor.
        val pending=java.util.ArrayDeque<AccessibilityNodeInfo>(); pending.add(root)
        var found:AccessibilityNodeInfo?=null; var count=0
        while(pending.isNotEmpty() && count++<400) {
            val item=pending.removeFirst()
            if(eligible(item,root,pkg)) { if(found!=null && found!=item)return null; found=item }
            for(i in 0 until item.childCount.coerceAtMost(100)) { if(pending.size>=400)break; item.getChild(i)?.let { pending.add(it) } }
        }
        return found.takeIf { pending.isEmpty() }
    }
    private fun insert(block:String) {
        if(block.isBlank()) { toast("No instructions to insert"); return }
        if(insertionPending) return
        if(!stillActive()) { sync(); return }
        val pkg=current
        val window=activeRoot()?.windowId ?: return
        // Let the non-focusable overlay disappear before resolving the editor again.
        hide(); insertionPending=true
        handler.postDelayed({
            insertionPending=false
            if(!stillActive() || current!=pkg || activeRoot()?.windowId!=window) { sync(); return@postDelayed }
            runCatching { insertFocused(block,pkg,window) }.onFailure {
                fallback(block,"Android could not confirm the editor. Review your draft before retrying.")
            }
        },150)
    }
    private fun insertFocused(block:String,pkg:String,window:Int) {
        val root=activeRoot() ?: run { sync(); return }
        if(root.windowId!=window || root.packageName?.toString()!=pkg) { sync(); return }
        editorDiagnostic=""
        val node=focusedEditor(root,pkg)
        if(node==null || !node.refresh() || !eligible(node,root,pkg) ||
            node.actionList.none { it.id==AccessibilityNodeInfo.ACTION_SET_TEXT } ||
            (!node.isShowingHintText && node.text==null)) {
            val nodeStatus=if(node==null)"No input-focused accessibility node" else if(node.actionList.none { it.id==AccessibilityNodeInfo.ACTION_SET_TEXT })"Focused node has no SET_TEXT action" else "Focused node failed validation or has no readable text"
            if(Build.VERSION.SDK_INT>=33 && insertWithEditor(block,pkg,window)) return
            fallback(block,"No compatible focused editor. Tap the end of your message and finish the current word.\n\nDiagnostic: $nodeStatus; ${editorDiagnostic.ifEmpty { "editor bridge unavailable" }}")
            return
        }
        val draft=if(node.isShowingHintText) "" else node.text?.toString() ?: return
        val result=CommandEngine.append(draft,block)
        if(result==draft) { toast("Commands already appended"); sync(); return }
        val max=node.maxTextLength
        if(result.length>200000 || (max>0 && result.length>max)) { fallback(block,"The resulting message is too long for this editor."); return }
        if(!stillActive() || current!=pkg || activeRoot()?.windowId!=window || !node.refresh() ||
            !eligible(node,root,pkg) || (if(node.isShowingHintText) "" else node.text?.toString())!=draft) {
            fallback(block,"The focused message changed. Try again."); return
        }
        val args=Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,result) }
        generatedHash=result.hashCode(); generatedWindow=node.windowId
        val ok=runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args) }.getOrDefault(false)
        if(!ok) { fallback(block,"The editor rejected direct text replacement. Review the draft before retrying or copying."); return }
        if(stillActive() && node.refresh() && eligible(node,root,pkg) && node.text?.toString()==result) {
            val selection=Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,result.length); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,result.length) }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection) }
            toast("Commands appended. Review before sending.")
        } else toast("Insert requested. Check your draft before sending or retrying.")
        sync()
    }
    private fun rejectEditor(reason:String):Boolean { editorDiagnostic=reason; return false }
    @RequiresApi(33)
    private fun insertWithEditor(block:String,pkg:String,window:Int):Boolean {
        val bridge=inputMethod as? EditorBridge ?: return rejectEditor("bridge not created; toggle accessibility service off/on")
        val info=bridge.currentInputEditorInfo ?: return rejectEditor("no active EditorInfo")
        if(!bridge.currentInputStarted || info.packageName!=pkg) return rejectEditor("editor session not active in selected app")
        val type=info.inputType
        val variation=type and InputType.TYPE_MASK_VARIATION
        val action=info.imeOptions and EditorInfo.IME_MASK_ACTION
        // NO_PERSONALIZED_LEARNING is a learning/privacy preference, not a password
        // classification. Never retain editor text; still exclude sensitive input types.
        if((type and InputType.TYPE_MASK_CLASS)!=InputType.TYPE_CLASS_TEXT ||
            variation !in listOf(InputType.TYPE_TEXT_VARIATION_NORMAL,InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT))
            return rejectEditor("unsupported or sensitive input type 0x"+type.toString(16))
        if((type and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE))==0 &&
            action !in listOf(EditorInfo.IME_ACTION_UNSPECIFIED,EditorInfo.IME_ACTION_NONE,EditorInfo.IME_ACTION_SEND,EditorInfo.IME_ACTION_DONE))
            return rejectEditor("not a message editor (action $action)")
        val connection=bridge.currentInputConnection ?: return rejectEditor("no input connection")
        val session=bridge.session
        val surrounding=connection.getSurroundingText(200001,1,0) ?: return rejectEditor("editor did not provide surrounding text")
        val draft=surrounding.text.toString()
        // Unknown offsets can be verified against the absolute input selection.
        // An empty, unselected editor is also safe if Android supplies no selection event yet.
        fun completeAtEnd(value:android.view.inputmethod.SurroundingText):Boolean {
            val length=value.text.length
            val absolute=bridge.selectionStart==length && bridge.selectionEnd==length
            return length<=200000 && value.selectionStart==length && value.selectionEnd==length &&
                (value.offset==0 || (value.offset==-1 && absolute)) &&
                (absolute || (length==0 && bridge.selectionStart<=0 && bridge.selectionEnd<=0))
        }
        if(!completeAtEnd(surrounding)) return rejectEditor("cursor not at verified end or draft incomplete")
        if(!bridge.compositionClear && draft.isNotEmpty()) return rejectEditor("Gboard composition active or not yet confirmed; press space then retry")
        val result=CommandEngine.append(draft,block)
        if(result==draft) { toast("Commands already appended"); sync(); return true }
        if(result.length>200000) return rejectEditor("message exceeds length limit")
        if(!stillActive() || current!=pkg || activeRoot()?.windowId!=window || bridge.session!=session) return rejectEditor("editor changed")
        val latest=connection.getSurroundingText(200001,1,0) ?: return rejectEditor("editor stopped responding")
        if(!completeAtEnd(latest) || latest.text.toString()!=draft || !stillActive() || current!=pkg ||
            activeRoot()?.windowId!=window || bridge.session!=session ||
            (!bridge.compositionClear && draft.isNotEmpty())) return rejectEditor("draft, cursor or editor changed during verification")
        generatedHash=result.hashCode(); generatedWindow=window
        connection.commitText(result.substring(draft.length),1,null)
        toast("Insert requested through Android editor. Review the draft before sending.")
        sync(); return true
    }
    private fun pasteAtEnd(block:String) {
        if(!stillActive() || insertionPending) { sync(); return }
        val pkg=current; val window=activeRoot()?.windowId ?: return
        hide(); insertionPending=true
        handler.postDelayed({
            insertionPending=false
            if(!stillActive() || current!=pkg || activeRoot()?.windowId!=window) { sync(); return@postDelayed }
            val root=activeRoot() ?: run { sync(); return@postDelayed }
            val node=focusedEditor(root,pkg)
            if(node==null || !node.refresh() || !eligible(node,root,pkg) ||
                node.actionList.none { it.id==AccessibilityNodeInfo.ACTION_PASTE }) {
                fallback(block,"Android does not expose a paste action for this focused editor."); return@postDelayed
            }
            val draft=if(node.isShowingHintText) "" else node.text?.toString()
            if(draft==null || draft.length>200000 || node.textSelectionStart!=draft.length || node.textSelectionEnd!=draft.length) {
                fallback(block,"Paste requires a readable draft and cursor at the end, without a selection."); return@postDelayed
            }
            val result=CommandEngine.append(draft,block)
            if(result==draft) { toast("Commands already appended"); sync(); return@postDelayed }
            if(result.length>200000 || (node.maxTextLength>0 && result.length>node.maxTextLength)) { fallback(block,"Message too long."); return@postDelayed }
            if(!stillActive() || current!=pkg || activeRoot()?.windowId!=window || !node.refresh() ||
                !eligible(node,root,pkg) || (if(node.isShowingHintText) "" else node.text?.toString())!=draft ||
                node.textSelectionStart!=draft.length || node.textSelectionEnd!=draft.length) {
                fallback(block,"The draft or cursor changed. Try again."); return@postDelayed
            }
            try {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AI commands",result.substring(draft.length)))
                if(!stillActive() || current!=pkg || activeRoot()?.windowId!=window || !node.refresh() || !eligible(node,root,pkg) ||
                    (if(node.isShowingHintText) "" else node.text?.toString())!=draft || node.textSelectionStart!=draft.length || node.textSelectionEnd!=draft.length) {
                    fallback(block,"Commands copied, but the editor changed. Paste manually if appropriate."); return@postDelayed
                }
                generatedHash=result.hashCode(); generatedWindow=window
                val accepted=node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                toast(if(accepted)"Paste requested. Review your draft." else "Paste rejected. Commands are on your clipboard; review before pasting.")
                sync()
            } catch(_:Exception) { fallback(block,"Paste unavailable. Review your draft before retrying.") }
        },150)
    }
    private fun fallback(block:String,reason:String="Direct insertion is unavailable.") {
        if(!stillActive()) { sync(); return }; closePanel()
        val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(10),dp(8)); background=background(Color.rgb(17,24,39),16) }
        col.addView(TextView(this).apply { text="$reason\n\nOr copy just the commands and paste them manually after your draft."; setTextColor(Color.WHITE); textSize=15f })
        col.addView(Button(this).apply { text="Try paste at cursor"; isAllCaps=false; setOnClickListener { pasteAtEnd(block) } })
        col.addView(Button(this).apply { text="Copy commands"; isAllCaps=false; setOnClickListener { if(stillActive()) { try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AI commands",block)); toast("Copy requested. Paste manually.") } catch(_:Exception) { toast("Clipboard unavailable. Use the app's composer.") }; closePanel(); sync() } else sync() } })
        col.addView(Button(this).apply { text="Back"; isAllCaps=false; setOnClickListener { openPanel() } }); displayPanel(col)
    }
}
