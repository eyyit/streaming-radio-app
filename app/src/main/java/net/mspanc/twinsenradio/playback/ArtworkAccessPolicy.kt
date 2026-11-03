package net.mspanc.twinsenradio.playback

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

/** Pure validation shared by the exported provider and its regression tests. */
internal object ArtworkAccessPolicy {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_DIMENSION = 1024
    private val token = Regex("[a-f0-9]{64}")

    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun route(raw: String, authority: String): Pair<String, String>? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "content" || uri.rawAuthority != authority || uri.rawFragment != null) return null
        val pieces = uri.rawPath.split('/')
        if (pieces.size != 3 || pieces[0] != "") return null
        val kind = pieces[1]
        val id = pieces[2]
        val valid = when (kind) {
            "track" -> id.endsWith(".jpg") && token.matches(id.removeSuffix(".jpg"))
            "station", "custom", "muted", "logo", "icon" -> token.matches(id)
            else -> false
        }
        if (!valid) return null
        if (uri.rawQuery != null && (kind != "station" || !Regex("v=[0-9]{1,19}").matches(uri.rawQuery))) return null
        kind to id
    }.getOrNull()

    /** Canonical containment defeats traversal, sibling prefixes and symlink escapes. */
    fun allowedFile(file: File, roots: List<File>): File? = runCatching {
        val canonical = file.canonicalFile
        if (!canonical.isFile || canonical.length() !in 1..MAX_BYTES.toLong()) return null
        if (canonical.extension.lowercase() !in setOf("png", "jpg", "jpeg")) return null
        canonical.takeIf { candidate -> roots.any { root ->
            val base = root.canonicalFile.toPath()
            candidate.toPath() != base && candidate.toPath().startsWith(base)
        } }
    }.getOrNull()

    fun httpsSource(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawFragment == null && raw.length <= 4096
    }.getOrDefault(false)

    fun sampleSize(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Int? {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return null
        var sample = 1
        while ((width - 1) / sample + 1 > maxDimension ||
            (height - 1) / sample + 1 > maxDimension) sample *= 2
        return sample
    }

    fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_BYTES) return null
            output.write(buffer, 0, count)
        }
    }
}
