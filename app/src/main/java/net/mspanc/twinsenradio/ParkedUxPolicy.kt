package net.mspanc.twinsenradio

/** Main-thread state for one live Car UX restriction subscription. Unknown is restricted. */
internal class ParkedUxPolicy(private val onRestricted: () -> Unit) {
    private var generation = 0L
    var canUseSettings = false
        private set

    fun beginSession(): Long {
        invalidate()
        return generation
    }

    fun invalidate() {
        generation++
        canUseSettings = false
        onRestricted()
    }

    fun update(session: Long, requiresDistractionOptimization: Boolean?) {
        if (session != generation) return
        canUseSettings = requiresDistractionOptimization == false
        if (!canUseSettings) onRestricted()
    }
}
