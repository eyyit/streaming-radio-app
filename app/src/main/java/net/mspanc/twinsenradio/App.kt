package net.mspanc.twinsenradio

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.data.StationRepository

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.IS_AUTOMOTIVE) AutomotiveUxGuard.initialize(this)
        // The user's M3U lists are fetched in the background, so the first
        // entry into Android Auto doesn't wait on the network - the built-in
        // list is available immediately.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            StationRepository.get(this@App).refreshUserLists()
        }
    }
}
