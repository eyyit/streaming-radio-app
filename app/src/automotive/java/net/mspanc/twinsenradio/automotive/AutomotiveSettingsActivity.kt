package net.mspanc.twinsenradio.automotive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.*

/** Native media-host preferences entry; shares the parked player settings. */
@androidx.media3.common.util.UnstableApi
class AutomotiveSettingsActivity:Activity() {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
 override fun onCreate(state:Bundle?) {
  super.onCreate(state)
  RadioManagement(this,scope,{ _,_ -> startActivity(Intent(this,ImpostorPlayerActivity::class.java)) },{}).settings { finish() }
 }
 override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
