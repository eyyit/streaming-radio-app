package net.mspanc.twinsenradio.phone

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import net.mspanc.twinsenradio.App
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.RadioService

@OptIn(UnstableApi::class)
class PhoneApp : App() {
    override fun handleRemoteMediaButton(event: android.view.KeyEvent): Boolean =
        PhoneCastRouting.get(this).mediaButton(event)

    override fun onCreate() {
        super.onCreate()
        PhoneCastRouting.get(this)
    }
}

/** The single owner survives activity navigation and output-picker background events. */
@UnstableApi
class PhoneCastRouting private constructor(private val context: Context) {
    val listeners = mutableSetOf<() -> Unit>()
    private var local: MediaController? = null
    private val repo = StationRepository.get(context)
    val controller = PhoneCastController(context, { local },
        { local?.currentMediaItem?.mediaId?.let(repo::byMediaId) },
        { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }, ::changed,
        { PhoneCastHandoffLease.connect(context) })
    private val native = PhoneCastNativeSession(context, { controller.isCasting() },
        { controller.isRemotePlaying() }, { controller.toggleRemotePlayback() })
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = controller.onLocalPlaybackChanged()
    }

    init {
        val pending = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, RadioService::class.java))).buildAsync()
        pending.addListener({
            try {
                local = pending.get().also { it.addListener(playerListener) }
                controller.onLocalPlaybackChanged()
            } catch (error: Exception) {
                android.util.Log.e("PhoneCastRouting", "Local session unavailable", error)
            }
        }, ContextCompat.getMainExecutor(context))
        controller.onStart()
    }

    private fun changed() {
        native.sync()
        listeners.toList().forEach { it() }
    }

    fun notification(): Notification = native.notification()
    internal fun displaySnapshot(): PhoneCastNativeSession.DisplaySnapshot? = native.displaySnapshot()
    fun action(value: String?) = native.action(value)
    fun mediaButton(event: android.view.KeyEvent): Boolean = native.mediaButton(event)

    companion object {
        private var instance: PhoneCastRouting? = null
        fun get(context: Context): PhoneCastRouting = instance ?: PhoneCastRouting(context.applicationContext)
            .also { instance = it }
    }
}

@UnstableApi
class PhoneCastPlaybackService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7601, PhoneCastRouting.get(this).notification())
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

@UnstableApi
class PhoneCastNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = PhoneCastRouting.get(context).action(intent.action)
}
