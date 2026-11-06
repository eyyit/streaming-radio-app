package net.mspanc.twinsenradio.playback

/** Selecting a collection keeps its current member, otherwise starts at its first station. */
internal object CollectionQueue {
    /** A station route supplies a fallback only before a collection has been selected. */
    fun stationScope(active: String, clickedParent: String?): String =
        active.ifBlank { clickedParent ?: "/all" }

    fun <T> withSelected(members: List<T>, selected: T, key: (T) -> String): List<T> =
        if (members.any { key(it) == key(selected) }) members else listOf(selected) + members

    fun startIndex(ids: List<String>, current: String?): Int? =
        if (ids.isEmpty()) null else ids.indexOf(current).coerceAtLeast(0)
}
