package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Test

class CollectionQueueTest {
    @Test fun clickingSharedStationFromSourceKeepsExplicitFavoritesScope() {
        val scope = CollectionQueue.stationScope("/fav", "/playlist/source")
        assertEquals("/fav", scope)
        val collections = mapOf("/fav" to listOf("shared", "favorite"),
            "/playlist/source" to listOf("shared", "source-only"))
        assertEquals(listOf("shared", "favorite"),
            CollectionQueue.withSelected(collections.getValue(scope), "shared") { it })
    }

    @Test fun explicitlyClickedOutsideStationDoesNotBroadenSelectedCollection() {
        val scope = CollectionQueue.stationScope("/fav", "/all")
        assertEquals("/fav", scope)
        assertEquals(listOf("outside", "favorite"),
            CollectionQueue.withSelected(listOf("favorite"), "outside") { it })
    }

    @Test fun emptyChosenCollectionResumesOnlyCurrentStation() {
        assertEquals(listOf("current"),
            CollectionQueue.withSelected(emptyList(), "current") { it })
    }

    @Test fun preservesCurrentMemberInCollectionOrder() {
        assertEquals(1, CollectionQueue.startIndex(listOf("first", "current", "last"), "current"))
    }

    @Test fun differentCollectionStartsAtFirstMember() {
        assertEquals(0, CollectionQueue.startIndex(listOf("first", "last"), "outside"))
    }

    @Test fun emptyCollectionCannotSelectStation() {
        assertNull(CollectionQueue.startIndex(emptyList(), "current"))
    }

    @Test fun shuffledPreviousSkipsHistoryOutsideSelectedCollection() {
        val traversal = StationTraversal()
        traversal.observe("source-a")
        traversal.observe("source-b")
        traversal.observe("current")
        assertEquals("source-a", traversal.previous(setOf("source-a", "current"), "current"))
        assertNull(traversal.previous(setOf("source-a", "current"), "source-a"))
    }
}
