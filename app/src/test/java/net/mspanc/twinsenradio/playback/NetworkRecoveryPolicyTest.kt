package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test fun initialAndRepeatedHealthyCallbacksDoNotRestartStartup() {
        val policy = NetworkRecoveryPolicy(setOf("wifi"))
        assertFalse(policy.update("wifi", true))
        assertFalse(policy.update("wifi", true))
        assertFalse(policy.update("cell", true))
    }

    @Test fun networkLossThenValidationTriggersOneRecovery() {
        val policy = NetworkRecoveryPolicy(setOf("wifi"))
        assertFalse(policy.update("wifi", false))
        assertFalse(policy.update("wifi", false))
        assertTrue(policy.update("cell", true))
        assertFalse(policy.update("cell", true))
    }

    @Test fun initiallyOfflineRecoversOnFirstValidatedConnection() {
        val policy = NetworkRecoveryPolicy<String>(emptySet())
        assertFalse(policy.update("wifi", false))
        assertTrue(policy.update("wifi", true))
        assertFalse(policy.update("wifi", true))
    }

    @Test fun losingOneOfTwoUsableNetworksDoesNotRestartPlayback() {
        val policy = NetworkRecoveryPolicy(setOf("wifi", "cell"))
        assertFalse(policy.update("wifi", false))
        assertFalse(policy.update("wifi", true))
        assertFalse(policy.update("cell", false))
        assertFalse(policy.update("wifi", false))
        assertTrue(policy.update("wifi", true))
    }

    @Test fun restartingMonitorTakesNewBaseline() {
        val policy = NetworkRecoveryPolicy(setOf("wifi"))
        policy.update("wifi", false)
        policy.reset(setOf("cell"))
        assertFalse(policy.update("cell", true))
        policy.reset(emptySet())
        assertTrue(policy.update("wifi", true))
    }
}
