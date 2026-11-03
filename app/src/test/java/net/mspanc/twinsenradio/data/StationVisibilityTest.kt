package net.mspanc.twinsenradio.data

import org.junit.Assert.*
import org.junit.Test

class StationVisibilityTest {
    private val jazz = Station("jazz", "Jazz", "Music", "https://radio/jazz",
        source = Station.Source.USER_M3U)
    private val news = Station("news", "News", "Talk", "https://radio/news",
        source = Station.Source.USER_M3U)

    private fun persisted(rules: List<StationExclusion>) =
        StationVisibility.decode(StationVisibility.encode(rules))

    @Test fun hideSurvivesReloadButKeepsSourceAndFavoriteMembership() {
        val rules = persisted(StationVisibility.exclude(emptyList(), jazz, false))
        val source = listOf(jazz, news)
        assertEquals(listOf(news), StationVisibility.visible(source, rules, all = true))
        assertEquals(source, StationVisibility.visible(source, rules, all = false))
        assertEquals(setOf("jazz"), StationVisibility.favoriteIds(setOf("jazz"), rules))
        assertEquals(listOf(jazz), PlaylistLibrary.favorites(
            StationVisibility.visible(source, rules, all = false), emptyList(), setOf("jazz")))
    }

    @Test fun removeExcludesEverySourceAndDetachedFavoriteAfterRefreshAndReload() {
        val rules = persisted(StationVisibility.exclude(emptyList(), jazz, true))
        val refreshed = PlaylistLibrary.aggregate(listOf(
            PlaylistSource("a", "https://a/list", "A") to listOf(jazz, news),
            PlaylistSource("b", "https://b/list", "B") to listOf(jazz.copy(name = "Jazz updated"))
        )).map { it.station }
        assertEquals(listOf(news), StationVisibility.visible(refreshed, rules, all = false))
        val ids = StationVisibility.favoriteIds(setOf("jazz", "news"), rules)
        assertEquals(setOf("news"), ids)
        assertEquals(emptyList<Station>(), PlaylistLibrary.favorites(emptyList(),
            StationVisibility.visible(listOf(jazz), rules, all = false), ids))
        assertEquals("Jazz", rules.single().station.name)
    }

    @Test fun restoreNeedsPresentExactSourceAndDoesNotRecreateFavorite() {
        val rules = persisted(StationVisibility.exclude(emptyList(), jazz, true))
        assertEquals(rules, StationVisibility.restore(rules, jazz.stream, setOf(news.stream)))
        val restored = persisted(StationVisibility.restore(rules, jazz.stream, setOf(jazz.stream)))
        assertTrue(restored.isEmpty())
        assertEquals(listOf(jazz), StationVisibility.visible(listOf(jazz), restored, all = true))
        val afterRemoval = StationVisibility.favoriteIds(setOf("jazz"), rules)
        assertTrue(StationVisibility.favoriteIds(afterRemoval, restored).isEmpty())
    }

    @Test fun hiddenPositionReturnsWithoutReorderingOtherStations() {
        val order = listOf(news, jazz)
        val rules = StationVisibility.exclude(emptyList(), news, false)
        assertEquals(listOf(jazz), StationVisibility.visible(order, rules, all = true))
        val restored = StationVisibility.restore(rules, news.stream, setOf(news.stream))
        assertEquals(order, StationVisibility.visible(order, restored, all = true))
    }

    @Test fun selectedVariantStaysExcludedButDifferentExactStreamCanBeAdded() {
        val rules = persisted(StationVisibility.exclude(emptyList(), jazz, true))
        val variant = jazz.copy(stream = "https://radio/jazz-low")
        val replacement = jazz.copy(id = "new-jazz", stream = "https://radio/new-jazz")
        assertEquals(listOf(replacement), StationVisibility.visible(listOf(variant, replacement), rules, all = false))
    }
}
