package net.mspanc.twinsenradio.transfer

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivateTransferStoreTest {
    @Test fun rejectsSeventeenthRecordWithoutDiscardingCredentials() {
        val context = RuntimeEnvironment.getApplication()
        val config = TransferConfig("a".repeat(39), "https://cap-test.firebaseio.com", "https://test.web.app")
        val store = PrivateTransferStore(context, config)
        repeat(16) { index ->
            store.put(StoredTransfer(index.toString(16).padStart(64, '0'), "secret", "owner",
                "token", "refresh", 100, 100, 300100))
        }
        assertThrows(TransferException::class.java) {
            store.put(StoredTransfer("f".repeat(64), "secret", "owner", "token", "refresh", 100, 100, 300100))
        }
        assertEquals(16, store.all().size)
        assertTrue(store.all().all { it.refreshToken == "refresh" })
        // Updating an existing credential at capacity still permits returning cleanup.
        val first = store.all().first()
        store.put(StoredTransfer(first.id, "secret", "owner", "renewed", "refresh", 200, 100, 300100))
        assertEquals(16, store.all().size)
        assertEquals("renewed", store.all().first { it.id == first.id }.idToken)
    }

    @Test fun credentialsSurviveStoreRecreationAndStayProjectBound() {
        val context = RuntimeEnvironment.getApplication()
        val config = TransferConfig("a".repeat(39), "https://store-test.firebaseio.com", "https://test.web.app")
        val original = PrivateTransferStore(context, config)
        val record = StoredTransfer("a".repeat(64), "b".repeat(64), "owner", "token", "refresh", 100, 100, 300100)
        original.put(record)
        val restored = PrivateTransferStore(context, config)
        assertEquals("refresh", restored.all().single().refreshToken)
        assertEquals(record.id, restored.all().single().id)
        val other = PrivateTransferStore(context, TransferConfig("a".repeat(39),
            "https://another-store-test.firebaseio.com", "https://test.web.app"))
        assertTrue(other.all().isEmpty())
        restored.remove(record.id)
        assertTrue(original.all().isEmpty())
        assertEquals("StoredTransfer(redacted)", record.toString())
    }
}
