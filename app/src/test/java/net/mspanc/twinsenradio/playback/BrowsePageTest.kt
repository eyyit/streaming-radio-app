package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Test

class BrowsePageTest {
    @Test fun pageOfOneConvertsExactlyOneRecord() {
        val converted = mutableListOf<Int>()
        val result = BrowsePage.map((0 until 2_000).toList(), 731, 1) { converted += it; "item:$it" }
        assertEquals(listOf(731), converted)
        assertEquals(listOf("item:731"), result)
    }

    @Test fun handlesLastPageAndOverflowWithoutConvertingUnrequestedRecords() {
        val items = (0..4).toList()
        assertEquals(listOf(4), BrowsePage.map(items, 2, 2) { it })
        assertEquals(items, BrowsePage.map(items, 0, Int.MAX_VALUE) { it })
        assertTrue(BrowsePage.map(items, Int.MAX_VALUE, Int.MAX_VALUE) { fail("No conversion"); it }.isEmpty())
        assertTrue(BrowsePage.map(emptyList<Int>(), 0, 1) { fail("No conversion"); it }.isEmpty())
    }

    @Test fun rejectsInvalidPageBeforeConversion() {
        listOf(-1 to 1, 0 to 0, 0 to -1).forEach { (page, size) ->
            try {
                BrowsePage.map(listOf(1), page, size) { fail("No conversion"); it }
                fail("Expected invalid page")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
