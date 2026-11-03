package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RNS's title API, answers copied from `/api/oZeta/stat` on 27.10.2026.
 */
class StationTitleApiTest {

    /** `title` is the artist and `artist` the track - the names are crossed. */
    @Test
    fun `the crossed fields come out as artist then track`() {
        val body = """{"status":true,"data":{"ts":1793118776,"title":"Ferris & Sylvester","artist":"Dark Side","source":"currenttxt"}}"""
        val title = StationTitleApi.rnsStreamTitle(body)
        assertEquals("Ferris & Sylvester - Dark Side", title)
        val now = NowPlaying.parse(title, stationName = "Radio Nowy Świat")!!
        assertEquals("Ferris & Sylvester", now.artist)
        assertEquals("Dark Side", now.songTitle)
        assertTrue(now.isRealSong)
    }

    /** Between tracks it names itself, which must still read as the slogan. */
    @Test
    fun `the station naming itself is the slogan`() {
        val body = """{"status":true,"data":{"ts":1793118900,"title":"Radio Nowy Świat","artist":"Pion i poziom!","source":"currenttxt"}}"""
        val now = NowPlaying.parse(StationTitleApi.rnsStreamTitle(body), stationName = "Radio Nowy Świat")!!
        assertTrue(now.isStationSelfTitle)
        assertEquals("Pion i poziom!", now.slogan)
        assertFalse(now.isRealSong)
    }

    @Test
    fun `a refusal or garbage says nothing`() {
        assertNull(StationTitleApi.rnsStreamTitle("""{"status":false}"""))
        assertNull(StationTitleApi.rnsStreamTitle("<!DOCTYPE html><title>Attention Required! | Cloudflare</title>"))
        assertNull(StationTitleApi.rnsStreamTitle("""{"status":true,"data":{"title":"","artist":""}}"""))
    }

    @Test
    fun `only stations with an API get one`() {
        assertTrue(StationTitleApi.forStation("rns") != null)
        assertNull(StationTitleApi.forStation("rmf"))
        assertNull(StationTitleApi.forStation(null))
    }

    /** What every RNS mount sends since the studio move. */
    @Test
    fun `the platform placeholder is no title`() {
        assertNull(NowPlaying.parse("Now Playing info goes here", stationName = "Radio Nowy Świat"))
    }
}
