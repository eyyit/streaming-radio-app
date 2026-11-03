package net.mspanc.twinsenradio.playback

/** Slice records before creating metadata/provider URIs for a native browse page. */
object BrowsePage {
    fun <T, R> map(items: List<T>, page: Int, pageSize: Int, convert: (T) -> R): List<R> {
        require(page >= 0 && pageSize > 0)
        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, to).map(convert)
    }
}
