package net.mspanc.twinsenradio.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real repository/HTTP/storage behavior; no player or user-library singleton. */
object PlaylistRefreshCancellationRegression {
    suspend fun run(base: Context) {
        check(net.mspanc.twinsenradio.BuildConfig.DEBUG)
        val prefix = "refresh_cancel_${UUID.randomUUID()}"
        val directory = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolated = "$prefix-$name"
                preferenceNames += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val phase = AtomicInteger()
        val aRequests = AtomicInteger()
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "playlist-cancellation-fixture") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1]
                    while (!input.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                    val current = phase.get()
                    val a = path == "/a.m3u"
                    if (a) aRequests.incrementAndGet()
                    if (!a && current == 2) {
                        secondEntered.countDown()
                        check(releaseSecond.await(5, TimeUnit.SECONDS)) { "Cancellation release timed out" }
                    }
                    val status = if (!a && current == 1) "503 Unavailable" else "200 OK"
                    val name = (if (a) "A" else "B") + if (current >= 2) " new" else " old"
                    val body = "#EXTM3U\n#EXTINF:-1,$name\nhttps://radio.example/${if (a) "a" else "b"}\n"
                        .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                    }
                }
            } catch (failure: Throwable) {
                if (!server.isClosed) serverFailure.set(failure)
            }
        }
        try {
            val sources = org.json.JSONArray()
            listOf("a", "b").forEach { id -> sources.put(org.json.JSONObject()
                .put("id", id).put("name", id).put("url", "http://127.0.0.1:${server.localPort}/$id.m3u")) }
            check(context.getSharedPreferences("playlist_library", 0).edit()
                .putString("sources", sources.toString()).commit())
            // Bypass only the singleton, so this test owns its real preferences/files.
            val constructor = StationRepository::class.java.getDeclaredConstructor(Context::class.java)
            constructor.isAccessible = true
            val repo = constructor.newInstance(context)
            repo.refreshUserLists(force = true)
            check(repo.all().map { it.name }.toSet() == setOf("A old", "B old"))
            phase.set(1)
            repo.refreshUserLists(force = true)
            check(repo.lastRefreshFailureCount == 1) { "Failed-source prerequisite was not established" }
            phase.set(2)
            coroutineScope {
                val refresh = launch(Dispatchers.IO) { repo.refreshUserLists(force = true) }
                try {
                    check(secondEntered.await(5, TimeUnit.SECONDS)) { "Second source was not reached" }
                    refresh.cancel()
                } finally { releaseSecond.countDown() }
                refresh.join()
            }
            check(repo.all().map { it.name }.toSet() == setOf("A new", "B old")) {
                "Cancellation must publish the already-committed first source and retain the second"
            }
            check(repo.lastRefreshFailureCount == 1) { "Cancellation cleared an unprocessed source failure" }
            phase.set(3)
            val requestsBefore = aRequests.get()
            repo.refreshUserLists(force = false)
            check(aRequests.get() == requestsBefore) { "Fresh first source should not require another download" }
            check(repo.all().map { it.name }.toSet() == setOf("A new", "B new"))
            check(repo.lastRefreshFailureCount == 0)
            check(serverFailure.get() == null) { "Fixture server failed: ${serverFailure.get()}" }
        } finally {
            releaseSecond.countDown()
            server.close()
            worker.join(6000)
            check(!worker.isAlive) { "Fixture server did not stop" }
            preferenceNames.forEach {
                check(base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
                check(base.deleteSharedPreferences(it))
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.deleteRecursively())
        }
    }
}
