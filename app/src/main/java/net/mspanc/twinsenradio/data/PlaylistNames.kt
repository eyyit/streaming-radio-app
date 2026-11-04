package net.mspanc.twinsenradio.data

import java.net.URI

/** Source display names that omit URL credentials, query, and fragment. */
object PlaylistNames {
    private const val MIDDLE_ELLIPSIS = "…"

    /**
     * Builds a name from the host and decoded URL path. Query parameters,
     * fragments, and user info are intentionally excluded.
     */
    fun fromUrl(url: String, fallback: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return fallback
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return fallback
        val safeHost = sanitize(host)
        val path = sanitize(uri.path.orEmpty().trim('/'))
        if (path.isBlank()) return boundedHost(safeHost)
        return boundedPath(safeHost, path)
    }

    /** Replaces blank or legacy host-only names while leaving custom labels untouched. */
    fun normalizeStoredName(name: String, url: String, fallback: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return fromUrl(url, fallback)

        val host = runCatching { URI(url.trim()).host }.getOrNull()
        return if (host != null && trimmed.equals(host, ignoreCase = true)) {
            fromUrl(url, fallback)
        } else {
            name
        }
    }

    private fun boundedHost(host: String): String =
        if (host.length <= CatalogLimits.MAX_NAME) host
        else host.take(CatalogLimits.MAX_NAME - 1) + MIDDLE_ELLIPSIS

    private fun boundedPath(host: String, path: String): String {
        val prefix = "$host / "
        val full = prefix + path
        if (full.length <= CatalogLimits.MAX_NAME) return full

        val remaining = CatalogLimits.MAX_NAME - prefix.length
        if (remaining <= 1) {
            val filename = path.substringAfterLast('/')
            val suffixBudget = minOf(filename.length, CatalogLimits.MAX_NAME - 2)
            val hostBudget = CatalogLimits.MAX_NAME - MIDDLE_ELLIPSIS.length - suffixBudget
            return host.take(hostBudget) + MIDDLE_ELLIPSIS + filename.takeLast(suffixBudget)
        }
        val pathBudget = remaining - MIDDLE_ELLIPSIS.length
        val filename = path.substringAfterLast('/')
        if (filename.length >= pathBudget) {
            return prefix + MIDDLE_ELLIPSIS + filename.takeLast(pathBudget)
        }

        val parentPath = path.dropLast(filename.length).trimEnd('/')
        val parentBudget = pathBudget - filename.length
        return prefix + parentPath.take(parentBudget) + MIDDLE_ELLIPSIS + filename
    }

    /** Replace decoded control characters before putting a URL path in UI text. */
    private fun sanitize(value: String): String = buildString(value.length) {
        value.forEach { append(if (it.isISOControl()) '\uFFFD' else it) }
    }
}
