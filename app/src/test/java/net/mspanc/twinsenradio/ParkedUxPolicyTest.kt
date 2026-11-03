package net.mspanc.twinsenradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedUxPolicyTest {
    private var closed = 0
    private val policy = ParkedUxPolicy { closed++ }

    @Test fun startupAndSubscriptionWithoutSnapshotStayRestricted() {
        assertFalse(policy.canUseSettings)
        policy.beginSession()
        assertFalse(policy.canUseSettings)
        assertEquals(1, closed)
    }

    @Test fun onlyExplicitUnrestrictedSnapshotAllowsParkedScreens() {
        val session = policy.beginSession()
        policy.update(session, null)
        assertFalse(policy.canUseSettings)
        policy.update(session, true)
        assertFalse(policy.canUseSettings)
        policy.update(session, false)
        assertTrue(policy.canUseSettings)
    }

    @Test fun drivingRestrictionsCloseAlreadyOpenParkedScreens() {
        val session = policy.beginSession()
        policy.update(session, false)
        val beforeDriving = closed
        policy.update(session, true)
        assertFalse(policy.canUseSettings)
        assertEquals(beforeDriving + 1, closed)
    }

    @Test fun missingResumeSnapshotRevokesPreviouslySafeState() {
        val session = policy.beginSession()
        policy.update(session, false)
        val beforeRefresh = closed
        policy.update(session, null)
        assertFalse(policy.canUseSettings)
        assertEquals(beforeRefresh + 1, closed)
    }

    @Test fun disconnectOrReadFailureBlocksAndRejectsQueuedSafeCallback() {
        val oldSession = policy.beginSession()
        policy.update(oldSession, false)
        policy.invalidate()
        val afterFailure = closed
        policy.update(oldSession, false)
        assertFalse(policy.canUseSettings)
        assertEquals(afterFailure, closed)
    }

    @Test fun reconnectRequiresFreshSnapshotAndRejectsOldCallbacksInBothDirections() {
        val oldSession = policy.beginSession()
        policy.update(oldSession, false)
        val freshSession = policy.beginSession()
        policy.update(oldSession, false)
        assertFalse(policy.canUseSettings)
        policy.update(freshSession, false)
        assertTrue(policy.canUseSettings)
        policy.update(oldSession, true)
        assertTrue(policy.canUseSettings)
        policy.update(freshSession, true)
        assertFalse(policy.canUseSettings)
    }
}
