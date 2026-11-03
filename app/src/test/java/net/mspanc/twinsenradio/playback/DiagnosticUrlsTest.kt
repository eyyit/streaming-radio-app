package net.mspanc.twinsenradio.playback

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticUrlsTest {
    private val signed = "https://listener:example-password@radio.example:8443/signed-path?access_token=example-token#example-fragment"

    @Test fun keepsOnlyEndpointForSignedAndCredentialUrls() {
        assertEquals("https://radio.example:8443/[redacted]", DiagnosticUrls.endpoint(signed))
        assertEquals("http://radio.example/[redacted]", DiagnosticUrls.endpoint("http://radio.example/live"))
        assertEquals("https://[2001:db8::1]:8443/[redacted]", DiagnosticUrls.endpoint("https://[2001:db8::1]:8443/private"))
    }

    @Test fun invalidAndNonNetworkAddressesFailClosed() {
        listOf("file:///data/private", "content://private/settings", "https://bad host/private",
            "https:///private", "https://radio.example:0/private", "https://radio.example/" + "a".repeat(8192))
            .forEach { assertEquals("[redacted-uri]", DiagnosticUrls.endpoint(it)) }
        assertEquals("[no-uri]", DiagnosticUrls.endpoint(null))
    }

    @Test fun removesEveryEmbeddedUrlWithoutRemovingUsefulStatusText() {
        assertEquals("failed https://radio.example:8443/[redacted] then http://backup.example/[redacted] status=403",
            DiagnosticUrls.text("failed $signed then http://backup.example/token-path?q=private status=403"))
        assertEquals("[redacted-uri]", DiagnosticUrls.text("https://radio.example/\nprivate"))
        assertFalse(DiagnosticUrls.text("{\"url\":\"${signed.replace("/", "\\/")}\"}").contains("example-token"))
    }

    @Test fun doesNotIncludeExceptionMessagesOrNestedCauseDetails() {
        val error = IOException("request $signed failed", IOException("example-password"))
        assertEquals("IOException", DiagnosticUrls.failure(error))
    }

    @Test fun centralTraceRedactionCoversNestedFieldsAndArrays() {
        val fields = JSONObject().put("url", signed).put("state", "BUFFERING")
            .put("nested", JSONObject().put("message", "requested $signed"))
            .put("items", JSONArray().put(signed).put(42))
        DiagnosticUrls.redact(fields)
        assertEquals("https://radio.example:8443/[redacted]", fields.getString("url"))
        assertEquals("BUFFERING", fields.getString("state"))
        assertEquals(42, fields.getJSONArray("items").getInt(1))
        listOf("listener", "example-password", "signed-path", "example-token", "example-fragment")
            .forEach { assertFalse(fields.toString().contains(it)) }
    }
}
