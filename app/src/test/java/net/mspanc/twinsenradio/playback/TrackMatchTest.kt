package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every pair below is a real question and a real answer, taken from the traces
 * of 09-11.09.2026. The two rejected ones are the two that actually went wrong.
 */
class TrackMatchTest {

    private fun accepts(qa: String, qt: String, ka: String?, kt: String?) =
        TrackMatch.plausible(qa, qt, ka, kt)

    // --- the faults this exists for -----------------------------------------

    /** The one that put a stranger's sleeve on the instrument cluster. */
    @Test
    fun `a different song sharing one word is not the song`() {
        assertFalse(accepts("Zalia", "Tylko kochaj mnie", "Krzysztof Zalewski", "Kochaj"))
    }

    /** The same fault a day earlier, which nobody spotted. */
    @Test
    fun `a different artist sharing one word is not the artist`() {
        assertFalse(accepts("Fast Boy", "Music Sounds", "Lee Brice", "Boy"))
    }

    @Test
    fun `an answer with no title is not an answer`() {
        assertFalse(accepts("Queen", "Another One Bites the Dust", "Queen", null))
        assertFalse(accepts("Queen", "Another One Bites the Dust", "Queen", ""))
    }

    // --- what has to keep working -------------------------------------------

    @Test
    fun `the plain case`() {
        assertTrue(accepts("Fleetwood Mac", "Everywhere", "Fleetwood Mac", "Everywhere"))
        assertTrue(accepts("Madonna", "Express Yourself", "Madonna", "Express Yourself"))
    }

    /** Jacaranda FM sends the title where the artist goes. */
    @Test
    fun `a swapped station still matches`() {
        assertTrue(accepts("POKER FACE", "LADY GAGA", "Lady Gaga", "Poker Face (Glam As You Radio Mix)"))
        assertTrue(
            accepts(
                "STAY", "THE KID LAROI [+] JUSTIN BIEBER",
                "The Kid LAROI & Justin Bieber", "STAY"
            )
        )
    }

    /** The catalogue adds a qualifier the station never sent. */
    @Test
    fun `a trailing qualifier is not a difference`() {
        assertTrue(accepts("Snap!", "Rhythm Is a Dancer", "Snap!", "Rhythm Is a Dancer (7\" Edit)"))
        assertTrue(
            accepts(
                "Janet Jackson / Luther Vandross", "The Best Things In Life Are Free",
                "Janet Jackson & Luther Vandross",
                "The Best Things In Life Are Free (feat. Ralph Tresvant & Bell Biv DeVoe)"
            )
        )
    }

    /** Stacked qualifiers, which is why the stripping loops. */
    @Test
    fun `two stacked qualifiers still come off`() {
        assertTrue(
            accepts(
                "Zbigniew Preisner", "Van den Budenmayer: Concerto en mi mineur (SBI 152 - Version de 1798)",
                "Zbigniew Preisner",
                "Van den Budenmayer : Concerto en mi mineur (SBI 152 - Version de 1798) [Remastered]"
            )
        )
    }

    /**
     * A near-miss title is allowed only once the artist agrees. This pair is
     * right; the Zalia pair above differs by exactly that guard.
     */
    @Test
    fun `a near title passes when the artist agrees`() {
        assertTrue(accepts("Boyzone", "A Picture Of You", "Boyzone", "Picture of You"))
    }

    @Test
    fun `separators between artists do not matter`() {
        assertTrue(
            accepts(
                "C-BooL / Giang Pham", "DJ Is Your Second Name",
                "C-BooL", "DJ Is Your Second Name (feat. Giang Pham)"
            )
        )
    }

    @Test
    fun `accents and case do not matter`() {
        assertTrue(accepts("Dawid Podsiadlo", "Na blysk", "Dawid Podsiadło", "NA BŁYSK"))
    }
}
