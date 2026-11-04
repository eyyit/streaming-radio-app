package net.mspanc.twinsenradio.automotive

import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.*

/** Native media-host preferences entry; shares the parked player settings. */
@androidx.media3.common.util.UnstableApi
class AutomotiveSettingsActivity:ComponentActivity() {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
 private lateinit var management:RadioManagement
 override fun onCreate(state:Bundle?) {
  super.onCreate(state)
  val host=FrameLayout(this).apply { setBackgroundColor(AutomotiveDesign.CANVAS) }
  setContentView(host,ViewGroup.LayoutParams(-1,-1))
  management=RadioManagement(this,scope,null,{},host)
  management.nativeSettings { finish() }
  onBackPressedDispatcher.addCallback(this) {
   if(!management.handleBack()) {
    isEnabled=false
    onBackPressedDispatcher.onBackPressed()
   }
  }
 }

 override fun onDestroy() {
  if(::management.isInitialized) management.close()
  scope.cancel()
  super.onDestroy()
 }
}
