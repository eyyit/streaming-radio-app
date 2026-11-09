package net.mspanc.twinsenradio.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Dedicated app-private, project-bound storage; never store these credentials in library exports. */
class PrivateTransferStore(context: Context, config: TransferConfig) : TransferStore {
    private val project = MessageDigest.getInstance("SHA-256")
        .digest((config.databaseUrl.trimEnd('/') + "|" + config.apiKey).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val preferences = context.applicationContext.getSharedPreferences(
        "phone-transfer-$project", Context.MODE_PRIVATE)

    @Synchronized override fun all(): List<StoredTransfer> {
        val text = preferences.getString("sessions", null) ?: return emptyList()
        try {
            val array = JSONArray(text)
            if (array.length() > PhoneTransferClient.MAX_PENDING_SESSIONS) {
                throw TransferException("Too many saved transfers")
            }
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                StoredTransfer(item.getString("id"), item.getString("secret"),
                    item.getString("ownerUid"), item.getString("idToken"),
                    item.getString("refreshToken"), item.getLong("tokenCreatedAt"),
                    item.getLong("createdAt"), item.getLong("expiresAt"))
            }
        } catch (_: Exception) {
            // Keep the original bytes so damaged state is never silently discarded.
            throw TransferException("Saved transfer state unavailable")
        }
    }

    @Synchronized override fun put(value: StoredTransfer) {
        write(all().filter { it.id != value.id } + value)
    }

    @Synchronized override fun remove(id: String) { write(all().filter { it.id != id }) }

    private fun write(values: List<StoredTransfer>) {
        if (values.size > PhoneTransferClient.MAX_PENDING_SESSIONS) {
            throw TransferException("Too many pending transfers; cleanup required")
        }
        val array = JSONArray()
        values.forEach {
            array.put(JSONObject().put("id", it.id).put("secret", it.secret)
                .put("ownerUid", it.ownerUid).put("idToken", it.idToken)
                .put("refreshToken", it.refreshToken).put("tokenCreatedAt", it.tokenCreatedAt)
                .put("createdAt", it.createdAt).put("expiresAt", it.expiresAt))
        }
        if (!preferences.edit().putString("sessions", array.toString()).commit()) {
            throw TransferException("Unable to save transfer state")
        }
    }
}
