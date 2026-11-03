package net.mspanc.twinsenradio.playback

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArtworkWorkSchedulerTest {
    @Test fun blockingRequestsStayOffCallerAndNeverFanOut() = runBlocking {
        val initialized = AtomicInteger()
        val worker = artworkWorker("test-artwork") { initialized.incrementAndGet() }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val caller = Thread.currentThread()
        try {
            (1..20).map {
                async(worker) {
                    assertNotSame(caller, Thread.currentThread())
                    assertTrue(Thread.currentThread().name.startsWith("test-artwork"))
                    val count = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, count) }
                    try { Thread.sleep(2) } finally { active.decrementAndGet() }
                }
            }.awaitAll()
            assertEquals(1, peak.get())
            assertEquals(1, initialized.get())
        } finally { worker.close() }
    }

    @Test fun currentTrackWorkDoesNotWaitForBlockedStationImage() = runBlocking {
        val station = artworkWorker("test-station")
        val track = artworkWorker("test-track")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val blocked = async(station) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val currentTrack = async(track) { "current track ready" }
            assertEquals("current track ready", withTimeout(2_000) { currentTrack.await() })
            release.countDown()
            blocked.await()
        } finally { release.countDown(); station.close(); track.close() }
    }

    @Test fun cancelledQueuedSearchDoesNotStartAnotherDownload() = runBlocking {
        val worker = artworkWorker("test-artwork")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val blocked = async(worker) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val obsolete = launch(worker) { calls.incrementAndGet() }
            obsolete.cancel()
            release.countDown()
            blocked.await()
            obsolete.join()
            assertEquals(0, calls.get())
        } finally { release.countDown(); worker.close() }
    }

    @Test fun candidatePreviewSamplingBoundsBothDimensionsWithoutChangingSavedSize() {
        for ((width, height) in listOf(1024 to 1024, 1023 to 97, 1 to 1024, 385 to 384)) {
            val sample = ArtworkAccessPolicy.sampleSize(width, height, 384)!!
            assertTrue((width - 1) / sample + 1 <= 384)
            assertTrue((height - 1) / sample + 1 <= 384)
        }
        assertEquals(1, ArtworkAccessPolicy.sampleSize(1024, 1024))
        assertNull(ArtworkAccessPolicy.sampleSize(1024, 1024, 0))
    }
}
