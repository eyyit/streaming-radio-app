package net.mspanc.twinsenradio.playback

/** Synthetic offline URLs matching the producer's hosts and currentTrack fields. */
internal object NovaProducerArtworkFixture {
    data class Asset(val sourceFields: List<String>, val url: String, val host: String)

    fun trackAssets(): List<Asset> {
        val stream = checkNotNull(
            NovaProducerArtworkFixture::class.java.getResourceAsStream(
                "/nova-track-artwork.tsv"
            )
        )
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val columns = line.split('\t')
                check(columns.size == 3) { "Invalid Nova artwork fixture row" }
                Asset(columns[0].split(','), columns[1], columns[2])
            }.toList()
        }
    }
}
