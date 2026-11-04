package net.mspanc.twinsenradio.automotive

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import kotlinx.coroutines.runBlocking
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.M3uParser
import net.mspanc.twinsenradio.data.PlaylistSource
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.RadioService
import net.mspanc.twinsenradio.playback.CoverArtLookup
import net.mspanc.twinsenradio.playback.TrackArtworkStore
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the installed service through its public Media3 browser/player contract. */
@UnstableApi
class PrototypeSmokeInstrumentation : Instrumentation() {
    private lateinit var browser: MediaBrowser
    private val checks = mutableListOf<String>()
    private var mode: String? = null
    private var expectedCount = 53

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "hosted"
        arguments?.getString("expectedCount")?.let { expectedCount = it.toInt() }
        start()
    }

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        runOnMainSync(task)
        return task.get(20, TimeUnit.SECONDS)
    }
    private fun waitFor(label: String, seconds: Int = 25, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (System.nanoTime() < deadline) {
            if (condition()) { checks += label; return }
            Thread.sleep(200)
        }
        val state = if (this::browser.isInitialized) main {
            "state=${browser.playbackState}, wanted=${browser.playWhenReady}, playing=${browser.isPlaying}, " +
                "suppression=${browser.playbackSuppressionReason}, position=${browser.currentPosition}, buffered=${browser.bufferedPosition}, error=${browser.playerError}"
        } else "not connected"
        error("Timed out: $label ($state)")
    }
    private fun checkThat(label: String, condition: Boolean) { check(condition) { label }; checks += label }
    private fun children(parent: String, page: Int = 0, count: Int = 100): List<MediaItem> {
        val future = main { browser.getChildren(parent, page, count, null) }
        val result = future.get(20, TimeUnit.SECONDS)
        check(result.resultCode == 0) { "Browse failed for $parent: ${result.resultCode}" }
        return result.value!!.toList()
    }
    private fun control(state: String) {
        val connection = URL("http://10.0.2.2:18765/control?state=$state").openConnection()
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.getInputStream().use { it.readBytes() }
    }
    private fun select(item: MediaItem) { main { browser.setMediaItem(item); browser.prepare(); browser.play() } }
    private fun openedStreams(): Int = org.json.JSONObject(
        URL("http://10.0.2.2:18765/stats").readText()).getInt("openedStreams")

    override fun onStart() {
        if (mode == "native-favorite-icon") {
            val result = runCatching { net.mspanc.twinsenradio.playback.NativeFavoriteIconRegression.run(this) }
            finish(if (result.isSuccess) ActivityResult.OK else ActivityResult.FAIL, Bundle().apply {
                putString("stream", result.exceptionOrNull()?.let(android.util.Log::getStackTraceString)
                    ?: "PASS: Native favorite custom action advertises a heart")
            })
            return
        }
        if (mode == "artwork-fallback") {
            val result = runCatching { net.mspanc.twinsenradio.playback.ArtworkFallbackRegression.run(this) }
            finish(if (result.isSuccess) ActivityResult.OK else ActivityResult.FAIL, Bundle().apply {
                putString("stream", result.exceptionOrNull()?.let(android.util.Log::getStackTraceString)
                    ?: "PASS: Missing track art falls back to content station logo")
            })
            return
        }
        if (mode == "refresh-cancellation") {
            val result = runCatching {
                main { net.mspanc.twinsenradio.playback.ReconnectPauseRegression.run(targetContext) }
                net.mspanc.twinsenradio.playback.BoundedArtworkRegression.run()
                runBlocking { net.mspanc.twinsenradio.data.PlaylistRefreshCancellationRegression.run(targetContext) }
            }
            finish(if (result.isSuccess) ActivityResult.OK else ActivityResult.FAIL, Bundle().apply {
                putString("stream", result.exceptionOrNull()?.let(android.util.Log::getStackTraceString)
                    ?: "PASS: Isolated playlist refresh cancellation and retry regression")
            })
            return
        }
        main { net.mspanc.twinsenradio.playback.ReconnectPauseRegression.run(targetContext) }
        val prefs = Prefs(targetContext)
        val oldUrls = prefs.userM3uUrls
        val oldPresets = prefs.favourites
        val oldOrder = prefs.presetOrder
        val oldParent = prefs.activeBrowseParent
        val oldArtwork = prefs.onlineArtworkEnabled
        val repo = StationRepository.get(targetContext)
        val libraryPrefs = targetContext.getSharedPreferences("playlist_library", android.content.Context.MODE_PRIVATE)
        val oldLibrary = libraryPrefs.all.toMap()
        fun restoreLibrary() {
            check(libraryPrefs.edit().clear().apply {
                oldLibrary.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> error("Unsupported saved library preference: $key")
                    }
                }
            }.commit()) { "Could not restore original playlist library" }
        }
        val uiPrefs = targetContext.getSharedPreferences("streaming_radio_ui", android.content.Context.MODE_PRIVATE)
        val oldUiBrowse = uiPrefs.getString("browse", null)
        val oldUiMode = if (uiPrefs.contains("mode")) uiPrefs.getInt("mode", 0) else null
        var testActivity: android.app.Activity? = null
        val fixtureMode = mode == "fixture"
        val changesPreferences = fixtureMode || mode == "empty-favorites"
        var outcome = ActivityResult.OK
        var resultText = ""
        // Snapshot the actual configured sources and cached source rows. No default URL,
        // replacement playlist, network parser or synthetic catalog is used by real modes.
        fun savedCatalog(label: String): List<Station> {
            val sources = repo.playlists()
            checkThat("$label has saved playlist sources", sources.isNotEmpty())
            val sourceRows = sources.flatMap { source ->
                checkThat("$label source has cached station rows", repo.sourceStations(source.id).isNotEmpty())
                repo.sourceStations(source.id)
            }.distinctBy { it.stream }
            checkThat("$label uses real saved stations rather than the fixture catalog", sourceRows.none {
                it.name.startsWith("Fixture ") || runCatching { java.net.URI(it.stream).host }.getOrNull() == "10.0.2.2"
            })
            checkThat("$label contains $expectedCount saved station identities", sourceRows.size == expectedCount)
            val all = repo.all()
            checkThat("$label All stations contains every saved source station",
                all.size == expectedCount && all.map { it.id to it.stream }.toSet() == sourceRows.map { it.id to it.stream }.toSet())
            return all
        }
        fun sourceSnapshot() = repo.playlists().map { source ->
            Triple(source.id, source.url, repo.sourceStations(source.id).map { Triple(it.id, it.name, it.stream) })
        }
        try {
            require(expectedCount in 1..2000) { "expectedCount must be between 1 and 2000" }
            require(mode in setOf("hosted", "offline", "artwork-audio", "empty-favorites", "fixture", "screenshots", "settings-typography")) {
                "Use hosted, offline, artwork-audio, empty-favorites, fixture, or screenshots mode"
            }
            if (mode == "settings-typography") {
                testActivity = startActivitySync(Intent(targetContext, ImpostorPlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                main {
                    val manager = ImpostorPlayerActivity::class.java.getDeclaredField("management")
                        .apply { isAccessible = true }.get(testActivity) as RadioManagement
                    manager.settings()
                }
                waitForIdleSync()
                main {
                    val content = testActivity!!.findViewById<android.view.View>(android.R.id.content)
                    fun labels(view: android.view.View): List<android.widget.TextView> =
                        (if (view is android.widget.TextView) listOf(view) else emptyList()) +
                        (if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList())
                    val captions = setOf("Theme", "Manage playlists", "Automatic track and station artwork", "Animate artwork changes", "Refresh playlists", "Privacy policy", "Open-source licenses")
                    val found = labels(content).filter { it.text.toString() in captions }
                    checkThat("all seven Settings captions are present", found.size == 7)
                    checkThat("Settings omits station images entry", labels(content).none { it.text.toString() == "Station images" })
                    checkThat("all Settings captions retain 22sp typography", found.all { kotlin.math.abs(it.textSize / targetContext.resources.displayMetrics.scaledDensity - 22f) < 0.1f })
                    checkThat("all Settings captions use regular font weight", found.all { it.typeface.weight == 400 })
                    checkThat("all Settings rows remain 56dp high", found.all { kotlin.math.abs(it.height / targetContext.resources.displayMetrics.density - 56f) < 0.1f })
                    val done = labels(content).single { it.text.toString() == "Done" }
                    val theme = net.mspanc.twinsenradio.data.AppearanceTheme.current(targetContext)
                    val surface = (done.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable
                    checkThat("Settings Done uses selected theme accent background", surface.color?.defaultColor == theme.accent)
                    checkThat("Settings Done uses matching on-accent text", done.currentTextColor == theme.onAccent)
                    val originalTheme = net.mspanc.twinsenradio.data.AppearanceTheme.current(targetContext)
                    val alternate = net.mspanc.twinsenradio.data.AppearanceTheme.presets.first { it.id != originalTheme.id }
                    try {
                        found.single { it.text.toString() == "Theme" }.performClick()
                        net.mspanc.twinsenradio.data.AppearanceTheme.select(targetContext, alternate.id)
                        ThemePicker.handleBack()
                        val updated = (done.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable
                        checkThat("Settings Done refreshes after returning from changed theme", updated.color?.defaultColor == alternate.accent && done.currentTextColor == alternate.onAccent)
                    } finally {
                        net.mspanc.twinsenradio.data.AppearanceTheme.select(targetContext, originalTheme.id)
                        ThemePicker.close(notifyClosed = true)
                        found.single { it.text.toString() == "Theme" }.performClick()
                        ThemePicker.handleBack()
                    }
                    val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                    try {
                        content.draw(Canvas(bitmap))
                        val folder = java.io.File(targetContext.filesDir, "screenshots").apply { mkdirs() }
                        java.io.FileOutputStream(java.io.File(folder, "settings-corrected.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                }
                main {
                    val manager = ImpostorPlayerActivity::class.java.getDeclaredField("management")
                        .apply { isAccessible = true }.get(testActivity) as RadioManagement
                    manager.playlists()
                }
                waitForIdleSync()
                main {
                    val content = testActivity!!.findViewById<android.view.View>(android.R.id.content)
                    fun visibleLabels(view: android.view.View): List<android.widget.TextView> =
                        (if (view is android.widget.TextView && view.isShown) listOf(view) else emptyList()) +
                        (if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { visibleLabels(view.getChildAt(it)) } else emptyList())
                    val labels = visibleLabels(content)
                    checkThat("Manage playlists uses Done instead of Back", labels.any { it.text.toString() == "Done" } && labels.none { it.text.toString() == "Back" })
                    val done = labels.single { it.text.toString() == "Done" }
                    val theme = net.mspanc.twinsenradio.data.AppearanceTheme.current(targetContext)
                    val surface = (done.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable
                    checkThat("Manage playlists Done uses theme accent and on-accent text", surface.color?.defaultColor == theme.accent && done.currentTextColor == theme.onAccent)
                }
            } else if (mode == "screenshots") {
                val playlistUrl = "https://screenshots.example/playlist.m3u"
                val stationNames = listOf("Afterglow FM", "Blue Note Radio", "Coastline Radio", "Night Drive", "Sunday Soul", "Signal Ambient")
                val stationKeys = listOf("afterglow", "blue-note", "coastline", "night-drive", "sunday-soul", "signal-ambient")
                val body = buildString {
                    appendLine("#EXTM3U")
                    stationNames.forEachIndexed { index, name ->
                        appendLine("#EXTINF:-1 group-title=\"${if (index % 2 == 0) "Curated" else "Late Night"}\" tvg-logo=\"http://10.0.2.2:18766/station-${index + 1}.png\",$name")
                        appendLine("http://10.0.2.2:18766/live.wav?station=${stationKeys[index]}")
                    }
                }
                val source = PlaylistSource("screenshots-demo", playlistUrl, "Demo collection", stationNames.size)
                val playlistPrefs = targetContext.getSharedPreferences("playlist_library", android.content.Context.MODE_PRIVATE)
                val sourcesJson = org.json.JSONArray().put(org.json.JSONObject()
                    .put("id", source.id).put("url", source.url).put("name", source.name).put("count", source.stationCount))
                check(playlistPrefs.edit().putString("sources", sourcesJson.toString()).commit())
                check(targetContext.getSharedPreferences("twinsen_radio", android.content.Context.MODE_PRIVATE)
                    .edit().putString("user_m3u", playlistUrl).commit())
                val cacheDir = java.io.File(targetContext.filesDir, "playlists").apply { mkdirs() }
                java.io.File(cacheDir, M3uParser.stableKey(playlistUrl) + ".m3u").writeText(body)
                prefs.onlineArtworkEnabled = true
                val fixtureStations = M3uParser.parse(body, playlistUrl)
                prefs.favourites = setOf(fixtureStations[0].id, fixtureStations[2].id)
                prefs.presetOrder = listOf(fixtureStations[2].id, fixtureStations[0].id)
                prefs.stationSort = net.mspanc.twinsenradio.data.StationSort.DEFAULT
                prefs.activeBrowseParent = RadioService.NODE_ALL
                prefs.discovered.toList().forEach { prefs.removeDiscovered(it.id) }
                check(playlistPrefs.edit().remove("all_order").remove("favorite_order").commit())
                val reload = repo.javaClass.getDeclaredMethod("reloadPlaylistCache", List::class.java).apply { isAccessible = true }
                reload.invoke(repo, listOf(source))
                checkThat("screenshot fixture loads six fictional stations", repo.all().map { it.name } == stationNames)

                val trackCache = CoverArtLookup::class.java.getDeclaredField("cache").apply { isAccessible = true }
                    .get(CoverArtLookup) as MutableMap<String, CoverArtLookup.TrackInfo?>
                synchronized(trackCache) {
                    trackCache["paper satellites|midnight colors"] = CoverArtLookup.TrackInfo(
                        artworkUrl = "http://10.0.2.2:18766/album.png", album = "Midnight Colors",
                        year = null, isSingle = false, trackName = "Midnight Colors", artistName = "Paper Satellites")
                }
                check(targetContext.getSharedPreferences("streaming_radio_ui", android.content.Context.MODE_PRIVATE)
                    .edit().putInt("mode", 0).putString("browse", RadioService.NODE_ALL).commit())
                testActivity = startActivitySync(android.content.Intent(targetContext, ImpostorPlayerActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                waitForIdleSync()
                browser = main {
                    MediaBrowser.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, RadioService::class.java))).buildAsync()
                }.get(30, TimeUnit.SECONDS)
                main { browser.volume = 0f }
                val first = children(RadioService.NODE_ALL, count = stationNames.size).first()
                select(first)
                waitFor("screenshot fixture stream reaches playing quietly", 45) { main { browser.isPlaying } }
                waitFor("fictional ICY track metadata is published", 30) { main {
                    browser.mediaMetadata.title?.toString()?.contains("Midnight Colors", ignoreCase = true) == true
                } }
                waitFor("custom fixture album art is published", 60) { main {
                    browser.mediaMetadata.artworkUri?.pathSegments?.firstOrNull() == "track"
                } }
                checkThat("screenshot app remains open on the six-station library", testActivity?.isFinishing == false)
                val captureAction = "com.impostorlabs.streamingradio.debug.CAPTURE_SCREENSHOT"
                val finishAction = "com.impostorlabs.streamingradio.debug.FINISH_SCREENSHOTS"
                val screenshotDone = CountDownLatch(1)
                val screenshotFailure = AtomicReference<Throwable?>()
                val captured = java.util.concurrent.CopyOnWriteArrayList<String>()
                val screenshotDir = java.io.File(targetContext.filesDir, "screenshots").apply { mkdirs() }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context, intent: Intent) {
                        if (intent.action == finishAction) {
                            screenshotDone.countDown()
                            return
                        }
                        try {
                            val name = intent.getStringExtra("name").orEmpty()
                            require(Regex("[a-z0-9_-]+").matches(name)) { "Invalid screenshot name" }
                            val activity = checkNotNull(testActivity) { "Player activity is unavailable" }
                            val content = checkNotNull(activity.findViewById<View>(android.R.id.content)) {
                                "App content view is unavailable"
                            }
                            check(content.width > 0 && content.height > 0) { "App content has no drawable size" }
                            val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                            try {
                                content.draw(Canvas(bitmap))
                                java.io.FileOutputStream(java.io.File(screenshotDir, "$name.png")).use { output ->
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                        "Could not encode screenshot"
                                    }
                                }
                            } finally { bitmap.recycle() }

                            captured += name
                        } catch (failure: Throwable) {
                            screenshotFailure.set(failure)
                            screenshotDone.countDown()
                        }
                    }
                }
                val filter = IntentFilter().apply { addAction(captureAction); addAction(finishAction) }
                targetContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
                try {
                    check(screenshotDone.await(10, TimeUnit.MINUTES)) {
                        "Screenshot navigation timed out after 10 minutes"
                    }
                    screenshotFailure.get()?.let { throw it }
                    checks += captured.map { "Captured app content screenshot files/screenshots/$it.png" }
                    checks += "Captures contain the app content view only; emulator system bars and dock are excluded"
                    checks += "Screenshot fixture is left running; restore the parent backup to return the original app data"
                } finally { targetContext.unregisterReceiver(receiver) }
            } else if (mode == "artwork-audio") {
                savedCatalog("audio/artwork check")
                val connection = main {
                    MediaBrowser.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, RadioService::class.java))).buildAsync()
                }
                browser = connection.get(30, TimeUnit.SECONDS)
                val item = children(RadioService.NODE_ALL).first { it.mediaMetadata.title.toString() == "Radio Nova" }
                select(item)
                waitFor("real Radio Nova is playing before artwork stress", 45) { main { browser.isPlaying } }
                val buffering = java.util.concurrent.atomic.AtomicInteger()
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING) buffering.incrementAndGet()
                    }
                }
                val startPosition = main { browser.addListener(listener); browser.currentPosition }
                val started = System.nanoTime()
                val soul = repo.all().first { it.name == "Nova Soul" }
                try {
                    for (query in listOf("Nova Soul", "Nova", "Nova Beach")) {
                        val result = runBlocking { net.mspanc.twinsenradio.playback.StationArtworkStore.searchCandidates(targetContext, soul, query) }
                        checks += "artwork stress query '$query': "+result.javaClass.simpleName
                    }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
                    val endPosition = main { browser.currentPosition }
                    val expectedId = Station.idFromMediaId(item.mediaId)
                    val selectedId = main { Station.idFromMediaId(browser.currentMediaItem?.mediaId.orEmpty()) }
                    checks += "Observed after searches: elapsed=${elapsed}ms, advanced=${endPosition-startPosition}ms, bufferingTransitions=${buffering.get()}, " + main { "state=${browser.playbackState}, wanted=${browser.playWhenReady}, playing=${browser.isPlaying}, suppression=${browser.playbackSuppressionReason}, error=${browser.playerError?.errorCode}" }
                    checkThat("image searches preserve canonical station identity", selectedId == expectedId)
                    checkThat("radio remains playing after image searches", main { browser.isPlaying })
                    checkThat("playback position advances during artwork searches", endPosition > startPosition)
                    checkThat("no player buffering transition during artwork searches", buffering.get() == 0)
                    checks += "Measured search duration=${elapsed}ms playback advance=${endPosition-startPosition}ms; does not prove absence of audio underruns"
                } finally { main { browser.removeListener(listener) } }
            } else if (mode == "hosted") {
                val expected = savedCatalog("saved-library integration check")
                val sourcesBefore = sourceSnapshot()
                val localOrder = expected.map { it.id to it.stream }
                runBlocking { repo.refreshUserLists(force = true) }
                checkThat("refresh preserves saved source identity and individual source row order", sourceSnapshot() == sourcesBefore)
                checkThat("refresh preserves the saved All-stations identities and custom order",
                    repo.all().map { it.id to it.stream } == localOrder)
                val connection = main {
                    MediaBrowser.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, RadioService::class.java))).buildAsync()
                }
                browser = connection.get(30, TimeUnit.SECONDS)
                checkThat("native All stations follows all $expectedCount identities in saved library order",
                    children(RadioService.NODE_ALL, count = expectedCount).map { Station.idFromMediaId(it.mediaId) } == repo.all().map { it.id })
                for (station in listOf(repo.all().first(), repo.all().last())) {
                    val uri = net.mspanc.twinsenradio.playback.StationArtworkStore.uri(targetContext, station)
                    val bitmap = targetContext.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    checkThat("station preview image decodes for ${station.name}", bitmap != null && bitmap.width > 0)
                    bitmap?.recycle()
                }
                val authority=targetContext.packageName+".logos"
                fun readable(raw:String)=runCatching {
                    targetContext.contentResolver.openInputStream(android.net.Uri.parse(raw))?.use { it.read(); true } ?: false
                }.getOrDefault(false)
                val privatePath=java.io.File(targetContext.applicationInfo.dataDir,"shared_prefs/twinsen_radio.xml").absolutePath
                val zeros="0".repeat(64)
                checkThat("artwork provider rejects caller-supplied private file paths",
                    !readable("content://$authority/custom/$zeros?p="+android.net.Uri.encode(privatePath)))
                checkThat("artwork provider rejects caller-supplied source proxies",
                    !readable("content://$authority/muted/$zeros?src="+android.net.Uri.encode("file://$privatePath")))
                checkThat("artwork provider rejects arbitrary resource query parameters",
                    !readable("content://$authority/logo/$zeros?v=1"))
                if(prefs.onlineArtworkEnabled) {
                    val soul=repo.all().first { it.name.equals("Nova Soul",true) }
                    val result=runBlocking {
                        net.mspanc.twinsenradio.playback.StationArtworkStore.searchCandidates(targetContext,soul,"Nova Soul")
                    }
                    checkThat("Nova Soul artwork search retains downloadable logo alternatives",
                        result is net.mspanc.twinsenradio.playback.StationArtworkStore.CandidateSearch.Found && result.images.isNotEmpty())
                }
            } else if (mode == "offline") {
                val expected = savedCatalog("offline cold-process catalog")
                val connection = main {
                    MediaBrowser.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, RadioService::class.java))).buildAsync()
                }
                browser = connection.get(30, TimeUnit.SECONDS)
                checkThat("offline native browse preserves saved catalog identities and order",
                    children(RadioService.NODE_ALL, count = expectedCount).map { Station.idFromMediaId(it.mediaId) } == expected.map { it.id })
            } else if (mode == "empty-favorites") {
                val expected = savedCatalog("empty Favorites regression")
                val sourcesBefore = sourceSnapshot()
                waitFor("empty Favorites UI check is parked", 10) {
                    net.mspanc.twinsenradio.AutomotiveUxGuard.canUseSettings
                }
                main { prefs.favourites = emptySet() }
                // Force split view only for this opt-in UI check, restoring its saved
                // browse/mode and Favorites membership/order in finally below.
                check(uiPrefs.edit().putInt("mode", 0).putString("browse", RadioService.NODE_ALL).commit())
                testActivity = startActivitySync(android.content.Intent(targetContext, ImpostorPlayerActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                waitForIdleSync()
                fun views(view: android.view.View): List<android.view.View> = listOf(view) +
                    if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
                    else emptyList()
                fun clickTab(caption: String) = main {
                    val root = checkNotNull(testActivity).window.decorView
                    val tab = views(root).filterIsInstance<android.widget.TextView>().single {
                        if (caption == "All stations") it.text.toString().startsWith("All stations (")
                        else it.text.toString() == caption
                    }
                    check(tab.performClick()) { "Tab click was not handled" }
                }
                clickTab("Favorites")
                waitForIdleSync()
                checkThat("empty Favorites tab stays open with zero rows and no crash", main {
                    val activity = checkNotNull(testActivity)
                    !activity.isFinishing && !activity.isDestroyed &&
                        views(activity.window.decorView).filterIsInstance<android.widget.ListView>().single().adapter.count == 0
                })
                clickTab("All stations")
                waitForIdleSync()
                checkThat("All stations can reopen after empty Favorites", main {
                    val activity = checkNotNull(testActivity)
                    !activity.isFinishing && views(activity.window.decorView).filterIsInstance<android.widget.ListView>().single().adapter.count == expectedCount
                })
                checkThat("empty Favorites UI check preserves source snapshots and All order",
                    sourceSnapshot() == sourcesBefore && repo.all().map { it.id to it.stream } == expected.map { it.id to it.stream })
            } else {
            prefs.onlineArtworkEnabled = true
            runBlocking { repo.refreshUserLists(force = true) }
            savedCatalog("fixture prerequisite real library")
            val future = main {
                MediaBrowser.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, RadioService::class.java)))
                    .buildAsync()
            }
            browser = future.get(30, TimeUnit.SECONDS)
            val rootCount = if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) 3 else 4
            checkThat("native root has $rootCount browse folders", children(RadioService.NODE_ROOT).size == rootCount)
            val hosted = children(RadioService.NODE_ALL, count = expectedCount)
            checkThat("native browse exposes all saved stations", hosted.size == expectedCount)
            checkThat("pagination preserves hosted order", children(RadioService.NODE_ALL, 1, 5).map { it.mediaId } == hosted.drop(5).take(5).map { it.mediaId })
            select(hosted.first())
            waitFor("Nova relay decodes and reaches playing", 35) { main { browser.isPlaying } }
            checkThat("playlist selection creates full navigation queue", main { browser.mediaItemCount } == expectedCount)
            checkThat("every queued station has a nonblank title before track metadata", main {
                (0 until browser.mediaItemCount).all { browser.getMediaItemAt(it).mediaMetadata.title?.isNotBlank() == true }
            })
            main { browser.seekToNext() }
            waitFor("next advances to Nova Soul") { main { Station.idFromMediaId(browser.currentMediaItem?.mediaId.orEmpty()) } == Station.idFromMediaId(hosted[1].mediaId) }
            main { browser.seekToPrevious() }
            waitFor("previous returns to Radio Nova") { main { Station.idFromMediaId(browser.currentMediaItem?.mediaId.orEmpty()) } == Station.idFromMediaId(hosted[0].mediaId) }
            main { browser.pause() }

            control("normal")
            // Test-only canonical fixture injection; the public URL editor remains HTTPS-only.
            check(net.mspanc.twinsenradio.BuildConfig.DEBUG) { "HTTP fixture requires a debug build" }
            val fixtureUrl = "http://10.0.2.2:18765/list.m3u"
            val fixtureSources = org.json.JSONArray().put(org.json.JSONObject()
                .put("id", "instrumentation-fixture").put("url", fixtureUrl)
                .put("name", "Fixture").put("count", 0))
            check(libraryPrefs.edit().putString("sources", fixtureSources.toString())
                .remove("all_order").remove("favorite_order").commit())
            runBlocking { repo.refreshUserLists(force = true) }
            val first = repo.all()
            checkThat("section headings and explicit groups parsed", first.map { it.genre } == listOf("Jazz", "Jazz", "World"))
            prefs.favourites = setOf(first[0].id, first[2].id)
            prefs.presetOrder = listOf(first[2].id, first[0].id)
            waitFor("ordered presets visible through native browse") {
                children(RadioService.NODE_FAVOURITES).map { Station.idFromMediaId(it.mediaId) } == listOf(first[2].id, first[0].id)
            }
            select(children(RadioService.NODE_FAVOURITES).first())
            waitFor("preset playback uses two-station queue") { main { browser.mediaItemCount } == 2 }
            waitFor("fixture WAV reaches playing") { main { browser.isPlaying } }
            main { browser.seekToNextMediaItem() }
            waitFor("next follows preset order") { main { Station.idFromMediaId(browser.currentMediaItem?.mediaId.orEmpty()) } == first[0].id }
            main { browser.seekToPreviousMediaItem() }
            waitFor("previous follows preset order") { main { Station.idFromMediaId(browser.currentMediaItem?.mediaId.orEmpty()) } == first[2].id }
            select(children(RadioService.NODE_GENRE_PREFIX + "Jazz").first())
            waitFor("section selection scopes navigation queue") { main {
                browser.mediaItemCount == 2 && Station.idFromMediaId(browser.currentMediaItem?.mediaId.orEmpty()) == first[0].id
            } }
            control("reordered")
            runBlocking { repo.refreshUserLists(force = true) }
            checkThat("station identity survives playlist reorder", repo.all().map { it.id }.toSet() == first.map { it.id }.toSet())
            checkThat("preset order survives playlist reorder", repo.favourites().map { it.id } == listOf(first[2].id, first[0].id))
            control("bad-list")
            val saved = repo.all()
            runBlocking { repo.refreshUserLists(force = true) }
            checkThat("failed refresh retains last-good catalog", repo.all() == saved)
            control("normal")
            runBlocking { repo.refreshUserLists(force = true) }
            select(children(RadioService.NODE_ALL).first())
            waitFor("fixture stream playing before interruption") { main { browser.isPlaying } }
            waitFor("ICY song title reaches the native media session") { main {
                browser.mediaMetadata.title?.toString()?.contains("Teardrop", ignoreCase = true) == true
            } }
            waitFor("matching artwork downloaded and local image URI published", 60) { main {
                browser.mediaMetadata.artworkUri?.pathSegments?.firstOrNull() == "track"
            } }
            val art = main { browser.mediaMetadata.artworkUri!! }
            val image = targetContext.contentResolver.openInputStream(art)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            checkThat("published artwork is a readable decoded image", image != null && image.width > 0 && image.height > 0)
            image?.recycle()
            waitFor("station display title stays separate from artist track and bitrate") { main {
                val subtitle = browser.mediaMetadata.subtitle.toString()
                browser.mediaMetadata.displayTitle.toString() == "Fixture Jazz One" &&
                    subtitle.contains("Massive Attack") && subtitle.contains("Teardrop") && subtitle.contains("kbps")
            } }
            checkThat("structured quality and detailed description available to media consumers", main {
                val metadata = browser.mediaMetadata
                (metadata.extras?.getInt("com.impostorlabs.streamingradio.BITRATE_KBPS", 0) ?: 0) > 0 &&
                    metadata.description.toString().contains("kHz") && metadata.description.toString().contains("mono")
            })
            prefs.onlineArtworkEnabled = false
            waitFor("artwork opt-out immediately replaces downloaded track image") { main {
                browser.mediaMetadata.artworkUri?.pathSegments?.firstOrNull() != "track"
            } }
            val lookupCalls = CoverArtLookup.lookupCalls.get()
            val downloadCalls = TrackArtworkStore.downloadCalls.get()
            main { browser.seekToNextMediaItem() }
            waitFor("track text still updates with online artwork disabled") { main {
                browser.mediaMetadata.title.toString().contains("Teardrop") &&
                    browser.mediaMetadata.displayTitle.toString() == "Fixture Jazz Two"
            } }
            checkThat("artwork opt-out prevents catalog lookups and image downloads",
                CoverArtLookup.lookupCalls.get() == lookupCalls && TrackArtworkStore.downloadCalls.get() == downloadCalls)
            prefs.onlineArtworkEnabled = true
            waitFor("artwork opt-in restores the matching cached image") { main {
                browser.mediaMetadata.artworkUri?.pathSegments?.firstOrNull() == "track"
            } }
            waitFor("selected stream is actually playing before recovery test") { main { browser.isPlaying } }
            val openedBeforeDrop = openedStreams()
            control("drop-streams")
            Thread.sleep(6000)
            waitFor("playback reconnects and recovers after stream interruption", 35) {
                main { browser.isPlaying } && openedStreams() > openedBeforeDrop
            }
            main { browser.pause() }
            control("drop-streams")
            Thread.sleep(7000)
            checkThat("recovery does not override explicit pause", main { !browser.playWhenReady && !browser.isPlaying })
            }
            resultText = checks.joinToString("\nPASS: ", "PASS: ") + "\n"
        } catch (failure: Throwable) {
            outcome = ActivityResult.FAIL
            resultText = checks.joinToString("\nPASS: ", "PASS: ") + "\nFAIL: " + android.util.Log.getStackTraceString(failure)
        } finally {
            if (mode != "screenshots") testActivity?.let { activity -> main { activity.finish() } }
            if (this::browser.isInitialized && mode != "screenshots") main {
                if (fixtureMode || mode == "artwork-audio") browser.pause()
                browser.release()
            }
            if (changesPreferences) try {
            prefs.userM3uUrls = oldUrls
            prefs.favourites = oldPresets
            prefs.presetOrder = oldOrder
            prefs.activeBrowseParent = oldParent
            prefs.onlineArtworkEnabled = oldArtwork
            // Instrumentation finishes by terminating the process. Drain queued apply()
            // writes before that happens so temporary Favorites changes are restored too.
            check(targetContext.getSharedPreferences("twinsen_radio", android.content.Context.MODE_PRIVATE)
                .edit().putString("user_m3u", oldUrls.joinToString("\n")).commit()) {
                "Could not persist restored playlist configuration"
            }
            restoreLibrary()
            check(uiPrefs.edit().apply {
                if (oldUiBrowse == null) remove("browse") else putString("browse", oldUiBrowse)
                if (oldUiMode == null) remove("mode") else putInt("mode", oldUiMode)
            }.commit()) { "Could not persist restored player view" }
            // Reload the original sources, then restore the exact registry and order bytes
            // again: repository refresh may update IDs, snapshots, or order metadata.
            try {
                if (fixtureMode) runBlocking { repo.refreshUserLists(force = true) }
            } finally {
                restoreLibrary()
            }
            check(libraryPrefs.all == oldLibrary) { "Original playlist registry and orders were not restored" }
            check(prefs.userM3uUrls == oldUrls && prefs.favourites == oldPresets &&
                prefs.presetOrder == oldOrder && prefs.activeBrowseParent == oldParent &&
                prefs.onlineArtworkEnabled == oldArtwork) { "Original radio preferences were not restored" }
            check(uiPrefs.getString("browse", null) == oldUiBrowse &&
                (if (uiPrefs.contains("mode")) uiPrefs.getInt("mode", 0) else null) == oldUiMode) {
                "Original player view was not restored"
            }
            resultText += "PASS: Original playlist and player preferences restored\n"
            } catch (failure: Throwable) {
                outcome = ActivityResult.FAIL
                resultText += "\nFAIL: Preference restoration: " + android.util.Log.getStackTraceString(failure)
            }
        }
        finish(outcome, Bundle().apply { putString("stream", resultText) })
    }

    private object ActivityResult { const val OK = -1; const val FAIL = 0 }
}
