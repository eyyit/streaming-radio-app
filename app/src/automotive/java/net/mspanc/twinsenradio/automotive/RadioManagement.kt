package net.mspanc.twinsenradio.automotive

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import net.mspanc.twinsenradio.BuildConfig
import net.mspanc.twinsenradio.AutomotiveUxGuard
import net.mspanc.twinsenradio.transfer.*
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.*
import net.mspanc.twinsenradio.playback.MetadataFactory

/** Parked management surfaces. Dialogs leave the active media session untouched. */
class RadioManagement(
 private val activity:Activity,
 private val scope:CoroutineScope,
 private val play:((Station,String)->Unit)?,
 private val changed:()->Unit,
 private val host:ViewGroup?=null
) {
 private val repo=StationRepository.get(activity)
 private val prefs=Prefs(activity)
 private val coral get()=AppearanceTheme.current(activity).accent
 private val muted=AutomotiveDesign.MUTED
 private fun dp(value:Int)=(value*activity.resources.displayMetrics.density).toInt()
 private val largeManagement get()=play==null
 private fun actionHeightDp()=if(largeManagement)AutomotiveDesign.LARGE_MANAGEMENT_ACTION_HEIGHT_DP else 52
 private fun settingsRowHeightDp(fullArea:Boolean):Int {
  if(!fullArea) return AutomotiveDesign.SETTINGS_ROW_HEIGHT_DP
  val fontScale=activity.resources.configuration.fontScale
  return ceil(AutomotiveDesign.LARGE_MANAGEMENT_ROW_HEIGHT_DP*fontScale).toInt().coerceAtLeast(AutomotiveDesign.LARGE_MANAGEMENT_ROW_HEIGHT_DP)
 }
 private fun rounded(color:Int,radius:Int=18)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
 private fun text(value:String,size:Float=18f,color:Int=AutomotiveDesign.TEXT)=TextView(activity).apply {
  text=value; textSize=size; setTextColor(color); setPadding(dp(8),dp(6),dp(8),dp(6))
 }
 private fun button(value:String,accent:Boolean=value=="Done",action:()->Unit)=TextView(activity).apply {
  text=value; textSize=if(largeManagement)AutomotiveDesign.LARGE_MANAGEMENT_ACTION_SP else AutomotiveDesign.BUTTON_TEXT_SP; setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); setTextColor(if(accent)AppearanceTheme.current(activity).onAccent else AutomotiveDesign.TEXT); gravity=Gravity.CENTER; minHeight=dp(actionHeightDp())
  setPadding(dp(if(largeManagement)24 else 18),dp(6),dp(if(largeManagement)24 else 18),dp(6)); isFocusable=true
  background=RippleDrawable(ColorStateList.valueOf(0x30ffffff),rounded(if(accent)coral else AutomotiveDesign.CONTROL),null)
  setOnClickListener { action() }
 }
 private fun refreshDoneButtons(view:View) {
  if(view is TextView && view.text.toString()=="Done") {
   view.setTextColor(AppearanceTheme.current(activity).onAccent)
   view.background=RippleDrawable(ColorStateList.valueOf(0x30ffffff),rounded(coral),null)
  }
  if(view is ViewGroup) for(index in 0 until view.childCount)refreshDoneButtons(view.getChildAt(index))
 }
 private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
 private val screens=mutableListOf<Pair<Dialog,View>>()
 private val tasks=mutableMapOf<Dialog,MutableList<Job>>()
 fun handleBack():Boolean { if(StationImageDialog.handleBack(activity)) return true; if(ThemePicker.handleBack()) return true; val dialog=screens.lastOrNull()?.first ?: return false; dialog.dismiss(); return true }
 fun close() { StationImageDialog.close(activity); ThemePicker.close(); while(screens.isNotEmpty()) screens.last().first.dismiss() }
 private fun task(dialog:Dialog,work:suspend ()->Unit) {
  tasks.getOrPut(dialog) { mutableListOf() } += scope.launch { work() }
 }
 private fun stationLogo(station:Station,dialog:Dialog):ImageView {
  val logo=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=rounded(AutomotiveDesign.FIELD,24); clipToOutline=true; contentDescription="${station.name} station logo"; setImageResource(R.drawable.logo_placeholder) }
  task(dialog) {
   try {
    val uri=MetadataFactory(activity,prefs).logoDisplayUri(station)
    val bitmap=withContext(Dispatchers.IO) { uri?.let { activity.contentResolver.openInputStream(it)?.use { input -> android.graphics.BitmapFactory.decodeStream(input,null,android.graphics.BitmapFactory.Options().apply { inSampleSize=4 }) } } }
    if(bitmap!=null) logo.setImageBitmap(bitmap)
   } catch(e:CancellationException) { throw e } catch(_:Exception) { /* Keep placeholder. */ }
  }
  return logo
 }
 private fun show(title:String,done:String="Done",onClose:()->Unit={},scroll:Boolean=true,settingsLayout:Boolean=largeManagement,build:(LinearLayout,Dialog)->Unit):Dialog {
  lateinit var root:LinearLayout
  var dismissed=false
  fun finish(dialog:Dialog) {
   if(dismissed) return
   dismissed=true; tasks.remove(dialog)?.forEach { it.cancel() }
   screens.removeAll { it.first===dialog }; host?.removeView(root)
   screens.lastOrNull()?.second?.visibility=View.VISIBLE
   if(screens.isEmpty()) host?.visibility=View.GONE
   onClose()
  }
  val dialog=object:Dialog(activity) {
   override fun dismiss() { if(host!=null) finish(this) else super.dismiss() }
  }
  dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
  root=column().apply { setPadding(if(settingsLayout)0 else dp(24),if(settingsLayout)0 else dp(16),if(settingsLayout)0 else dp(24),if(settingsLayout)0 else dp(16)); setBackgroundColor(AutomotiveDesign.CANVAS); isClickable=true; isFocusable=true }
  val panel=column().apply {
   setPadding(dp(24),dp(12),dp(24),dp(20))
   background=if(settingsLayout)rounded(Color.TRANSPARENT,0) else rounded(AutomotiveDesign.SURFACE,28)
  }
  root.addView(panel,LinearLayout.LayoutParams(-1,-1))
  val header=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
  header.addView(text(title,if(settingsLayout)AutomotiveDesign.LARGE_MANAGEMENT_LABEL_SP else 30f).apply { setTypeface(null,Typeface.BOLD); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(0,dp(if(settingsLayout)AutomotiveDesign.LARGE_MANAGEMENT_HEADER_HEIGHT_DP else 60),1f))
  header.addView(button(done) { dialog.dismiss() }.apply {
   if(settingsLayout) { textSize=AutomotiveDesign.LARGE_MANAGEMENT_ACTION_SP; minHeight=dp(AutomotiveDesign.LARGE_MANAGEMENT_ACTION_HEIGHT_DP); setPadding(dp(24),dp(8),dp(24),dp(8)) }
  },LinearLayout.LayoutParams(-2,dp(if(settingsLayout)AutomotiveDesign.LARGE_MANAGEMENT_ACTION_HEIGHT_DP else 52)))
  panel.addView(header)
  val body=column()
  val content:View=if(scroll) ScrollView(activity).apply { isFillViewport=settingsLayout; clipToPadding=false; addView(body) } else body
  panel.addView(content,LinearLayout.LayoutParams(-1,0,1f))
  if(host!=null) {
   screens.lastOrNull()?.second?.visibility=View.GONE
   screens += dialog to root
   host.visibility=View.VISIBLE; host.addView(root,ViewGroup.LayoutParams(-1,-1))
   build(body,dialog)
   return dialog
  }
  build(body,dialog)
  dialog.setContentView(root); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
  dialog.setOnDismissListener { finish(dialog) }; dialog.show()
  dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels*0.94f).toInt(),(activity.resources.displayMetrics.heightPixels*0.88f).toInt())
  return dialog
 }
 private fun addAction(parent:LinearLayout,caption:String,bottomMarginDp:Int=0,captionSize:Float=if(largeManagement)AutomotiveDesign.LARGE_MANAGEMENT_LABEL_SP else AutomotiveDesign.BUTTON_TEXT_SP,captionTypeface:Typeface?=null,action:()->Unit) {
  parent.addView(button(caption,action=action).apply { textSize=if(largeManagement && caption=="Done")AutomotiveDesign.LARGE_MANAGEMENT_ACTION_SP else captionSize; if(captionTypeface!=null)typeface=captionTypeface else if(largeManagement && caption!="Done")typeface=Typeface.create(AutomotiveDesign.SETTINGS_FONT_FAMILY,Typeface.NORMAL); gravity=Gravity.CENTER_VERTICAL or Gravity.START },LinearLayout.LayoutParams(-1,dp(if(largeManagement && caption=="Done")actionHeightDp() else settingsRowHeightDp(largeManagement))).apply { topMargin=dp(8); bottomMargin=dp(bottomMarginDp) })
 }
 fun settings(onClose:()->Unit={}) = settingsScreen(includeArtworkTransitions=play!=null,fullArea=play==null,onClose=onClose)

 /** OEM preferences surface; keep custom-player-only options out of retail settings. */
 fun nativeSettings(onClose:()->Unit={}) = settingsScreen(includeArtworkTransitions=false,fullArea=true,onClose=onClose)

 private fun settingsScreen(includeArtworkTransitions:Boolean,fullArea:Boolean,onClose:()->Unit) {
  show("Settings",onClose=onClose,settingsLayout=fullArea) { body,settingsDialog ->
   if(fullArea) body.setPadding(0,dp(12),0,0)
   fun settingsAction(parent:LinearLayout,caption:String,action:()->Unit) {
    if(!fullArea) {
     addAction(parent,caption,captionSize=AutomotiveDesign.SETTINGS_LABEL_SP,captionTypeface=Typeface.create(AutomotiveDesign.SETTINGS_FONT_FAMILY,Typeface.NORMAL),action=action)
     return
    }
    val row=TextView(activity).apply {
     text=caption
     textSize=AutomotiveDesign.LARGE_MANAGEMENT_LABEL_SP
     setTextColor(AutomotiveDesign.TEXT)
     typeface=Typeface.create(AutomotiveDesign.SETTINGS_FONT_FAMILY,Typeface.NORMAL)
     gravity=Gravity.CENTER_VERTICAL
     setPadding(dp(12),dp(4),dp(12),dp(4))
     maxLines=2
     isFocusable=true
     contentDescription=caption
     background=RippleDrawable(ColorStateList.valueOf(0x22ffffff),rounded(Color.TRANSPARENT,12),null)
     setOnClickListener { action() }
    }
    addSettingsRow(parent,row,settingsRowHeightDp(fullArea))
   }
   fun settingsGroup(build:(LinearLayout)->Unit):LinearLayout {
    val group=column().apply {
     background=rounded(AutomotiveDesign.SURFACE,20)
     setPadding(dp(12),dp(4),dp(12),dp(4))
    }
    body.addView(group,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(20) })
    build(group)
    return group
   }
   fun toggle(parent:LinearLayout,caption:String,value:Boolean,action:(Boolean)->Unit) {
    val row=LinearLayout(activity).apply {
     gravity=Gravity.CENTER_VERTICAL
     if(!fullArea) {
      background=rounded(AutomotiveDesign.CONTROL)
      setPadding(dp(12),0,dp(16),0)
     }
    }
    row.addView(text(caption,if(fullArea)36f else AutomotiveDesign.SETTINGS_LABEL_SP).apply {
     gravity=Gravity.CENTER_VERTICAL
     typeface=Typeface.create(AutomotiveDesign.SETTINGS_FONT_FAMILY,Typeface.NORMAL)
     maxLines=if(fullArea)2 else 1
     if(fullArea) setPadding(dp(12),dp(4),dp(12),dp(4))
    },LinearLayout.LayoutParams(0,dp(settingsRowHeightDp(fullArea)),1f))
    val toggle=Switch(activity).apply {
     contentDescription=caption
     isChecked=value
     if(fullArea) {
      // Explicit artwork avoids platform drawable insets shrinking the approved control.
      thumbDrawable=android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
       shape=GradientDrawable.OVAL
       setColor(Color.WHITE)
       setSize(dp(44),dp(44))
      },dp(5))
      trackDrawable=GradientDrawable().apply {
       shape=GradientDrawable.RECTANGLE
       cornerRadius=dp(27).toFloat()
       setColor(AutomotiveDesign.BORDER)
       setSize(dp(108),dp(54))
      }
      thumbTintList=ColorStateList.valueOf(Color.WHITE)
      val states=arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf())
      trackTintList=ColorStateList(states,intArrayOf(AutomotiveDesign.BORDER,AutomotiveDesign.CONTROL))
      background=null
      switchMinWidth=dp(108)
      gravity=Gravity.CENTER_VERTICAL
      minHeight=dp(72)
      showText=false
      splitTrack=false
     } else {
      thumbTintList=ColorStateList.valueOf(coral)
      minHeight=dp(AutomotiveDesign.SETTINGS_SWITCH_HEIGHT_DP)
     }
     setOnCheckedChangeListener { _,enabled -> action(enabled); changed() }
    }
    row.addView(toggle,LinearLayout.LayoutParams(if(fullArea)dp(108) else -2,dp(if(fullArea)72 else AutomotiveDesign.SETTINGS_SWITCH_HEIGHT_DP)).apply {
     if(fullArea) { marginStart=dp(8); marginEnd=dp(8) }
    })
    if(fullArea) addSettingsRow(parent,row,settingsRowHeightDp(fullArea))
    else parent.addView(row,LinearLayout.LayoutParams(-1,dp(settingsRowHeightDp(largeManagement))).apply { topMargin=dp(8) })
   }
   val themeAction:()->Unit = {
    val settingsView=screens.firstOrNull { it.first===settingsDialog }?.second
    settingsView?.visibility=View.GONE
    ThemePicker.show(activity,changed,{ settingsView?.let { refreshDoneButtons(it); it.visibility=View.VISIBLE } },host=host)
   }
   if(fullArea) {
    settingsGroup { group ->
     settingsAction(group,"Theme",themeAction)
     settingsAction(group,"Manage playlists") { playlists() }
     toggle(group,"Automatic track and station artwork",prefs.onlineArtworkEnabled) { prefs.onlineArtworkEnabled=it }
     settingsAction(group,"Refresh playlists") { refreshPlaylists() }
    }
    settingsGroup { group ->
     settingsAction(group,"Privacy policy") { legalDocument("Privacy policy",listOf("legal/privacy-policy.txt")) }
     settingsAction(group,"Open-source licenses") { legalDocument("Open-source licenses",listOf("legal/open-source-notices.txt","legal/gpl-3.0.txt","legal/dependency-notices.txt","legal/runtime-dependency-notices.txt","legal/apache-2.0.txt","legal/lucide-isc.txt")) }
    }
   } else {
    settingsAction(body,"Theme",themeAction)
    settingsAction(body,"Manage playlists") { playlists() }
    toggle(body,"Automatic track and station artwork",prefs.onlineArtworkEnabled) { prefs.onlineArtworkEnabled=it }
    if(includeArtworkTransitions) toggle(body,"Animate artwork changes",prefs.artworkTransitionsEnabled) { prefs.artworkTransitionsEnabled=it }
    settingsAction(body,"Refresh playlists") { refreshPlaylists() }
    settingsAction(body,"Privacy policy") { legalDocument("Privacy policy",listOf("legal/privacy-policy.txt")) }
    settingsAction(body,"Open-source licenses") { legalDocument("Open-source licenses",listOf("legal/open-source-notices.txt","legal/gpl-3.0.txt","legal/dependency-notices.txt","legal/runtime-dependency-notices.txt","legal/apache-2.0.txt","legal/lucide-isc.txt")) }
   }
  }
 }
 private fun addSettingsRow(parent:LinearLayout,row:View,heightDp:Int) {
  if(parent.childCount>0) {
   parent.addView(View(activity).apply { setBackgroundColor(0x66778390) },LinearLayout.LayoutParams(-1,dp(1)).apply {
    marginStart=dp(8)
    marginEnd=dp(8)
   })
  }
  parent.addView(row,LinearLayout.LayoutParams(-1,dp(heightDp)))
 }
 private fun legalDocument(title:String,assets:List<String>) {
  show(title) { body,_ ->
   val contents=assets.joinToString("\n\n") { file -> activity.assets.open(file).bufferedReader().use { it.readText() } }
   body.addView(text(contents,16f,muted).apply { setTextIsSelectable(true) })
  }
 }
 fun playlists() {
  var dialog:Dialog?=null
  fun render() {
   dialog?.dismiss()
   dialog=show("Manage playlists","Done") { body,_ ->
    body.addView(button("+ Add playlist",true) { editPlaylist(null) { render(); changed() } },LinearLayout.LayoutParams(-2,dp(actionHeightDp())).apply { topMargin=dp(8); bottomMargin=dp(12) })
    addAction(body,"Hidden and removed stations") { restoreStations() }
    addAction(body,"Refresh playlists",bottomMarginDp=12) { refreshPlaylists { render(); changed() } }
    val sources=repo.playlists()
    if(sources.isEmpty()) body.addView(text("No playlists yet. Add an M3U URL to load stations.",18f,muted))
    sources.forEach { source ->
     val details=column()
     details.addView(text(if(source.isLocal)source.name else source.url.trim(),if(largeManagement)AutomotiveDesign.LARGE_MANAGEMENT_LABEL_SP else 23f).apply { setTypeface(null,Typeface.BOLD); setSingleLine(); ellipsize=android.text.TextUtils.TruncateAt.MIDDLE },LinearLayout.LayoutParams(-1,-2))
     details.addView(text("${repo.sourceStations(source.id).size} stations"+(if(source.isLocal)" · Saved on device" else ""),16f,muted))
     body.addView(details,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12); bottomMargin=dp(8) })
     val actions=LinearLayout(activity)
     actions.addView(button("Open") { openSource(source.id) { render(); changed() } },LinearLayout.LayoutParams(0,dp(actionHeightDp()),1f).apply { marginEnd=dp(6) })
     actions.addView(button("Remove") { confirmRemove(source.id,source.name) { render(); changed() } },LinearLayout.LayoutParams(0,dp(actionHeightDp()),1f).apply { marginStart=dp(6) })
     body.addView(actions,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(20) })
    }
    body.addView(text("All stations combines these sources. Shared stream URLs appear once. Your saved order is kept when sources refresh.",16f,muted))
   }
  }
  render()
 }
 private fun openSource(id:String,onChanged:()->Unit) {
  val item=repo.playlists().firstOrNull { it.id==id } ?: return
  lateinit var dialog:Dialog
  dialog=show(item.name,"Done",scroll=false) { body,screen ->
   val actions=LinearLayout(activity)
   actions.addView(button("Edit") { editPlaylist(id) { dialog.dismiss(); onChanged(); openSource(id,onChanged) } },LinearLayout.LayoutParams(-2,dp(actionHeightDp())).apply { marginEnd=dp(12) })
   actions.addView(button("Remove") { confirmRemove(id,item.name) { dialog.dismiss(); onChanged() } })
   body.addView(actions)
   body.addView(text(if(item.isLocal) "${item.stationCount} stations · Saved on device; no remote refresh" else "${item.stationCount} stations · M3U source order",16f,muted))
   var stations=repo.sourceStations(id)
   val sourceList=ListView(activity).apply { dividerHeight=0 }
   body.addView(sourceList,LinearLayout.LayoutParams(-1,0,1f))
   sourceList.divider=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT); sourceList.dividerHeight=dp(8); sourceList.cacheColorHint=Color.TRANSPARENT
   sourceList.adapter=object:BaseAdapter() {
    override fun getCount()=stations.size
    override fun getItem(position:Int)=stations[position]
    override fun getItemId(position:Int)=stations[position].id.hashCode().toLong()
    override fun getView(position:Int,recycled:View?,parent:android.view.ViewGroup):View {
    val station=stations[position]
    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL; background=rounded(AutomotiveDesign.CONTROL); setPadding(dp(8),dp(8),dp(8),dp(8)) }
    val logo=stationLogo(station,screen)
    row.addView(logo,LinearLayout.LayoutParams(dp(112),dp(112)).apply { marginEnd=dp(12) })
    val details=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
    row.addView(details,LinearLayout.LayoutParams(0,dp(112),1f))
    val captions=column()
    details.addView(captions,LinearLayout.LayoutParams(0,-1,1f))
    val stationName=text(station.name,23f).apply { setTypeface(null,Typeface.BOLD); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(8),0,dp(8),0) }
    if(play!=null) stationName.setOnClickListener { play.invoke(station,"/playlist/$id") }
    captions.addView(stationName,LinearLayout.LayoutParams(-1,dp(32)))
    captions.addView(text(StationText.subtitle(station),16f,muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(8),0,dp(8),0) },LinearLayout.LayoutParams(-1,dp(28)))
    val stationActions=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
    details.addView(stationActions,LinearLayout.LayoutParams(dp(176),dp(80)))
    val heart=ImageButton(activity).apply {
     fun update() { setImageResource(if(station.id in prefs.favourites)R.drawable.ic_star_filled else R.drawable.ic_star_outline); setColorFilter(if(station.id in prefs.favourites)coral else Color.WHITE) }
     update(); contentDescription="Toggle ${station.name} favorite"; background=rounded(Color.TRANSPARENT); setPadding(dp(12),dp(12),dp(12),dp(12)); scaleType=ImageView.ScaleType.FIT_CENTER
     setOnClickListener { prefs.toggleFavourite(station.id); repo.favourites(); update(); changed() }
    }
    stationActions.addView(heart,LinearLayout.LayoutParams(dp(80),dp(80)))
    stationActions.addView(ImageButton(activity).apply {
     setImageResource(R.drawable.ic_station_image); background=rounded(Color.TRANSPARENT); setPadding(dp(12),dp(12),dp(12),dp(12)); contentDescription="Change ${station.name} station image"; scaleType=ImageView.ScaleType.FIT_CENTER
     setOnClickListener { StationImageDialog.show(activity,station,scope,changed=changed,onOpenSettings={ settings() },host=host) }
    },LinearLayout.LayoutParams(dp(80),dp(80)).apply { marginStart=dp(16) })
    val remove=button("Remove…") { stationAction(station) {
     stations=repo.sourceStations(id); notifyDataSetChanged(); changed()
    } }.apply { textSize=AutomotiveDesign.BUTTON_TEXT_SP; minHeight=0; background=RippleDrawable(ColorStateList.valueOf(0x30ffffff),rounded(AutomotiveDesign.SURFACE,16),null) }
    captions.addView(remove,LinearLayout.LayoutParams(dp(246),dp(48)).apply { marginStart=dp(8); topMargin=dp(4) })
    captions.addOnLayoutChangeListener { _,left,_,right,_,_,_,_,_ ->
     val available=(right-left-dp(8)).coerceAtLeast(0)
     val width=minOf(dp(246),available)
     if(remove.layoutParams.width!=width) {
      remove.layoutParams=LinearLayout.LayoutParams(width,dp(48)).apply { marginStart=dp(8); topMargin=dp(4) }
     }
    }
    row.layoutParams=AbsListView.LayoutParams(-1,dp(128))
    return row
    }
   }
  }
 }
 fun addPlaylist() { editPlaylist(null) { changed() } }
 private fun editPlaylist(id:String?,saved:()->Unit) {
  val source=repo.playlists().firstOrNull { it.id==id }
  show(if(source==null)"Add playlist" else "Edit playlist","Cancel") { body,dialog ->
   fun input(caption:String,value:String):EditText {
    body.addView(text(caption,16f,muted))
    val edit=EditText(activity).apply { setText(value); setTextColor(AutomotiveDesign.TEXT); setSingleLine(); textSize=if(largeManagement)AutomotiveDesign.LARGE_MANAGEMENT_ACTION_SP else 18f; setPadding(dp(14),dp(8),dp(14),dp(8)); background=rounded(AutomotiveDesign.FIELD,12) }
    body.addView(edit,LinearLayout.LayoutParams(-1,dp(if(largeManagement)AutomotiveDesign.LARGE_MANAGEMENT_ACTION_HEIGHT_DP else 56)).apply { bottomMargin=dp(12) }); return edit
   }
   if(source == null) {
    body.addView(button("Use phone", true) { phonePlaylist(saved,dialog) }, LinearLayout.LayoutParams(-1,dp(actionHeightDp())).apply { topMargin=dp(12); bottomMargin=dp(8) })
    body.addView(text("Scan a QR code to paste a URL or send an M3U.",if(largeManagement)24f else 17f,muted))
   }
   val urlField=if(source?.isLocal==true)null else input("M3U playlist URL",source?.url.orEmpty()).apply {
    inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
    hint="https://example.com/stations.m3u"; setHintTextColor(muted)
   }
   val name=input(if(source?.isLocal==true)"Name" else "Name (optional)",source?.name.orEmpty()).apply {
    if(source?.isLocal!=true) { hint="Leave blank to use the playlist URL"; setHintTextColor(muted) }
   }
   if(source?.isLocal==true) {
    body.addView(text("Saved on device; no remote refresh. Add a hosted M3U as a separate playlist when needed.",17f,muted))
    body.addView(button("Save changes",true) {
     if(repo.renameLocalPlaylist(name.text.toString().trim(),source.id)) { dialog.dismiss(); saved() }
    },LinearLayout.LayoutParams(-2,dp(actionHeightDp())))
    return@show
   }
   val url=checkNotNull(urlField)
   if(source==null) url.requestFocus()
   val status=text("Test the URL before saving. Existing stations stay available if validation fails.",17f,muted); body.addView(status)
   val progress=ProgressBar(activity).apply { isIndeterminate=true; visibility=View.GONE; indeterminateTintList=ColorStateList.valueOf(coral); contentDescription="Checking playlist" }
   body.addView(progress,LinearLayout.LayoutParams(dp(40),dp(40)))
   val actions=LinearLayout(activity); body.addView(actions)
   var validatedUrl:String?=null
   var busy=false
   lateinit var test:TextView
   lateinit var save:TextView
   fun available() { name.isEnabled=!busy; url.isEnabled=!busy; progress.visibility=if(busy)View.VISIBLE else View.GONE; test.isEnabled=!busy; test.alpha=if(busy)0.4f else 1f; save.isEnabled=!busy && validatedUrl==url.text.toString().trim(); save.alpha=if(save.isEnabled)1f else 0.35f }
   test=button("Test URL") {
    if(busy) return@button
    busy=true; status.setTextColor(muted); status.text="Checking playlist…"; available()
    task(dialog) {
     try {
      val candidate=url.text.toString().trim(); val stations=repo.validatePlaylist(candidate)
      validatedUrl=candidate; status.setTextColor(0xff83d9a3.toInt()); status.text="${stations.size} stations found · Ready to ${if(source==null)"add" else "save"}"
     } catch(e:CancellationException) { throw e } catch(e:Exception) {
      validatedUrl=null; status.setTextColor(0xfff28585.toInt()); status.text=(e.message?.take(180) ?: "Could not load this playlist. Check the URL or try another M3U.")+"\nYour last good playlist is unchanged."
     } finally { busy=false; available() }
    }
   }
   save=button(if(source==null)"Add playlist" else "Save changes",true) {
    if(busy || validatedUrl!=url.text.toString().trim()) return@button
    busy=true; available(); status.text="Saving…"
    task(dialog) {
     try { repo.savePlaylist(name.text.toString().trim(),validatedUrl!!,id); dialog.dismiss(); saved() }
     catch(e:CancellationException) { throw e } catch(e:Exception) { status.setTextColor(0xfff28585.toInt()); status.text=e.message ?: "Could not save. Your previous playlist is unchanged." }
     finally { busy=false; available() }
    }
   }
   actions.addView(test,LinearLayout.LayoutParams(-2,dp(actionHeightDp())).apply { marginEnd=dp(12) }); actions.addView(save,LinearLayout.LayoutParams(-2,dp(actionHeightDp())))
   url.addTextChangedListener(object:TextWatcher {
    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { validatedUrl=null; available() }
    override fun afterTextChanged(s:Editable?) {}
   })
   available()
  }
 }
 private fun stationAction(station:Station,updated:()->Unit) {
  show("Remove from App?","Cancel") { body,dialog ->
   body.addView(text(station.name,20f,muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
   fun choice(title:String,explanation:String,action:()->Unit) {
    val card=column().apply { background=rounded(AutomotiveDesign.FIELD,20); setPadding(dp(16),dp(12),dp(16),dp(12)) }
    card.addView(text(title,27f).apply { setTypeface(null,Typeface.BOLD) })
    card.addView(text(explanation,21f,muted))
    card.addView(button(title,true,action),LinearLayout.LayoutParams(-2,dp(actionHeightDp())).apply { topMargin=dp(10) })
    body.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
   }
   choice("Hide from All stations","Hide only from All stations. Keep it in this playlist and Favorites. Restore in the app.") { repo.hideFromAll(station); dialog.dismiss(); updated() }
   choice("Remove from App","Exclude this stream across all sources and Favorites. Keep exclusion after refresh. Restore in Settings. Source M3Us stay unchanged.") { repo.removeFromApp(station); dialog.dismiss(); updated() }
   body.addView(text("Both actions affect this app only; source M3Us are unchanged.",18f,muted))
  }
 }
 private fun restoreStations() {
  var dialog:Dialog?=null
  fun render() {
   dialog?.dismiss()
   dialog=show("Hidden and removed stations","Done") { body,screen ->
    body.addView(text("Restore stations still present in a saved source. Favorites are not added automatically.",17f,muted))
    val rules=repo.restorableStations()
    if(rules.isEmpty()) body.addView(text("No hidden or removed stations.",20f,muted))
    rules.forEach { rule ->
     val station=rule.station
     val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(10)); background=rounded(AutomotiveDesign.CONTROL) }
     row.addView(stationLogo(station,screen),LinearLayout.LayoutParams(dp(112),dp(112)).apply { marginEnd=dp(12) })
     val captions=column(); row.addView(captions,LinearLayout.LayoutParams(0,-2,1f))
     captions.addView(text(station.name,23f).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) })
     val sources=repo.exclusionSourceNames(station)
     captions.addView(text((if(rule.removed)"Removed" else "Hidden")+" · "+sources.joinToString().ifBlank { "Source unavailable" },16f,muted))
     val available=repo.canRestoreStation(station)
     captions.addView(button("Restore",true) { if(repo.restoreStation(station)) { changed(); render() } }.apply { isEnabled=available; alpha=if(available)1f else 0.4f },LinearLayout.LayoutParams(-2,dp(actionHeightDp())))
     if(!available) captions.addView(text("Re-add this station's playlist before restoring.",17f,muted))
     body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
    }
   }
  }
  render()
 }

 /** Optional, parked-only transfer. Missing configuration never blocks manual entry. */
 private fun phonePlaylist(saved:()->Unit,parentDialog:Dialog) {
  if(BuildConfig.IS_AUTOMOTIVE && !AutomotiveUxGuard.canUseSettings) return
  val config=runCatching { TransferConfig(BuildConfig.PHONE_TRANSFER_API_KEY,
   BuildConfig.PHONE_TRANSFER_DATABASE_URL,BuildConfig.PHONE_TRANSFER_HOSTING_URL) }.getOrNull()
  if(config==null) {
   show("Use phone","Done") { body,_ ->
    body.addView(text("Phone transfer is not configured in this build. You can still enter a playlist URL.",if(largeManagement)28f else 20f))
   }
   return
  }
  val client=PhoneTransferClient(config,PrivateTransferStore(activity,config))
  var session:TransferSession?=null
  var closed=false
  fun cleanup() {
   if(closed) return
   closed=true
   val active=session ?: return
   // A bounded independent cleanup may outlive the Activity; failed IDs remain private for retry.
   CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO).launch {
    kotlinx.coroutines.withTimeoutOrNull(15_000) {
     try { client.close(active) } catch(cancelled:CancellationException) { throw cancelled }
     catch(_:Exception) { /* Known session is retained for cleanup on next entry. */ }
    }
   }
  }
  show("Use phone","Cancel",onClose={cleanup()}) { body,dialog ->
   body.addView(text("Scan with your phone camera",if(largeManagement)36f else 24f))
   val image=ImageView(activity).apply { contentDescription="Scan this QR code to send a playlist"; scaleType=ImageView.ScaleType.FIT_CENTER }
   body.addView(image,LinearLayout.LayoutParams(dp(280),dp(280)).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=dp(20); bottomMargin=dp(20) })
   val status=text("Starting phone transfer…",if(largeManagement)28f else 20f,muted)
   body.addView(status)
   body.addView(text("Then paste a URL or select an M3U file.\nThe code expires after five minutes.",if(largeManagement)26f else 18f,muted))
   task(dialog) {
    try {
     kotlinx.coroutines.withTimeoutOrNull(15_000) { client.cleanupKnownSessions() }
     val active=client.createSession(); session=active
     if(closed) { client.close(active); return@task }
     val bitmap=withContext(Dispatchers.Default) {
      val matrix=com.google.zxing.qrcode.QRCodeWriter().encode(active.pairingUrl,com.google.zxing.BarcodeFormat.QR_CODE,560,560)
      val pixels=IntArray(560*560) { i -> if(matrix[i%560,i/560]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
      android.graphics.Bitmap.createBitmap(pixels,560,560,android.graphics.Bitmap.Config.ARGB_8888)
     }
     image.setImageBitmap(bitmap); status.text="Waiting for phone…"
     when(val result=client.awaitPayload(active)) {
      is TransferPoll.Submitted -> {
       status.text="Checking playlist…"
       val payload=result.payload
       val stations=withContext(Dispatchers.IO) {
        if(payload.kind==TransferKind.URL) repo.validatePlaylist(payload.value)
        else PlaylistLibrary.parseCachedPlaylist(payload.value,PlaylistLibrary.LOCAL_SNAPSHOT_URL)
       }
       val name=payload.name.ifBlank { if(payload.kind==TransferKind.URL) PlaylistNames.fromUrl(payload.value,"Playlist") else "Imported playlist" }
       val panel=(body.parent as? android.widget.ScrollView)?.parent as? ViewGroup
       val header=panel?.getChildAt(0) as? ViewGroup
       (header?.getChildAt(0) as? TextView)?.text="Add this playlist?"
       body.removeAllViews()
       body.addView(text("Add this playlist?",if(largeManagement)36f else 26f))
       body.addView(text(name,if(largeManagement)36f else 26f))
       body.addView(text("${stations.size} stations",if(largeManagement)32f else 22f))
       body.addView(text(if(payload.kind==TransferKind.URL) "Hosted playlist · updates from its source automatically" else "Saved in the car · this file does not refresh automatically",if(largeManagement)26f else 18f,muted))
       if(payload.kind==TransferKind.URL) body.addView(text(payload.value,if(largeManagement)26f else 18f,muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.MIDDLE })
       val error=text("Your current playlists stay in the app.",if(largeManagement)26f else 18f,muted)
       body.addView(error)
       lateinit var add:TextView
       add=button("Add playlist",true) {
        if(!add.isEnabled || closed) return@button
        if(BuildConfig.IS_AUTOMOTIVE && !AutomotiveUxGuard.canUseSettings) { dialog.dismiss(); return@button }
        if(System.currentTimeMillis()>=active.expiresAt) { error.text="Pairing expired. Cancel and start a new QR code."; return@button }
        add.isEnabled=false; error.text="Adding playlist…"
        task(dialog) {
         try {
          val imported=if(payload.kind==TransferKind.URL) repo.savePlaylist(name,payload.value)
          else repo.saveLocalPlaylist(name,payload.value)
          changed()
          var deleted=false
          try { client.close(active); deleted=true }
          catch(cancelled:CancellationException) { throw cancelled }
          catch(_:Exception) { /* The local import is complete; retain cleanup credentials. */ }
          val successHeader=panel?.getChildAt(0) as? ViewGroup
          (successHeader?.getChildAt(0) as? TextView)?.text="Playlist added"
          fun finishImport(open:Boolean) {
           dialog.dismiss(); parentDialog.dismiss(); saved()
           if(open) openSource(imported.id) { changed() }
          }
          (successHeader?.getChildAt(1) as? TextView)?.let { done ->
           done.text="Done"; refreshDoneButtons(done); done.setOnClickListener { finishImport(false) }
          }
          body.removeAllViews()
          body.addView(text("Playlist added",if(largeManagement)36f else 26f))
          body.addView(text("${stations.size} stations are ready.",if(largeManagement)32f else 22f))
          body.addView(text(if(deleted) "The temporary transfer is deleted." else "Added to the car. Temporary-transfer cleanup will be retried.",if(largeManagement)26f else 18f,muted))
          body.addView(button("Open playlist",true) { finishImport(true) },LinearLayout.LayoutParams(-1,dp(actionHeightDp())))
         } catch(cancelled:CancellationException) { throw cancelled }
         catch(_:Exception) { error.text="Could not add this playlist. Your existing playlists are unchanged."; add.isEnabled=true }
        }
       }
       body.addView(add,LinearLayout.LayoutParams(-1,dp(actionHeightDp())).apply { topMargin=dp(20) })
      }
      TransferPoll.Expired -> {
       status.text="This code has expired. Start a new session."
       body.addView(button("New QR code",true) { dialog.dismiss(); phonePlaylist(saved,parentDialog) },LinearLayout.LayoutParams(-1,dp(actionHeightDp())))
      }
      TransferPoll.Waiting -> Unit
     }
    } catch(cancelled:CancellationException) { throw cancelled }
    catch(_:Exception) {
     image.setImageDrawable(null); status.text="Phone transfer could not complete. Existing playlists are unchanged."
     body.addView(button("Enter URL instead",true) { dialog.dismiss() },LinearLayout.LayoutParams(-1,dp(actionHeightDp())))
    }
   }
  }
 }

 private fun refreshPlaylists(updated:()->Unit={}) {
  show("Refresh playlists","Cancel") { body,dialog ->
   val progress=ProgressBar(activity).apply { isIndeterminate=true; indeterminateTintList=ColorStateList.valueOf(coral); contentDescription="Refreshing playlists" }
   body.addView(progress,LinearLayout.LayoutParams(dp(48),dp(48)).apply { topMargin=dp(24) })
   val status=text("Refreshing saved sources…\nYour last good stations stay available while this runs.",20f,muted); body.addView(status)
   task(dialog) {
    try {
     repo.refreshPlaylists(true); changed()
     status.text=if(repo.lastRefreshFailureCount>0) "${repo.lastRefreshFailureCount} sources could not refresh. Their last good copies are still available. Check the URL or try again." else "Playlists updated. ${repo.all().size} stations available."
     if(repo.lastRefreshFailureCount>0) status.setTextColor(0xfff28585.toInt())
    } catch(e:CancellationException) { throw e }
    catch(_:Exception) { status.setTextColor(0xfff28585.toInt()); status.text="Refresh failed. Your last good playlists are unchanged. Try again when a connection is available." }
    finally { progress.visibility=View.GONE }
    addAction(body,"Done") { dialog.dismiss(); updated() }
   }
  }
 }
 private fun confirmRemove(id:String,name:String,removed:()->Unit) {
  show("Remove $name?","Cancel") { body,dialog ->
   body.addView(text("Stations shared with another playlist stay. Favorites keep their saved stream URL. Current playback continues.",20f,muted))
   body.addView(button("Remove playlist",true) { repo.removePlaylist(id); dialog.dismiss(); removed() },LinearLayout.LayoutParams(-2,dp(actionHeightDp())).apply { topMargin=dp(20) })
  }
 }
 private fun chooseStationImage() {
  if(!prefs.onlineArtworkEnabled) { StationImageDialog.showDisabled(activity) {}; return }
  show("Station images","Done") { body,_ ->
   (repo.all()+repo.favourites()).distinctBy { it.id }.forEach { station -> addAction(body,station.name) { StationImageDialog.show(activity,station,scope,changed,host=host) } }
  }
 }
}


