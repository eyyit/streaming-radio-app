package net.mspanc.twinsenradio.data

import java.net.URI
import java.util.Locale

/** Correct known legacy labels only after matching the broadcaster's endpoint.
 * Names verified against nova.fr/radios on 2026-11-02. No station is bundled here.
 */
object OfficialStationNames {
    private data class Correction(val name: String, val legacy: Set<String>)
    private val nova = mapOf(
        "nova-vnt.ice.infomaniak.ch" to Correction("Nova Classics", setOf("nova vintage")),
        "nova-dance.ice.infomaniak.ch" to Correction("Nova Danse", setOf("nova dance")),
        "nova-nouvo.ice.infomaniak.ch" to Correction("Nouvo Nova", setOf("nova nouva", "nova nouvo")),
        "nova-ln.ice.infomaniak.ch" to Correction("Nova la nuit", setOf("nova night")),
        "nova-odn.ice.infomaniak.ch" to Correction("Nova Hip-Hop", setOf("nova hip hop", "nova rap")),
        "nova-plage.nova-infra.fr" to Correction("Nova la plage", setOf("nova beach"))
    )

    fun correct(station: Station): Station {
        val host = runCatching { URI(station.stream).host?.lowercase(Locale.ROOT) }.getOrNull()
        val rule = nova[host] ?: return station
        if (station.name.trim().lowercase(Locale.ROOT) !in rule.legacy) return station
        return station.copy(name = rule.name)
    }
}
