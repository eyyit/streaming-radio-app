package net.mspanc.twinsenradio.playback

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ArtworkSelectionTransactionTest {
    @Test fun failedPreferenceCommitRestoresImageAndInMemoryPreferences() {
        var image = "old"
        var preference = "old"
        assertFalse(ArtworkSelectionTransaction.run({}, { image = "new" }, {
            preference = "new"; false
        }, { image = "old" }, { preference = "old" }))
        assertEquals("old", image)
        assertEquals("old", preference)
    }

    @Test fun cancellationAfterPublicationRestoresThePriorChoice() {
        var image = "old"
        var preference = "old"
        var checks = 0
        try {
            ArtworkSelectionTransaction.run({
                if (++checks == 2) throw CancellationException()
            }, { image = "new" }, { preference = "new"; true },
                { image = "old" }, { preference = "old" })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("old", image)
        assertEquals("old", preference)
    }

    @Test fun cancellationAfterPreferenceCommitAlsoRollsBackBothRecords() {
        var image = "old"
        var preference = "old"
        var checks = 0
        try {
            ArtworkSelectionTransaction.run({
                if (++checks == 3) throw CancellationException()
            }, { image = "new" }, { preference = "new"; true },
                { image = "old" }, { preference = "old" })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("old", image)
        assertEquals("old", preference)
    }

    @Test fun successKeepsThePublishedImageAndPreferences() {
        var image = "old"
        var preference = "old"
        assertTrue(ArtworkSelectionTransaction.run({}, { image = "new" }, {
            preference = "new"; true
        }, { image = "old" }, { preference = "old" }))
        assertEquals("new", image)
        assertEquals("new", preference)
    }
}
