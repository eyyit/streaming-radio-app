package net.mspanc.twinsenradio.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom

/** Fixed project endpoints only; playlist values never become transfer requests. */
class TransferConfig(val apiKey: String, val databaseUrl: String, val hostingUrl: String) {
    init {
        require(apiKey.matches(Regex("[A-Za-z0-9_-]{20,200}")))
        val database = endpoint(databaseUrl)
        require(database.path.isNullOrEmpty() || database.path == "/")
        require(database.host.matches(Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(firebaseio\\.com|firebasedatabase\\.app)")))
        val hosting = endpoint(hostingUrl)
        require(hosting.path.isNullOrEmpty() || hosting.path == "/")
    }

    private fun endpoint(value: String): URI = URI(value).also {
        require(it.scheme == "https" && !it.host.isNullOrEmpty())
        require(it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null)
        require(it.port == -1 || it.port == 443)
    }

    override fun toString() = "TransferConfig(configured)"
}

enum class TransferKind { URL, FILE }
data class TransferPayload(val kind: TransferKind, val value: String, val name: String) {
    override fun toString() = "TransferPayload($kind, redacted)"
}

class TransferSession(val id: String, val claimSecret: String, val expiresAt: Long, val pairingUrl: String) {
    override fun toString() = "TransferSession(redacted)"
}

sealed class TransferPoll {
    data object Waiting : TransferPoll()
    data object Expired : TransferPoll()
    data class Submitted(val payload: TransferPayload) : TransferPoll()
}

class TransferException(message: String) : Exception(message)

/** Private persistence is required before sending a creation request. */
interface TransferStore {
    fun all(): List<StoredTransfer>
    fun put(value: StoredTransfer)
    fun remove(id: String)
}

class StoredTransfer(
    val id: String, val secret: String, val ownerUid: String,
    val idToken: String, val refreshToken: String, val tokenCreatedAt: Long,
    val createdAt: Long, val expiresAt: Long,
) {
    override fun toString() = "StoredTransfer(redacted)"
}

/** No playback dependency. Call only while the transfer UI is open or for known-ID cleanup. */
class PhoneTransferClient(
    private val config: TransferConfig,
    private val store: TransferStore,
    private val transport: TransferTransport = UrlConnectionTransferTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun createSession(): TransferSession {
        if (store.all().size >= MAX_PENDING_SESSIONS) {
            throw TransferException("Too many pending transfers; cleanup required")
        }
        val auth = request("POST", authUrl("accounts:signUp"),
            JSONObject().put("returnSecureToken", true).toString())
        val identity = json(auth)
        val created = now()
        val record = StoredTransfer(token(), token(), required(identity, "localId"),
            required(identity, "idToken"), required(identity, "refreshToken"), created,
            created, created + LIFETIME_MS)
        store.put(record)
        val body = JSONObject().put("ownerUid", record.ownerUid)
            .put("claimSecret", record.secret).put("createdAt", record.createdAt)
            .put("expiresAt", record.expiresAt).put("status", "waiting")
        request("PUT", databaseUrl("transfers/${record.id}", record.idToken), body.toString())
        return session(record)
    }

    suspend fun poll(session: TransferSession): TransferPoll {
        val record = find(session.id)
        if (now() >= record.expiresAt) return TransferPoll.Expired
        val response = request("GET", databaseUrl("transfers/${record.id}", record.idToken))
        if (now() >= record.expiresAt) return TransferPoll.Expired
        val root = json(response)
        if (root.optString("ownerUid") != record.ownerUid ||
            root.optString("claimSecret") != record.secret ||
            root.optLong("createdAt", -1) != record.createdAt ||
            root.optLong("expiresAt", -1) != record.expiresAt) {
            throw TransferException("Invalid transfer response")
        }
        return when (root.optString("status")) {
            "waiting" -> {
                if (root.has("payload")) throw TransferException("Invalid waiting transfer")
                TransferPoll.Waiting
            }
            "submitted" -> TransferPoll.Submitted(parsePayload(root.optJSONObject("payload")))
            else -> throw TransferException("Invalid transfer status")
        }
    }

    /** Cancellation disconnects the current request. Expiry never returns a payload. */
    suspend fun awaitPayload(session: TransferSession): TransferPoll {
        val remaining = (find(session.id).expiresAt - now()).coerceAtMost(LIFETIME_MS)
        if (remaining <= 0) return TransferPoll.Expired
        return try {
            withTimeout(remaining) {
                while (true) {
                    val result = poll(session)
                    if (result !is TransferPoll.Waiting) return@withTimeout result
                    delay(1_500)
                }
                @Suppress("UNREACHABLE_CODE")
                TransferPoll.Expired
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            // Only our deadline is converted; cancellation of the caller stays cancellation.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            TransferPoll.Expired
        }
    }

    /** Atomic removal also preserves claimed-phone authorization under the rules. */
    suspend fun close(session: TransferSession) = closeKnown(session.id)

    suspend fun cleanupKnownSessions(): Int {
        var failures = 0
        for (record in store.all()) {
            try { closeKnown(record.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: TransferException) { failures++ }
        }
        return failures
    }

    private suspend fun closeKnown(id: String) {
        var record = find(id)
        if (now() - record.tokenCreatedAt >= 50 * 60_000L) {
            val body = "grant_type=refresh_token&refresh_token=${encode(record.refreshToken)}"
            val refreshed = json(request("POST",
                "https://securetoken.googleapis.com/v1/token?key=${encode(config.apiKey)}",
                body, "application/x-www-form-urlencoded"))
            if (required(refreshed, "user_id") != record.ownerUid) {
                throw TransferException("Invalid refreshed identity")
            }
            record = StoredTransfer(record.id, record.secret, record.ownerUid,
                required(refreshed, "id_token"), required(refreshed, "refresh_token"), now(),
                record.createdAt, record.expiresAt)
            store.put(record)
        }
        val body = JSONObject().put("claims/$id", JSONObject.NULL)
            .put("transfers/$id", JSONObject.NULL)
        request("PATCH", databaseUrl("", record.idToken), body.toString())
        store.remove(id)
    }

    private fun session(record: StoredTransfer) = TransferSession(record.id, record.secret,
        record.expiresAt, config.hostingUrl.trimEnd('/') + "/#s=${record.id}&k=${record.secret}")

    private fun find(id: String): StoredTransfer {
        if (!id.matches(Regex("[0-9a-f]{64}"))) throw TransferException("Invalid session")
        return store.all().firstOrNull { it.id == id } ?: throw TransferException("Unknown session")
    }

    private fun token(): String = ByteArray(32).also(random::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun authUrl(action: String) =
        "https://identitytoolkit.googleapis.com/v1/$action?key=${encode(config.apiKey)}"

    private fun databaseUrl(path: String, token: String) =
        "${config.databaseUrl.trimEnd('/')}/$path.json?auth=${encode(token)}"

    private suspend fun request(method: String, url: String, body: String? = null,
        contentType: String = "application/json"): String =
        try { transport.request(method, url, body, contentType) }
        catch (cancelled: CancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                throw TransferException("Transfer request timed out")
            }
            throw cancelled
        }
        catch (_: Exception) { throw TransferException("Transfer service unavailable") }

    private fun json(value: String): JSONObject = try { JSONObject(value) }
        catch (_: Exception) { throw TransferException("Invalid transfer response") }

    private fun required(value: JSONObject, key: String): String =
        (value.opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 16_384 }
            ?: throw TransferException("Invalid transfer identity")

    companion object {
        const val LIFETIME_MS = 300_000L
        const val MAX_PENDING_SESSIONS = 16
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_URL_LENGTH = 4096
        const val MAX_NAME_LENGTH = 512

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        internal fun parsePayload(value: JSONObject?): TransferPayload {
            if (value == null || value.keys().asSequence().any { it !in setOf("kind", "value", "name") }) {
                throw TransferException("Invalid playlist transfer")
            }
            val text = value.opt("value") as? String
                ?: throw TransferException("Invalid playlist transfer")
            val name = if (!value.has("name")) "" else value.opt("name") as? String
                ?: throw TransferException("Invalid playlist name")
            if (name.length > MAX_NAME_LENGTH || name.any { it == '\u0000' }) {
                throw TransferException("Invalid playlist name")
            }
            val kind = when (value.optString("kind")) {
                "url" -> {
                    if (text.isBlank() || text.length > MAX_URL_LENGTH || text.any { it <= ' ' }) {
                        throw TransferException("Invalid playlist URL")
                    }
                    val uri = try { URI(text) } catch (_: Exception) { null }
                    if (uri == null || uri.scheme != "https" ||
                        uri.host.isNullOrEmpty() || uri.rawUserInfo != null) {
                        throw TransferException("Invalid playlist URL")
                    }
                    TransferKind.URL
                }
                "file" -> {
                    if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES ||
                        text.contains('\u0000') || !validUnicode(text)) {
                        throw TransferException("Invalid playlist file")
                    }
                    TransferKind.FILE
                }
                else -> throw TransferException("Invalid playlist kind")
            }
            return TransferPayload(kind, text, name)
        }

        private fun validUnicode(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                val char = text[index++]
                if (Character.isHighSurrogate(char)) {
                    if (index >= text.length || !Character.isLowSurrogate(text[index++])) return false
                } else if (Character.isLowSurrogate(char)) return false
            }
            return true
        }
    }
}
