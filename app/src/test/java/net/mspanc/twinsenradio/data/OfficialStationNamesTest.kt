package net.mspanc.twinsenradio.data

import org.junit.Assert.*
import org.junit.Test

class OfficialStationNamesTest {
    private fun station(name: String, host: String) = Station("saved-id", name, "Soul", "https://$host/live")

    @Test fun correctsVerifiedLegacyNames() {
        listOf(
            Triple("Nova Vintage", "nova-vnt.ice.infomaniak.ch", "Nova Classics"),
            Triple("Nova Dance", "nova-dance.ice.infomaniak.ch", "Nova Danse"),
            Triple("Nova Nouva", "nova-nouvo.ice.infomaniak.ch", "Nouvo Nova"),
            Triple("Nova Night", "nova-ln.ice.infomaniak.ch", "Nova la nuit"),
            Triple("Nova Hip Hop", "nova-odn.ice.infomaniak.ch", "Nova Hip-Hop"),
            Triple("Nova Beach", "nova-plage.nova-infra.fr", "Nova la plage")
        ).forEach { (old, host, expected) -> assertEquals(expected, OfficialStationNames.correct(station(old, host)).name) }
    }
    @Test fun retainsIdentityStreamAndOtherFields() {
        val old = station("Nova Vintage", "nova-vnt.ice.infomaniak.ch")
        assertEquals(old.copy(name = "Nova Classics"), OfficialStationNames.correct(old))
    }
    @Test fun preservesCustomNames() {
        val old = station("My favorites", "nova-vnt.ice.infomaniak.ch")
        assertEquals(old, OfficialStationNames.correct(old))
    }
    @Test fun doesNotRenameOtherStationsWithSameLabel() {
        val old = station("Nova Vintage", "another.example")
        assertEquals(old, OfficialStationNames.correct(old))
        val misleading = station("Nova Vintage", "nova-vnt.ice.infomaniak.ch.example")
        assertEquals(misleading, OfficialStationNames.correct(misleading))
    }
    @Test fun malformedStreamIsUnchanged() {
        val old = station("Nova Vintage", "nova-vnt.ice.infomaniak.ch").copy(stream = "not a url")
        assertEquals(old, OfficialStationNames.correct(old))
    }
}
