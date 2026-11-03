package net.mspanc.twinsenradio.playback

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostics keep the endpoint, never credentials or signed URL paths/queries. */
internal object DiagnosticUrls {
    private val addresses = Regex("(?i)[a-z][a-z0-9+.-]*://[^\\s\"'<>]+")
    private val addressStart = Regex("(?i)^[a-z][a-z0-9+.-]*://")

    fun endpoint(raw: String?): String {
        if (raw == null) return "[no-uri]"
        if (raw.length > 8192) return "[redacted-uri]"
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (scheme !in setOf("http", "https") || host.isNullOrBlank() ||
                uri.port !in -1..65535 || uri.port == 0) return "[redacted-uri]"
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "$scheme://$host$port/[redacted]"
        }.getOrDefault("[redacted-uri]")
    }

    /** Messages/metadata may embed several URLs; invalid addresses fail closed. */
    fun text(value: String): String {
        val normalized = value.replace("\\/", "/")
        if (addressStart.containsMatchIn(normalized.trim())) return endpoint(normalized.trim())
        return addresses.replace(normalized) { endpoint(it.value) }
    }

    // Error messages and cause stacks may contain credentials without a URL prefix.
    fun failure(error: Throwable): String = error.javaClass.simpleName

    /** Apply centrally before trace serialization, including nested metadata fields. */
    fun redact(fields: JSONObject): JSONObject = fields.apply {
        keys().asSequence().toList().forEach { key -> put(key, redactValue(get(key))) }
    }

    private fun redactValue(value: Any?): Any? = when (value) {
        is String -> text(value)
        is JSONObject -> redact(value)
        is JSONArray -> value.apply {
            for (index in 0 until length()) put(index, redactValue(get(index)))
        }
        else -> value
    }
}
