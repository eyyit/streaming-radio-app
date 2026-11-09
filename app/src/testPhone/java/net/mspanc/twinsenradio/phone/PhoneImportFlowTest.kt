package net.mspanc.twinsenradio.phone

import android.app.Activity
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.RadioService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ContentProviderController
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneImportFlowTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var repo: StationRepository
    private lateinit var originalSources: Set<String>
    private var priorOnlineArtwork = true
    private val temporaryFiles = mutableListOf<File>()
    private val controllers = mutableListOf<org.robolectric.android.controller.ActivityController<PhoneImportActivity>>()

    @Before fun isolatePlaylistState() {
        repo = StationRepository.get(context)
        originalSources = repo.playlists().map { it.id }.toSet()
        priorOnlineArtwork = Prefs(context).onlineArtworkEnabled
        Prefs(context).onlineArtworkEnabled = false
    }

    @After fun restoreFixturesAndPreferences() {
        repo.playlists().filterNot { it.id in originalSources }.forEach { repo.removePlaylist(it.id) }
        Prefs(context).onlineArtworkEnabled = priorOnlineArtwork
        controllers.forEach { runCatching { it.destroy() } }
        temporaryFiles.forEach { it.delete() }
    }

    @Test fun localPreviewDoesNotSaveUntilExplicitImportAndPreservesSourceOrder() {
        val body = playlist("One", "Two", "Three", "Four")
        val fixture = fixture("stations.m3u", body)
        val before = repo.playlists().map { it.id }
        val host = launch()
        selectDocument(host.get(), fixture.uri)

        await(host.get()) { title(host.get()) == "Import local playlist" }
        assertNotNull(text(host.get(), "4 stations found"))
        listOf("One", "Two", "Three").forEach { row ->
            assertNotNull("Preview should show source-order row $row", text(host.get(), row))
        }
        assertTrue("Preview is limited to the first three entries",
            text(host.get(), "Four") == null)
        assertEquals("File selection must leave existing playlist state unchanged", before, repo.playlists().map { it.id })
        assertEquals(body, fixture.file.readText(Charsets.UTF_8))
        assertTrue(fixture.provider.writeAttempts == 0)

        button(host.get(), "Import playlist").performClick()
        await(host.get()) { host.get().isFinishing }

        val imported = repo.playlists().filterNot { it.id in originalSources }
        assertEquals(1, imported.size)
        assertEquals(listOf("One", "Two", "Three", "Four"),
            repo.sourceStations(imported.single().id).map { it.name })
        assertEquals(body, fixture.file.readText(Charsets.UTF_8))
        assertEquals(0, fixture.provider.writeAttempts)
    }

    @Test fun invalidSecondFileKeepsTheExistingPreviewAndUnsavedLibrary() {
        val firstBody = playlist("Keep one", "Keep two")
        val valid = fixture("first.m3u", firstBody)
        val invalid = fixture("broken.m3u", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n")
        val before = repo.playlists().map { it.id }
        val host = launch()
        selectDocument(host.get(), valid.uri)
        await(host.get()) { title(host.get()) == "Import local playlist" }
        assertEquals(before, repo.playlists().map { it.id })

        button(host.get(), "Choose another file").performClick()
        selectDocument(host.get(), invalid.uri)
        await(host.get()) { containsText(host.get(), "Could not open") ||
            containsText(host.get(), "HLS") || containsText(host.get(), "playable station") }

        assertEquals("Import local playlist", title(host.get()))
        assertNotNull(text(host.get(), "first.m3u"))
        assertNotNull(text(host.get(), "2 stations found"))
        assertTrue(button(host.get(), "Import playlist").isEnabled)
        assertEquals("A failed replacement must not save anything", before, repo.playlists().map { it.id })
    }

    @Test fun blankAndInvalidHostedUrlKeepFieldsAndExistingLibrary() {
        runBlocking { repo.saveLocalPlaylist("Existing", playlist("Saved")) }
        val before = repo.playlists().map { it.id }
        val host = launch()
        val activity = host.get()
        button(activity, "Add playlist").performClick()
        assertEquals("", field(activity, "Playlist URL").text.toString())
        assertNotNull(text(activity, "Enter a playlist URL first."))
        assertEquals(View.VISIBLE, text(activity, "Enter a playlist URL first.")!!.visibility)

        field(activity, "Playlist URL").setText("http://not-https.invalid/list.m3u")
        field(activity, "Hosted playlist name").setText("Keep my name")
        button(activity, "Add playlist").performClick()
        await(activity) { containsText(activity, "HTTPS") }

        assertEquals("http://not-https.invalid/list.m3u", field(activity, "Playlist URL").text.toString())
        assertEquals("Keep my name", field(activity, "Hosted playlist name").text.toString())
        assertEquals(before, repo.playlists().map { it.id })
    }

    @Test fun selectedPreviewAndBothNamesSurviveActivityRecreation() {
        val body = playlist("Rotation one", "Rotation two")
        val fixture = fixture("rotation.m3u", body)
        val host = launch()
        field(host.get(), "Playlist URL").setText("https://preserved.example/feed.m3u")
        field(host.get(), "Hosted playlist name").setText("Hosted draft")
        selectDocument(host.get(), fixture.uri)
        await(host.get()) { title(host.get()) == "Import local playlist" }
        field(host.get(), "Local playlist name").setText("Local draft")

        val recreated = host.recreate()
        controllers.remove(host)
        controllers.add(recreated)
        val activity = recreated.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("Import local playlist", title(activity))
        assertNotNull(text(activity, "rotation.m3u"))
        assertNotNull(text(activity, "2 stations found"))
        assertEquals("Local draft", field(activity, "Local playlist name").text.toString())
        assertEquals(body, fixture.file.readText(Charsets.UTF_8))
        assertTrue(button(activity, "Import playlist").isEnabled)
        assertEquals("https://preserved.example/feed.m3u",
            field(activity, "Playlist URL").text.toString())
        assertEquals("Hosted draft", field(activity, "Hosted playlist name").text.toString())
    }

    @Test fun largeTextPreviewKeepsItsHeadingAndFileInformationVisible() {
        RuntimeEnvironment.setFontScale(1.8f)
        try {
            val host = launch()
            selectDocument(host.get(), fixture("large-text.m3u", playlist("One", "Two")).uri)
            await(host.get()) { title(host.get()) == "Import local playlist" }
            val activity = host.get()
            assertEquals(1.8f, activity.resources.configuration.fontScale, .01f)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val density = activity.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((393 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((753 * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val clipped = listOf("Import local playlist", "large-text.m3u", "2 stations found")
                .map { text(activity, it)!! }.filter {
                    val layout = it.layout
                    val parent = it.parent as View
                    layout == null || layout.lineCount == 0 ||
                        layout.getLineBottom(layout.lineCount - 1) + it.compoundPaddingTop +
                            it.compoundPaddingBottom > it.height ||
                        it.bottom + parent.paddingBottom > parent.height
                }.map { it.text.toString() }
            assertTrue("Preview text must remain visible at 1.8x: $clipped", clipped.isEmpty())
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun launch(): org.robolectric.android.controller.ActivityController<PhoneImportActivity> {
        shadowOf(context.applicationContext as android.app.Application)
            .declareComponentUnbindable(ComponentName(context, RadioService::class.java))
        val host = Robolectric.buildActivity(PhoneImportActivity::class.java)
        host.get().setTheme(R.style.Theme_TwinsenRadio)
        controllers += host
        host.create().start()
        return host
    }

    private fun selectDocument(activity: PhoneImportActivity, uri: Uri) {
        val caption = if (title(activity) == "Import local playlist") "Choose another file" else "Choose M3U file"
        button(activity, caption).performClick()
        val launched = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("OpenDocument should launch the native file picker", launched)
        val result = android.content.Intent().setData(uri)
        assertTrue(activity.activityResultRegistry.dispatchResult(
            launched.requestCode, Activity.RESULT_OK, result))
    }

    private fun fixture(name: String, content: String): Fixture {
        val file = File.createTempFile("phone-import-", ".m3u", context.cacheDir)
        file.writeText(content, Charsets.UTF_8)
        temporaryFiles += file
        val authority = "phone.import.test.${UUID.randomUUID()}"
        val provider = ReadOnlyPlaylistProvider(file, name)
        val providerInfo = android.content.pm.ProviderInfo().apply {
            this.authority = authority
            applicationInfo = context.applicationInfo
        }
        ContentProviderController.of(provider).create(providerInfo)
        val uri = Uri.parse("content://$authority/${UUID.randomUUID()}.m3u")
        return Fixture(uri, file, provider)
    }

    private fun await(activity: PhoneImportActivity, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val status = views(activity).filterIsInstance<TextView>().map { it.text.toString() }
            .filter { it.isNotBlank() }.joinToString(" | ")
        assertTrue("Timed out waiting for the import flow; title=${title(activity)}; text=$status", condition())
    }

    private fun title(activity: PhoneImportActivity) = text(activity, "Import local playlist")?.text?.toString()
        ?: text(activity, "Add playlist")?.text?.toString()

    private fun field(activity: PhoneImportActivity, description: String) =
        views(activity).filterIsInstance<EditText>().first { it.contentDescription == description }

    private fun button(activity: PhoneImportActivity, vararg captions: String): Button =
        views(activity).filterIsInstance<Button>().first { it.text.toString() in captions }

    private fun text(activity: PhoneImportActivity, value: String): TextView? =
        views(activity).filterIsInstance<TextView>().firstOrNull { it.text.toString() == value }

    private fun containsText(activity: PhoneImportActivity, value: String): Boolean =
        views(activity).filterIsInstance<TextView>().any { it.text.toString().contains(value) }

    private fun views(activity: Activity): List<View> {
        fun collect(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
            else emptyList()
        return collect(activity.findViewById(android.R.id.content))
    }

    private fun playlist(vararg names: String) = buildString {
        append("#EXTM3U\n")
        names.forEachIndexed { index, name ->
            append("#EXTINF:-1,").append(name).append('\n')
            append("https://radio.example/").append(index).append('\n')
        }
    }

    private data class Fixture(val uri: Uri, val file: File, val provider: ReadOnlyPlaylistProvider)

    private class ReadOnlyPlaylistProvider(private val file: File, private val name: String) : ContentProvider() {
        var writeAttempts = 0

        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/x-mpegurl"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r") { "M3U provider is read-only" }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writeAttempts++; return null }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            writeAttempts++; return 0
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?): Int { writeAttempts++; return 0 }
    }
}
