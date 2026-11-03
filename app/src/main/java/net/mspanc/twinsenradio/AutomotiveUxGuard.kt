package net.mspanc.twinsenradio

import android.app.Activity
import android.app.Application
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.util.Collections
import java.util.WeakHashMap

/** All app-owned screens are parked settings. Driving playback belongs to the car media host. */
object AutomotiveUxGuard : Application.ActivityLifecycleCallbacks {
    private const val TAG = "AutomotiveUxGuard"
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var session = 0L
    private var initialized = false
    private val activities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val policy = ParkedUxPolicy { closeRestrictedScreens() }
    val canUseSettings: Boolean get() = policy.canUseSettings

    fun initialize(app: Application) {
        if (initialized) return
        initialized = true
        app.registerActivityLifecycleCallbacks(this)
        try {
            car = Car.createCar(app, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connection, ready ->
                // Ignore queued callbacks from the old service after disconnect/reconnect.
                manager = null
                session = policy.beginSession()
                if (ready) {
                    try {
                        val current = connection.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                            as CarUxRestrictionsManager
                        // Reconnection can return the same manager; replace its old listener.
                        current.unregisterListener()
                        val listenerSession = session
                        current.registerListener { restrictions ->
                            policy.update(listenerSession, restrictions?.isRequiresDistractionOptimization)
                        }
                        manager = current
                        refreshRestrictions()
                    } catch (error: Exception) {
                        failClosed(error)
                    }
                }
            }
        } catch (error: Exception) {
            failClosed(error)
        }
    }

    private fun refreshRestrictions() {
        val current = manager ?: return policy.invalidate()
        try {
            policy.update(session, current.currentCarUxRestrictions?.isRequiresDistractionOptimization)
        } catch (error: Exception) {
            failClosed(error)
        }
    }

    private fun failClosed(error: Exception) {
        manager = null
        policy.invalidate()
        Log.w(TAG, "Parked screens blocked: UX restriction state unavailable", error)
    }

    private fun closeRestrictedScreens() {
        activities.toList().forEach { if (!it.isFinishing) it.finish() }
    }

    override fun onActivityResumed(activity: Activity) {
        // Do not trust a parked value cached before this activity opened.
        refreshRestrictions()
        if (!canUseSettings) {
            Toast.makeText(activity, "This screen is available only while parked", Toast.LENGTH_SHORT).show()
            activity.finish()
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) { activities.add(activity) }
    override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
