package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Jacaranda FM's "[+]" between collaborators, as it reaches the screen. */
class JoinedNamesTest {

    @Test
    fun `a plus in brackets becomes an ampersand`() {
        assertEquals("Beyonce & Jay Z", MetadataFactory.joinedNames("Beyonce [+] Jay Z"))
    }

    @Test
    fun `every one of several collaborators`() {
        assertEquals(
            "Pitbull & Ne-Yo & Afrojack & Nayer",
            MetadataFactory.joinedNames("Pitbull [+] Ne-Yo [+] Afrojack [+] Nayer")
        )
    }

    @Test
    fun `the artist line on screen has no brackets`() {
        val now = NowPlaying.parse("BEYONCE [+] JAY Z - CRAZY IN LOVE", "Jacaranda FM")!!
        assertEquals("Beyonce & Jay Z", MetadataFactory.displayArtist(now, null))
    }

    /** The raw text keeps "[+]" - the catalogue lookup splits names on it. */
    @Test
    fun `the parsed artist is left as the station sent it`() {
        val now = NowPlaying.parse("BEYONCE [+] JAY Z - CRAZY IN LOVE", "Jacaranda FM")!!
        assertEquals("BEYONCE [+] JAY Z", now.artist)
    }
}
