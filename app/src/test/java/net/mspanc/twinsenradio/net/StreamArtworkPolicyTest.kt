package net.mspanc.twinsenradio.net

import java.io.IOException
import net.mspanc.twinsenradio.playback.StreamArtworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StreamArtworkPolicyTest {
    private val hosts = StreamArtworkPolicy.allowedHosts

    @Test fun acceptsExactHttpsHostsAndDefaultPort() {
        for (url in listOf("https://nova.fr/a.jpg", "https://www.nova.fr:443/b.jpg",
            "https://www.lesinrocks.com/wp-content/uploads/cover.jpg")) {
            assertEquals(url, SafeHttp.checkedUrl(url, false, hosts).toString())
        }
    }

    @Test fun rejectsUntrustedInitialTargetsEvenInDebugHttpMode() {
        for (url in listOf("https://127.0.0.1/a", "https://[::1]/a",
            "https://192.168.1.2/a", "https://nova.fr.evil.example/a",
            "https://other.example/a", "https://nova.fr:8443/a", "http://nova.fr/a",
            "https://lesinrocks.com/a", "https://sub.www.lesinrocks.com/a",
            "https://www.lesinrocks.com.evil.example/a",
            "https://www.lesinrocks.com:8443/a", "http://www.lesinrocks.com/a",
            "https://user@www.lesinrocks.com/a", "https://user@nova.fr/a")) {
            reject { SafeHttp.checkedUrl(url, true, hosts) }
        }
    }

    @Test fun redirectsKeepTheSameBoundary() {
        val start = SafeHttp.checkedUrl("https://nova.fr/a", false, hosts)
        assertEquals("https://nova.fr/b", SafeHttp.redirectTarget(start, "/b", false, hosts).toString())
        assertEquals("www.nova.fr", SafeHttp.redirectTarget(start,
            "https://www.nova.fr/b", false, hosts).host)
        assertEquals("www.lesinrocks.com", SafeHttp.redirectTarget(start,
            "https://www.lesinrocks.com/b", false, hosts).host)
        for (target in listOf("https://127.0.0.1/a", "https://other.example/a",
            "https://nova.fr:8443/a", "http://nova.fr/a",
            "https://www.lesinrocks.com.evil.example/a",
            "https://www.lesinrocks.com:8443/a", "http://www.lesinrocks.com/a")) {
            reject { SafeHttp.redirectTarget(start, target, true, hosts) }
        }
    }

    @Test fun ordinaryCallerPolicyIsUnchanged() {
        assertEquals("127.0.0.1", SafeHttp.checkedUrl("http://127.0.0.1/a", true).host)
    }

    private fun reject(block: () -> Any) {
        try { block(); fail("Untrusted artwork URL accepted") }
        catch (_: IOException) { /* Expected boundary refusal before any connection. */ }
    }
}
