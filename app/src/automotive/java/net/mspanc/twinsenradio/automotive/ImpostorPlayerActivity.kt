package net.mspanc.twinsenradio.automotive

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.*
import net.mspanc.twinsenradio.playback.*
import net.mspanc.twinsenradio.ui.ArtworkLoader

/** Approved parked-only player. Native car media remains the driving surface. */
@UnstableApi
class ImpostorPlayerActivity : AppCompatActivity() {
 private val coral get()=net.mspanc.twinsenradio.data.AppearanceTheme.current(this).accent
 private var lastAccent=0
  private val muted=AutomotiveDesign.MUTED
 private lateinit var repo: StationRepository
 private lateinit var prefs: Prefs
 private lateinit var art: MetadataFactory
 private lateinit var body: LinearLayout
 private lateinit var left: LinearLayout
 private lateinit var list: ListView
 private lateinit var tabs: LinearLayout
 private lateinit var adapter: Stations
 private lateinit var detail: LinearLayout
 private lateinit var cover: ImageView
 private lateinit var addPlaylistButton: TextView
 private lateinit var trackView: TextView
 private lateinit var artistView: TextView
 private lateinit var stationView: TextView
 private lateinit var metadataFooter: TextView
 private val footerHandler=Handler(Looper.getMainLooper())
 private val footerTick=object:Runnable {
  override fun run() { renderMetadataFooter(); footerHandler.postDelayed(this,1000L) }
 }
 private lateinit var playPause: ImageButton
 private lateinit var heart: ImageButton
 private lateinit var shuffle: ImageButton
 private lateinit var shuffleDot: View
 private lateinit var artworkProgress: ArtworkLoadingView
 private lateinit var artworkOverlay: FrameLayout
 private lateinit var management: RadioManagement
 private lateinit var managementHost: FrameLayout
 private lateinit var trackArtToggle: ImageButton
 private var controller: MediaController?=null
 private var generation=0
 private var mode=0 //0 split,2 player only
 private var browseParent=RadioService.NODE_ALL
 private var rows=emptyList<Station>()
 private var direction=0
 private val ui by lazy { getSharedPreferences("streaming_radio_ui",MODE_PRIVATE) }
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun bg(color:Int,radius:Int=24)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
  private fun label(size:Float,color:Int=AutomotiveDesign.TEXT)=TextView(this).apply { textSize=size; setTextColor(color); maxLines=1; ellipsize=TextUtils.TruncateAt.END }
 private fun pill(caption:String,selected:Boolean=false,leading:Int=0,action:()->Unit)=label(16f).apply {
  text=caption; gravity=Gravity.CENTER; setTypeface(null,Typeface.BOLD); setPadding(dp(12),0,dp(12),0)
   background=RippleDrawable(ColorStateList.valueOf(0x25FFFFFF),bg(if(selected) coral else AutomotiveDesign.CONTROL,28),null)
  minHeight=dp(52); isFocusable=true; if(leading!=0) { setCompoundDrawablesRelativeWithIntrinsicBounds(leading,0,0,0); compoundDrawablePadding=dp(4) }; setOnClickListener { action() }
 }
 private fun icon(res:Int,description:String,action:()->Unit)=ImageButton(this).apply {
  setImageResource(res); contentDescription=description; scaleType=ImageView.ScaleType.FIT_CENTER
  setPadding(dp(18),dp(18),dp(18),dp(18)); setColorFilter(Color.WHITE)
  background=RippleDrawable(ColorStateList.valueOf(0x25FFFFFF),bg(Color.TRANSPARENT,36),null)
  setOnClickListener { action() }
 }
  private fun image()=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=bg(AutomotiveDesign.FIELD,12); clipToOutline=true }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  repo=StationRepository.get(this); prefs=Prefs(this); art=MetadataFactory(this,prefs)
  val savedMode=ui.getInt("mode",0)
  mode=if(savedMode==2)2 else 0
  if(savedMode!=mode) ui.edit().putInt("mode",mode).apply()
  browseParent=ui.getString("browse",RadioService.NODE_ALL) ?: RadioService.NODE_ALL
  if(browseParent!=RadioService.NODE_ALL && browseParent!=RadioService.NODE_FAVOURITES) browseParent=RadioService.NODE_ALL
   val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(AutomotiveDesign.CANVAS) }
  setContentView(root)
  val content=FrameLayout(this); root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
  body=LinearLayout(this); content.addView(body,FrameLayout.LayoutParams(-1,-1).apply { bottomMargin=dp(28) })
  metadataFooter=label(14f,0xff89939d.toInt()).apply {
   gravity=Gravity.CENTER; visibility=View.INVISIBLE
   setPadding(dp(12),0,dp(12),0)
  }
  content.addView(metadataFooter,FrameLayout.LayoutParams(-1,dp(28),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
  managementHost=FrameLayout(this); content.addView(managementHost,FrameLayout.LayoutParams(-1,-1))
  management=RadioManagement(this,lifecycleScope,{ station,parent -> select(station,parent) },{ applyTheme(); refreshRows(); renderPlayer() },host=managementHost)
  left=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(12),dp(12),0) }
  tabs=LinearLayout(this); left.addView(tabs,LinearLayout.LayoutParams(-1,dp(52)))
  list=ListView(this).apply { dividerHeight=0; setPadding(0,dp(12),0,dp(12)); clipToPadding=true; isVerticalScrollBarEnabled=true }
  adapter=Stations(); list.adapter=adapter; left.addView(list,LinearLayout.LayoutParams(-1,0,1f))
  buildTransport(root); applyTheme(); refreshRows(); buildBody()
  lifecycleScope.launch { repo.revisions.collect { refreshRows(); renderPlayer() } }
  lifecycleScope.launch { Prefs.favouritesFlow.collect { refreshRows(force=true); renderPlayer() } }
  lifecycleScope.launch { StationArtworkStore.loading.collect { adapter.notifyDataSetChanged(); renderArtworkLoading() } }
  lifecycleScope.launch { PlaybackStatusBus.trackArtworkLoading.collect { renderArtworkLoading() } }
  lifecycleScope.launch { PlaybackStatusBus.status.collect { renderPlayer() } }
 }
 private fun buildTransport(root:LinearLayout) {
  val dock=LinearLayout(this).apply {
    gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(4),dp(12),dp(4)); background=bg(AutomotiveDesign.SURFACE,20)
  }
  root.addView(dock,LinearLayout.LayoutParams(-1,dp(92)))
  dock.addView(icon(R.drawable.ic_settings_gear,"Settings") { management.settings() },LinearLayout.LayoutParams(dp(72),dp(72)))
  dock.addView(View(this),LinearLayout.LayoutParams(dp(72),1))
  val transports=LinearLayout(this).apply { gravity=Gravity.CENTER }
  dock.addView(transports,LinearLayout.LayoutParams(0,-1,1f))
  transports.addView(icon(R.drawable.ic_transport_previous,"Previous station") { direction=-1; controller?.seekToPreviousMediaItem() },LinearLayout.LayoutParams(dp(72),dp(72)))
  transports.addView(View(this),LinearLayout.LayoutParams(dp(74),1))
  playPause=icon(R.drawable.ic_transport_play,"Play") {
   val c=controller ?: return@icon
   if(c.playWhenReady) c.pause() else if(c.currentMediaItem==null) repo.resumeCandidate()?.let { select(it) } else c.play()
  }.apply { background=RippleDrawable(ColorStateList.valueOf(0x30FFFFFF),bg(coral,48),null); setPadding(dp(10),dp(10),dp(10),dp(10)) }
  transports.addView(playPause,LinearLayout.LayoutParams(dp(76),dp(76)))
  transports.addView(View(this),LinearLayout.LayoutParams(dp(74),1))
  transports.addView(icon(R.drawable.ic_transport_next,"Next station") { direction=1; controller?.seekToNextMediaItem() },LinearLayout.LayoutParams(dp(72),dp(72)))
  val shuffleBox=FrameLayout(this)
  shuffle=icon(R.drawable.ic_control_shuffle,"Enable random station order") {
   prefs.randomStationOrder=!prefs.randomStationOrder; renderPlayer()
  }
  shuffleBox.addView(shuffle,FrameLayout.LayoutParams(-1,dp(72)))
  shuffleDot=View(this).apply { background=bg(coral,4) }
  shuffleBox.addView(shuffleDot,FrameLayout.LayoutParams(dp(5),dp(5),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(4) })
  dock.addView(shuffleBox,LinearLayout.LayoutParams(dp(72),dp(80)))
  heart=icon(R.drawable.ic_star_outline,"Add station to Favorites") {
   currentStation()?.let {
    val added=prefs.toggleFavourite(it.id)
    heart.setImageResource(if(added)R.drawable.ic_star_filled else R.drawable.ic_star_outline)
    heart.setColorFilter(if(added)coral else Color.WHITE)
    heart.contentDescription=if(added)"Remove station from Favorites" else "Add station to Favorites"
    repo.favourites()
   }
  }
  dock.addView(heart,LinearLayout.LayoutParams(dp(72),dp(72)))
 }
 private fun applyTheme() {
  if(!::playPause.isInitialized || lastAccent==coral) return
  lastAccent=coral
  playPause.background=RippleDrawable(ColorStateList.valueOf(0x30ffffff),bg(coral,48),null)
  playPause.setColorFilter(net.mspanc.twinsenradio.data.AppearanceTheme.current(this).onAccent)
  shuffleDot.background=bg(coral,4)
 }
 private fun setMode(value:Int) {
  val position=list.firstVisiblePosition; val offset=list.getChildAt(0)?.top ?: 0
  mode=value; ui.edit().putInt("mode",mode).commit(); buildBody()
  list.post { if(rows.isNotEmpty()) list.setSelectionFromTop(position.coerceIn(0,rows.lastIndex),offset) }
 }
 private fun buildBody() {
  refreshRows()
  (left.parent as? ViewGroup)?.removeView(left); body.removeAllViews()
  val portrait=resources.displayMetrics.heightPixels>resources.displayMetrics.widthPixels
  body.orientation=if(portrait)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
  val playerFrame=FrameLayout(this).apply { setPadding(dp(20),dp(16),dp(20),dp(12)) }
  val right=LinearLayout(this).apply {
   orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(20),dp(24),dp(20)); background=bg(AutomotiveDesign.SURFACE,24)
  }
  playerFrame.addView(right,FrameLayout.LayoutParams(-1,-1))
  if(portrait) {
   body.addView(playerFrame,LinearLayout.LayoutParams(-1,if(mode==2)0 else dp(300),if(mode==2)1f else 0f))
   if(mode!=2)body.addView(left,LinearLayout.LayoutParams(-1,0,1f))
  } else {
   if(mode!=2)body.addView(left,LinearLayout.LayoutParams(0,-1,.60f))
   body.addView(playerFrame,LinearLayout.LayoutParams(0,-1,if(mode==2)1f else .40f))
  }
  val artworkControls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  playerFrame.addView(artworkControls,FrameLayout.LayoutParams(dp(56),-2,Gravity.TOP or Gravity.END).apply { topMargin=dp(16); marginEnd=dp(20) })
  fun columnIcon(res:Int,description:String,action:()->Unit)=icon(res,description,action).apply { setPadding(dp(14),dp(14),dp(14),dp(14)) }
  artworkControls.addView(columnIcon(if(mode==2)R.drawable.ic_view_restore else R.drawable.ic_view_expand,if(mode==2)"Restore playlist and player" else "Expand player") { setMode(if(mode==2)0 else 2) },LinearLayout.LayoutParams(dp(56),dp(56)))
  trackArtToggle=columnIcon(R.drawable.ic_track_art_hide,"Hide track art") {
   ui.edit().putBoolean("hide_track_art",!ui.getBoolean("hide_track_art",false)).apply(); renderPlayer()
  }
  artworkControls.addView(trackArtToggle,LinearLayout.LayoutParams(dp(56),dp(56)).apply { topMargin=dp(8) })
  artworkOverlay=FrameLayout(this)
  artworkProgress=ArtworkLoadingView(this).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO; scaleType=ImageView.ScaleType.FIT_CENTER }
  artworkOverlay.addView(artworkProgress,FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER))
  artworkOverlay.background=RippleDrawable(ColorStateList.valueOf(0x25FFFFFF),bg(Color.TRANSPARENT,36),null)
  artworkOverlay.setOnClickListener { refreshArtwork() }; artworkOverlay.isFocusable=true
  artworkOverlay.contentDescription="Try a different artwork image"
  artworkControls.addView(artworkOverlay,LinearLayout.LayoutParams(dp(56),dp(56)).apply { topMargin=dp(8) })
  detail=LinearLayout(this).apply { orientation=if(portrait && mode==0 || !portrait && mode==2)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL }
  // Keep all artwork and text clear of the utility column; center stacked art.
  detail.setPadding(if(portrait && mode==2)dp(64) else 0,0,dp(64),0)
  right.addView(detail,LinearLayout.LayoutParams(-1,-1))
  cover=image().apply { contentDescription="Current artwork" }
  val artworkFrame=FrameLayout(this); artworkFrame.addView(cover,FrameLayout.LayoutParams(-1,-1))
  val preferredSize=when {
   portrait && mode==0 -> dp(224).coerceAtMost(resources.displayMetrics.widthPixels/3)
   portrait -> dp(400).coerceAtMost(resources.displayMetrics.widthPixels-dp(120)).coerceAtMost((resources.displayMetrics.heightPixels*.47f).toInt())
   mode==2 -> (resources.displayMetrics.widthPixels*.43f).toInt().coerceAtMost(resources.displayMetrics.heightPixels-dp(180))
   else -> (resources.displayMetrics.widthPixels*.30f).toInt().coerceAtMost(dp(230))
  }.coerceAtLeast(dp(80))
  val playerWidth=if(portrait || mode==2)resources.displayMetrics.widthPixels else (resources.displayMetrics.widthPixels*.40f).toInt()
  val size=if(detail.orientation==LinearLayout.VERTICAL)preferredSize.coerceAtMost((playerWidth-dp(if(portrait && mode==2)216 else 152)).coerceAtLeast(dp(80))) else preferredSize
  detail.addView(artworkFrame,LinearLayout.LayoutParams(size,size).apply { gravity=Gravity.CENTER_HORIZONTAL; if(detail.orientation==LinearLayout.HORIZONTAL)marginEnd=dp(28) else bottomMargin=dp(16) })
  val captions=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=if(portrait && mode==2)Gravity.CENTER else Gravity.CENTER_VERTICAL }
  detail.addView(captions,if(detail.orientation==LinearLayout.HORIZONTAL)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
  trackView=label(if(mode==2)36f else 28f).apply { maxLines=2; setTypeface(null,Typeface.BOLD) }
  artistView=label(if(mode==2)27f else 23f)
  stationView=label(if(mode==2)20f else 18f,muted)
  addPlaylistButton=pill("Add a playlist",selected=true) { management.addPlaylist() }.apply {
   textSize=AutomotiveDesign.BUTTON_TEXT_SP
   typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
   setTextColor(AppearanceTheme.current(this@ImpostorPlayerActivity).onAccent)
   contentDescription="Add a playlist"
  }
  captions.addView(addPlaylistButton,LinearLayout.LayoutParams(dp(240),dp(56)).apply { gravity=if(portrait && mode==2)Gravity.CENTER_HORIZONTAL else Gravity.START })
  captions.addView(trackView); captions.addView(artistView); captions.addView(stationView,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
  if(portrait && mode==2)listOf(trackView,artistView,stationView).forEach { it.gravity=Gravity.CENTER }
  renderPlayer()
 }
 private fun refreshRows(force:Boolean=false) {
  val updated=when {
   browseParent==RadioService.NODE_FAVOURITES -> repo.favourites()
   else -> repo.all()
  }
  val changed=rows!=updated
  rows=updated
  if(changed || force) adapter.notifyDataSetChanged()
  tabs.removeAllViews()
  fun tab(name:String,parent:String,weight:Float=1f) {
   tabs.addView(pill(name,browseParent==parent) {
    browseParent=parent; prefs.activeBrowseParent=parent; ui.edit().putString("browse",parent).commit(); refreshRows(); if(rows.isNotEmpty()) list.setSelection(0)
   },LinearLayout.LayoutParams(0,-1,weight).apply { marginEnd=dp(5) })
  }
  tab("All stations (${repo.all().size})",RadioService.NODE_ALL,1.45f)
  tab("Favorites",RadioService.NODE_FAVOURITES)
  tabs.addView(pill("Playlists") { management.playlists() },LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=dp(5) })
 }
 private fun showMenu(caption:String,choices:List<Pair<String,()->Unit>>) {
   val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(16)); background=bg(AutomotiveDesign.SURFACE,24) }
  if(caption.isNotBlank()) column.addView(label(22f).apply { text=caption; setTypeface(null,Typeface.BOLD); setPadding(0,dp(8),0,dp(12)) })
  val dialog=android.app.Dialog(this); dialog.setContentView(column)
  choices.forEach { (name,action) -> column.addView(pill(name) { dialog.dismiss(); action() },LinearLayout.LayoutParams(-1,dp(60)).apply { topMargin=dp(6) }) }
  dialog.window?.setBackgroundDrawable(bg(Color.TRANSPARENT)); dialog.show(); dialog.window?.setLayout(dp(420),-2)
 }
 private fun select(station:Station,parent:String=browseParent) {
  controller?.let { c -> c.setMediaItem(MediaItem.Builder().setMediaId(Station.contextualMediaId(station.mediaId,parent)).build()); c.prepare(); c.play() }
 }
 private fun currentStation():Station? = controller?.currentMediaItem?.mediaId?.let(Station::idFromMediaId)?.let(repo::byId) ?: repo.resumeCandidate()
 private fun renderPlayer() {
  if(!::cover.isInitialized) return
  val c=controller; val station=currentStation(); val metadata=c?.mediaMetadata
  val track=metadata?.title?.toString().orEmpty().takeUnless { it==station?.name }.orEmpty()
  val empty=station==null && repo.all().isEmpty() && repo.favourites().isEmpty()
  addPlaylistButton.visibility=if(empty)View.VISIBLE else View.GONE
  trackView.visibility=if(empty)View.GONE else View.VISIBLE
  artistView.visibility=if(empty)View.GONE else View.VISIBLE
  stationView.visibility=if(empty)View.GONE else View.VISIBLE
  trackView.text=track.ifBlank { station?.name.orEmpty() }
  val connection=c?.let { PlayerConnectionLabel.text(PlaybackStatusBus.status.value,it.playWhenReady,it.isPlaying) }
  val artist=if(track.isNotBlank())metadata?.artist?.toString().orEmpty() else ""
  artistView.text=listOf(artist,connection.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
  renderMetadataFooter()
  stationView.text=listOfNotNull(station?.name,PlaybackStatusBus.quality.value?.compactLabel()?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
  val playing=c?.playWhenReady==true
  playPause.setImageResource(if(playing)R.drawable.ic_transport_pause else R.drawable.ic_transport_play); playPause.contentDescription=if(playing)"Pause" else "Play"
  shuffle.setColorFilter(if(prefs.randomStationOrder)coral else Color.WHITE)
  shuffleDot.visibility=if(prefs.randomStationOrder)View.VISIBLE else View.INVISIBLE
  shuffle.contentDescription=if(prefs.randomStationOrder)"Disable random station order" else "Enable random station order"
  val saved=station?.id in prefs.favourites
  heart.setImageResource(if(saved)R.drawable.ic_star_filled else R.drawable.ic_star_outline); heart.setColorFilter(if(saved)coral else Color.WHITE)
  heart.contentDescription=if(saved)"Remove station from Favorites" else "Add station to Favorites"
  renderArtworkLoading()
  val trackArtHidden=ui.getBoolean("hide_track_art",false)
  trackArtToggle.setImageResource(if(trackArtHidden)R.drawable.ic_station_image else R.drawable.ic_track_art_hide)
  trackArtToggle.contentDescription=if(trackArtHidden)"Show track art" else "Hide track art"
  trackArtToggle.isSelected=trackArtHidden
  ArtworkLoader.into(lifecycleScope,metadata?.artworkUri?.takeUnless { ui.getBoolean("hide_track_art",false) },station?.let { art.logoDisplayUri(it) },R.drawable.logo_placeholder,cover,animate=prefs.artworkTransitionsEnabled,slideDirection=direction)
 }
 private fun stationArtworkLoading(station:Station):Boolean =
  prefs.onlineArtworkEnabled && station.id in StationArtworkStore.loading.value
 private fun renderMetadataFooter() {
  if(!::metadataFooter.isInitialized) return
  val message=PlaybackStatusBus.missingTrackInfoText(SystemClock.elapsedRealtime())
   .takeIf { controller?.isPlaying==true }
  metadataFooter.text=message.orEmpty()
  metadataFooter.visibility=if(message!=null)View.VISIBLE else View.INVISIBLE
 }
 private fun renderArtworkLoading() {
  if(!::artworkProgress.isInitialized) return
  val station=currentStation()
  val trackLoading=!ui.getBoolean("hide_track_art",false) && prefs.onlineArtworkEnabled && PlaybackStatusBus.trackArtworkLoading.value &&
   station?.id==PlaybackStatusBus.stationId.value
  val stationLoading=station?.let(::stationArtworkLoading)==true
  val busy=trackLoading || stationLoading
  artworkProgress.visibility=View.VISIBLE
  artworkProgress.setLoading(busy)
  artworkOverlay.visibility=if(station!=null)View.VISIBLE else View.GONE
  artworkOverlay.isEnabled=station!=null && prefs.onlineArtworkEnabled && !busy
  artworkProgress.alpha=if(prefs.onlineArtworkEnabled)1f else .35f
 }
 private fun refreshArtwork() {
  val station=currentStation() ?: return
  if(!prefs.onlineArtworkEnabled) return
  val c=controller
  val trackUri=PlaybackStatusBus.coverArtUrl.value
  if(!ui.getBoolean("hide_track_art",false) && trackUri!=null && c?.mediaMetadata?.artworkUri?.toString()==trackUri) {
   c.sendCustomCommand(RadioService.CMD_REFRESH_ARTWORK,Bundle().apply { putString("stationId",station.id) })
  } else lifecycleScope.launch {
   val result=StationArtworkStore.tryNext(this@ImpostorPlayerActivity,station)
   Toast.makeText(this@ImpostorPlayerActivity,result,Toast.LENGTH_SHORT).show()
   adapter.notifyDataSetChanged(); renderPlayer()
  }
 }
 override fun onStart() {
  super.onStart(); if(::artworkProgress.isInitialized) artworkProgress.resumeForActivity(); val current=++generation
  footerHandler.removeCallbacks(footerTick); footerHandler.post(footerTick)
  val future=MediaController.Builder(this,SessionToken(this,ComponentName(this,RadioService::class.java))).buildAsync()
  future.addListener({ runOnUiThread {
   val c=runCatching { future.get() }.getOrNull() ?: return@runOnUiThread
   if(current!=generation) { c.release(); return@runOnUiThread }; controller=c
   c.addListener(object:Player.Listener {
    override fun onMediaMetadataChanged(metadata:MediaMetadata) { renderPlayer(); adapter.notifyDataSetChanged() }
    override fun onIsPlayingChanged(value:Boolean) { renderPlayer() }
    override fun onPlayWhenReadyChanged(value:Boolean,reason:Int) { renderPlayer() }
    override fun onPlaybackStateChanged(value:Int) { renderPlayer() }
    override fun onPlaybackSuppressionReasonChanged(value:Int) { renderPlayer() }
   }); renderPlayer(); adapter.notifyDataSetChanged()
  } },MoreExecutors.directExecutor())
 }
 override fun onBackPressed() { if(!management.handleBack())super.onBackPressed() }
 override fun onDestroy() { if(::management.isInitialized)management.close(); super.onDestroy() }
 override fun onStop() { footerHandler.removeCallbacks(footerTick); if(::artworkProgress.isInitialized) artworkProgress.stopForActivity(); generation++; controller?.release(); controller=null; super.onStop() }
 private inner class Stations:BaseAdapter() {
  override fun getCount()=rows.size
  override fun getItem(position:Int)=rows.getOrNull(position)
  override fun getItemId(position:Int)=rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: AdapterView.INVALID_ROW_ID
  override fun hasStableIds()=true
  override fun getView(position:Int,recycled:View?,parentView:ViewGroup):View {
   val station=rows.getOrNull(position) ?: return View(this@ImpostorPlayerActivity).apply { layoutParams=AbsListView.LayoutParams(-1,0) }
   val row=recycled as? LinearLayout ?: LinearLayout(this@ImpostorPlayerActivity).apply {
    gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),dp(8),dp(6),dp(8)); layoutParams=AbsListView.LayoutParams(-1,dp(103))
    addView(FrameLayout(context).apply {
     addView(image(),FrameLayout.LayoutParams(-1,-1))
     addView(ArtworkLoadingView(context).apply {
      contentDescription="Loading station artwork"
      visibility=View.GONE
     },FrameLayout.LayoutParams(dp(20),dp(20),Gravity.BOTTOM or Gravity.END).apply { setMargins(0,0,dp(5),dp(5)) })
    },LinearLayout.LayoutParams(dp(64),dp(64)).apply { marginEnd=dp(10) })
    addView(LinearLayout(context).apply {
     orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL
     addView(label(22f).apply { setTypeface(null,Typeface.BOLD) }); addView(label(16f,muted))
    },LinearLayout.LayoutParams(0,-1,1f))
    listOf(R.drawable.ic_move_up,R.drawable.ic_move_down,R.drawable.ic_star_outline,R.drawable.ic_station_image).forEach { resource ->
     addView(icon(resource,"") {}.apply { setPadding(dp(11),dp(11),dp(11),dp(11)) },LinearLayout.LayoutParams(dp(48),dp(64)))
    }
   }
   val selected=controller?.currentMediaItem?.mediaId?.let(Station::idFromMediaId)==station.id
    row.background=bg(if(selected)AutomotiveDesign.CONTROL else Color.TRANSPARENT,16); row.setOnClickListener { select(station) }
   val captions=row.getChildAt(1) as LinearLayout; (captions.getChildAt(0) as TextView).text=station.name
   (captions.getChildAt(1) as TextView).text=if(repo.isDetachedFavorite(station.id)) "Not in current playlists" else if(selected)listOfNotNull(controller?.mediaMetadata?.artist,controller?.mediaMetadata?.title?.takeUnless { it.toString()==station.name }).joinToString(" · ").ifBlank { StationText.subtitle(station) } else StationText.subtitle(station)
   listOf(-1,1).forEachIndexed { actionIndex,delta ->
    (row.getChildAt(2+actionIndex) as ImageButton).apply {
     contentDescription="Move ${station.name} ${if(delta<0)"up" else "down"}"
     isEnabled=position+delta in rows.indices; alpha=if(isEnabled)1f else 0.2f
     setOnClickListener {
      if(position+delta in rows.indices) {
       val first=list.firstVisiblePosition; val offset=list.getChildAt(0)?.top ?: 0
       repo.moveStation(station.id,delta,browseParent==RadioService.NODE_FAVOURITES); refreshRows()
       if(rows.isNotEmpty()) list.setSelectionFromTop(first.coerceIn(0,rows.lastIndex),offset)
      }
     }
    }
   }
   val saved=station.id in prefs.favourites
   (row.getChildAt(4) as ImageButton).apply {
    visibility=View.VISIBLE
    setImageResource(if(saved)R.drawable.ic_star_filled else R.drawable.ic_star_outline)
    setColorFilter(if(saved)coral else Color.WHITE)
    contentDescription=if(saved)"Remove ${station.name} from Favorites" else "Add ${station.name} to Favorites"
    setOnClickListener {
     val added=prefs.toggleFavourite(station.id)
     setImageResource(if(added)R.drawable.ic_star_filled else R.drawable.ic_star_outline)
     setColorFilter(if(added)coral else Color.WHITE)
     contentDescription=if(added)"Remove ${station.name} from Favorites" else "Add ${station.name} to Favorites"
     repo.favourites() // The shared flow refreshes both views once.
    }
   }
   (row.getChildAt(5) as ImageButton).apply {
    contentDescription="Change ${station.name} station image"
    setOnClickListener { StationImageDialog.show(this@ImpostorPlayerActivity,station,lifecycleScope,changed={ adapter.notifyDataSetChanged(); renderPlayer() },onOpenSettings={ management.settings() }) }
   }
   val rowArtwork=row.getChildAt(0) as FrameLayout
   rowArtwork.getChildAt(1).visibility=if(stationArtworkLoading(station))View.VISIBLE else View.GONE
   ArtworkLoader.into(lifecycleScope,null,art.logoDisplayUri(station),R.drawable.logo_placeholder,rowArtwork.getChildAt(0) as ImageView)
   return row
  }
 }
}





