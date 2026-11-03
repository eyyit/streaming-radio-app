package net.mspanc.twinsenradio.playback

import net.mspanc.twinsenradio.data.LineContent
import net.mspanc.twinsenradio.data.LineSet
import net.mspanc.twinsenradio.data.LineSpec
import net.mspanc.twinsenradio.data.Presentation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone's playback screen follows the dashboard's rules - over a record,
 * through the news, and while the presenter talks. These are the three cases
 * the phone used to get wrong on its own: the bare slogan over the news, and
 * nothing about the programme at all.
 */
class PhoneTextTest {

    private val rns = "Radio Nowy Swiat"

    private fun phone(
        now: NowPlaying?,
        info: CoverArtLookup.TrackInfo? = null,
        onAir: OnAir? = null,
        p: Presentation = Presentation.DEFAULT
    ) = MetadataFactory.forPhone(now, info, onAir, p, includeYear = true)

    @Test
    fun `a record gives title, artist and album`() {
        val now = NowPlaying.parse("Dawid Podsiadlo - Malomiasteczkowy", rns)
        val info = CoverArtLookup.TrackInfo(null, "Malomiasteczkowy", 2018, isSingle = false)
        assertEquals(
            PhoneText("Malomiasteczkowy", "Dawid Podsiadlo", "Malomiasteczkowy [2018]"),
            phone(now, info)
        )
    }

    @Test
    fun `the album the station tacks onto the artist is not shown twice`() {
        val now = NowPlaying.parse("Wiktoria Kida / Ksiega - Pustka", "RMF FM")
        val info = CoverArtLookup.TrackInfo(null, "Ksiega", null, isSingle = false)
        assertEquals("Wiktoria Kida", phone(now, info).artist)
    }

    @Test
    fun `a record switched off on the dashboard is switched off on the phone`() {
        val now = NowPlaying.parse("Dawid Podsiadlo - Malomiasteczkowy", rns)
        val info = CoverArtLookup.TrackInfo(null, "Malomiasteczkowy", 2018, isSingle = false)
        val p = Presentation.DEFAULT.copy(
            playing = LineSet(LineSpec(LineContent.ARTIST), LineSpec(LineContent.STATION), LineSpec(LineContent.TITLE))
        )
        assertEquals("", phone(now, info, p = p).album)
    }

    @Test
    fun `the news is called by name, as on the dashboard`() {
        val now = NowPlaying.parse("RMF FM - FAKTY", "RMF FM")
        assertEquals(PhoneText("FAKTY — serwis informacyjny", "", ""), phone(now))
    }

    @Test
    fun `while the presenter talks the programme and its hosts are shown`() {
        val now = NowPlaying.parse("$rns - Pion i poziom!", rns)
        val onAir = OnAir("Poranek", "Jan Kowalski", null)
        assertEquals(PhoneText("Poranek", "Jan Kowalski", ""), phone(now, onAir = onAir))
    }

    @Test
    fun `talk without a guide falls back to the slogan`() {
        val now = NowPlaying.parse("$rns - Pion i poziom!", rns)
        assertEquals(PhoneText("Pion i poziom!", "", ""), phone(now))
    }

    @Test
    fun `a talk with nothing said at all leaves the programme on its own`() {
        val onAir = OnAir("Poranek", "Jan Kowalski", null)
        assertEquals(PhoneText("Poranek", "Jan Kowalski", ""), phone(null, onAir = onAir))
    }

    @Test
    fun `an ad says so, with its length`() {
        val now = NowPlaying.parse("", "RMF FM", "StreamTitle='';adw_ad='true';durationMilliseconds='30000';")
        assertEquals("Reklama · 30s", phone(now).title)
    }

    @Test
    fun `a gap layout without programme or slogan says nothing about the gap`() {
        val now = NowPlaying.parse("$rns - Pion i poziom!", rns)
        val p = Presentation.DEFAULT.copy(
            idle = LineSet(LineSpec(LineContent.CLOCK), LineSpec(LineContent.STATION), LineSpec(LineContent.DATE))
        )
        assertEquals(PhoneText("", "", ""), phone(now, p = p))
    }
}
