package net.mspanc.twinsenradio.playback

/** Exact verified broadcaster hosts; metadata is not permission to contact arbitrary servers. */
internal object StreamArtworkPolicy {
    // Nova primary-site image links verified 2026-11-05; new hosts need evidence/review.
    val allowedHosts: Set<String> = setOf("nova.fr", "www.nova.fr")
}
