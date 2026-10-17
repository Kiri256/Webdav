package com.example.webdavmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import android.content.res.Configuration
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import okhttp3.HttpUrl.Companion.toHttpUrl
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import `is`.xyz.mpv.MPV
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BrowserFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val model: BrowserViewModel get() = ViewModelProvider(rule.activity)[BrowserViewModel::class.java]

    @Test fun remoteCreateAndRenameRefreshAfterEarlierListingErrors() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            fixture.failListing.set(true)
            rule.runOnIdle { vm.refresh() }
            rule.waitUntil(10000) { !vm.loading && vm.error != null }
            rule.runOnIdle { vm.createCurrent("new folder", true) }
            rule.waitUntil(10000) { !vm.operationBusy && !vm.loading && vm.entries.any { it.name == "new folder" } }
            assertNull(vm.error)
            fixture.failListing.set(true)
            rule.runOnIdle { vm.refresh() }
            rule.waitUntil(10000) { !vm.loading && vm.error != null }
            rule.runOnIdle { vm.renameCurrent(vm.entries.first { it.name == "a+b.png" }, "renamed.png") }
            rule.waitUntil(10000) { !vm.operationBusy && !vm.loading && vm.entries.any { it.name == "renamed.png" } }
            assertNull(vm.error)
            assertFalse(vm.entries.any { it.name == "a+b.png" })
        }
    }

    @Test fun dismissingResultsFinishesJournalCleanupBeforeAnotherTransfer() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            val source = vm.entries.first { it.isImage() }
            runBlocking { TransferJournal(context, source, "/photos/sub/old.png", vm.connection!!.id, false).record("interrupted") }
            rule.runOnIdle { vm.stageFiles(false, listOf(source)); vm.navigate("/photos/sub") }
            rule.waitUntil(10000) { !vm.loading }
            rule.runOnIdle {
                vm.dismissOperationResults()
                assertTrue(vm.operationBusy)
                assertFalse(vm.canPaste)
                vm.pasteFiles()
            }
            rule.waitUntil(10000) { !vm.operationBusy }
            assertFalse(fixture.destinationFiles.contains(source.name))
            fixture.failTransfer.set(true)
            rule.runOnIdle { vm.pasteFiles() }
            rule.waitUntil(10000) { !vm.operationBusy && !vm.loading }
            assertEquals("Failed", vm.operationResults.single().status)
            assertTrue(runBlocking { TransferJournal.recover(context) }.any { it.file.path == source.path })
            rule.runOnIdle { vm.dismissOperationResults() }
            rule.waitUntil(10000) { !vm.operationBusy }
        }
    }

    @Test fun newerSavedStateWinsOverPersistedTabSnapshot() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            rule.runOnIdle { vm.setSearch("old-disk-query") }
            rule.waitUntil(10000) { runBlocking { BrowserStateStore(context).load() }?.tabs?.getOrNull(2)?.search == "old-disk-query" }
            val owner = androidx.lifecycle.ViewModelStore()
            lateinit var restored: BrowserViewModel
            rule.runOnIdle {
                restored = BrowserViewModel(rule.activity.application, androidx.lifecycle.SavedStateHandle(mapOf(
                    "activeTab" to 2, "localMode" to false, "server" to vm.connection!!.id,
                    "directory" to "/photos/sub", "searchQuery" to "new-saved-query",
                    "grid" to false, "sortMode" to FileSortMode.SIZE.name)))
                owner.put("restored", restored)
            }
            try {
                rule.waitUntil(10000) { !restored.starting && !restored.loading }
                rule.runOnIdle {
                    assertEquals(2, restored.activeTab)
                    assertEquals("/photos/sub", restored.path)
                    assertEquals("new-saved-query", restored.searchQuery)
                    assertFalse(restored.grid)
                    assertEquals(FileSortMode.SIZE, restored.sortMode)
                }
            } finally { rule.runOnIdle { owner.clear() } }
            rule.runOnIdle {
                restored = BrowserViewModel(rule.activity.application, androidx.lifecycle.SavedStateHandle(mapOf("localMode" to true)))
                owner.put("legacy", restored)
            }
            try {
                rule.waitUntil(10000) { !restored.starting }
                rule.runOnIdle { assertEquals(0, restored.activeTab); assertTrue(restored.localMode) }
            } finally { rule.runOnIdle { owner.clear() } }
        }
    }

    @Test fun fourTabsShareConnectionAndRestoreIndependentFolders() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            rule.runOnIdle { vm.selectTab(2) }
            rule.waitUntil(10000) { !vm.loading }
            val sharedConnection = vm.connection!!.id
            rule.runOnIdle { vm.navigate("/photos/sub") }
            rule.waitUntil(10000) { !vm.loading }
            rule.runOnIdle {
                vm.setSearch("tab-two")
                vm.selectTab(3)
            }
            rule.waitUntil(10000) { !vm.loading }
            rule.runOnIdle {
                assertEquals("/photos", vm.path)
                assertEquals("", vm.searchQuery)
                assertEquals(sharedConnection, vm.connection!!.id)
                vm.stageFiles(false, listOf(vm.entries.first { !it.isDirectory }))
                vm.selectTab(2)
            }
            rule.waitUntil(10000) { !vm.loading }
            val secondTabPositionKey = vm.folderPositionKey("grid")
            rule.runOnIdle {
                assertEquals("/photos/sub", vm.path)
                assertEquals("tab-two", vm.searchQuery)
                assertTrue(vm.canPaste)
                vm.selectTab(3)
                assertNotEquals(secondTabPositionKey, vm.folderPositionKey("grid"))
            }
            rule.waitUntil(10000) { !vm.loading }
            rule.runOnIdle { vm.selectTab(2) }
            rule.waitUntil(10000) { !vm.loading }
            rule.waitUntil(10000) { runBlocking { BrowserStateStore(context).load() }?.let {
                it.activeTab == 2 && it.tabs.getOrNull(2)?.search == "tab-two" && it.tabs.getOrNull(3)?.directory == "/photos"
            } == true }
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10000) { !model.starting && !model.loading }
            rule.runOnIdle {
                assertEquals(2, model.activeTab)
                assertEquals("/photos/sub", model.path)
                assertEquals("tab-two", model.searchQuery)
                model.dismissSearch()
            }
            rule.onNodeWithText("Search this folder").assertDoesNotExist()
            rule.onNodeWithContentDescription("Refresh").assertIsDisplayed()
            rule.onNodeWithContentDescription("Search").performClick()
            rule.onNodeWithText("Search this folder").assertIsDisplayed()
            rule.onNodeWithText("Done").performClick()
            rule.onNodeWithText("Search this folder").assertDoesNotExist()
            listOf("Local 1", "Local 2", "WebDAV 1", "WebDAV 2").forEach {
                rule.onNodeWithText(it).assertIsDisplayed()
            }
            val owner = androidx.lifecycle.ViewModelStore()
            lateinit var fresh: BrowserViewModel
            rule.runOnIdle {
                fresh = BrowserViewModel(rule.activity.application, androidx.lifecycle.SavedStateHandle())
                owner.put("fresh", fresh)
            }
            try {
                rule.waitUntil(10000) { !fresh.starting && !fresh.loading }
                rule.runOnIdle {
                    assertEquals(2, fresh.activeTab)
                    assertEquals("/photos/sub", fresh.path)
                    assertEquals("tab-two", fresh.searchQuery)
                    assertEquals(sharedConnection, fresh.connection!!.id)
                    fresh.selectTab(3)
                }
                rule.waitUntil(10000) { !fresh.loading }
                rule.runOnIdle { assertEquals("/photos", fresh.path) }
            } finally { rule.runOnIdle { owner.clear() } }
        }
    }

    @Test fun gridScrollReturnsToSameFileAfterDirectoryNavigationAndRecreation() {
        Fixture().use { fixture ->
            repeat(60) { fixture.files.add("item-%02d.png".format(it)) }
            val vm = connect(fixture)
            rule.runOnIdle { if (!vm.grid) vm.toggleGrid() }
            rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)).performScrollToIndex(30)
            val key = vm.folderPositionKey("grid")
            rule.waitUntil(5000) { vm.folderPosition(key).index >= 28 }
            val anchor = vm.folderPosition(key).path!!
            val name = vm.entries.first { it.path == anchor }.name
            rule.runOnIdle { vm.navigate("/photos/sub") }
            rule.waitUntil(10000) { !vm.loading }
            rule.runOnIdle { vm.navigate("/photos") }
            rule.waitUntil(10000) { !vm.loading }
            rule.onNodeWithText(name).assertIsDisplayed()
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            rule.onNodeWithText(name).assertIsDisplayed()
        }
    }

    private fun connect(server: Fixture): BrowserViewModel {
        val vm = model
        val serverUrl = server.server.url("/dav/").toString()
        rule.waitUntil(10_000) { !vm.starting }
        rule.runOnIdle {
            vm.editConnection()
            vm.connect(WebDavConnection(UUID.randomUUID().toString(), "Fixture", serverUrl,
                "user", "pass", "/photos"))
        }
        rule.waitUntil(15_000) { !vm.connecting }
        rule.runOnIdle { assertNull(vm.connectionError); assertFalse(vm.editing) }
        return vm
    }

    @Test fun imageSwipesRespectZoomAndBoundaries() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            val images = vm.entries.filter { it.isImage() }
            rule.runOnIdle { vm.open(images.first()) }
            rule.waitForIdle()
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!
            assertTrue(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()))
            val statusHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            val top = rule.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot.top
            val density = rule.activity.resources.displayMetrics.density
            assertTrue("Image toolbar should sit directly below the status bar: $top / $statusHeight",
                top >= statusHeight && top <= statusHeight + 16 * density)
            val bounds = rule.onNodeWithContentDescription(images.first().name).fetchSemanticsNode().boundsInRoot
            rule.onNodeWithText("Hide").performClick()
            rule.onNodeWithContentDescription("Back").assertDoesNotExist()
            assertEquals(bounds, rule.onNodeWithContentDescription(images.first().name).fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithContentDescription(images.first().name).performTouchInput { click() }
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Back").assertIsDisplayed()
            rule.onNodeWithContentDescription(images.first().name).performTouchInput { swipeLeft() }
            rule.waitUntil(5000) { vm.imagePath == images[1].path }
            rule.onNodeWithContentDescription(images[1].name).performTouchInput { swipeRight() }
            rule.waitUntil(5000) { vm.imagePath == images.first().path }
            rule.onNodeWithContentDescription(images.first().name).performTouchInput { swipeRight() }
            assertEquals(images.first().path, vm.imagePath)
            rule.onNodeWithContentDescription(images.first().name).performTouchInput { doubleClick() }
            rule.onNodeWithContentDescription(images.first().name).performTouchInput { swipeLeft() }
            assertEquals(images.first().path, vm.imagePath)
            rule.runOnIdle { vm.closeMedia() }
        }
    }

    @Test fun videoSwipeSeekWithoutHoldSpeedAndRotationRestore() {
        Fixture(makeVideo(context)).use { fixture ->
            val vm = connect(fixture)
            val video = vm.entries.first { it.isVideo() }
            rule.runOnIdle { vm.open(video) }
            rule.waitUntil(20_000) { rule.runOnIdle { (findMpv(rule.activity.window.decorView)?.getPropertyDouble("time-pos") ?: 0.0) > .2 } }
            val view = findMpvView(rule.activity.window.decorView)!!
            val player = view.mpv!!
            rule.waitUntil(5000) { rule.runOnIdle { view.keepScreenOn } }
            rule.onNodeWithTag("media-gestures").performTouchInput { down(androidx.compose.ui.geometry.Offset(width * .3f, height * .3f)) }
            rule.mainClock.advanceTimeBy(700)
            rule.runOnIdle { assertEquals(1.0, player.getPropertyDouble("speed")!!, .05) }
            rule.onNodeWithTag("media-gestures").performTouchInput { up() }
            rule.waitUntil(5000) { rule.runOnIdle { player.getPropertyDouble("speed") == 1.0 } }
            rule.runOnIdle {
                assertEquals(1.0, player.getPropertyDouble("speed")!!, 0.05)
                vm.playbackWanted = false
                player.setPropertyBoolean("pause", true)
                player.setPropertyDouble("time-pos", 1.2)
            }
            rule.waitUntil(5000) { rule.runOnIdle { !view.keepScreenOn } }
            rule.waitUntil(5000) { vm.playbackPosition >= 1200 }
            rule.onNodeWithTag("media-gestures").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(width * .25f, height * .45f))
                moveTo(androidx.compose.ui.geometry.Offset(width * .60f, height * .45f), 300)
                up()
            }
            rule.waitUntil(5000) { rule.runOnIdle { (player.getPropertyDouble("time-pos") ?: 0.0) > 3.0 } }
            var afterForward = 0.0
            rule.runOnIdle { afterForward = player.getPropertyDouble("time-pos")!! }
            rule.onNodeWithTag("media-gestures").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(width * .6f, height * .45f))
                moveTo(androidx.compose.ui.geometry.Offset(width * .4f, height * .45f), 300)
                up()
            }
            rule.waitUntil(5000) { rule.runOnIdle { (player.getPropertyDouble("time-pos") ?: 0.0) < afterForward - 1.5 } }
            rule.runOnIdle { player.setPropertyDouble("time-pos", 1.2) }
            rule.waitUntil(5000) { vm.playbackPosition >= 1200 }
            rule.onNodeWithContentDescription("Rotate screen").performClick()
            rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            rule.waitForIdle()
            assertEquals(video.path, model.videoPath)
            rule.waitUntil(20000) { rule.runOnIdle {
                (findMpv(rule.activity.window.decorView)?.getPropertyDouble("time-pos") ?: 0.0) >= 1.2
            } }
            assertTrue(model.playbackPosition >= 1200)
            rule.onNodeWithContentDescription("Rotate screen").performClick()
            rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            rule.waitUntil(20000) { rule.runOnIdle {
                findMpv(rule.activity.window.decorView)?.getPropertyDouble("duration") != null
            } }
            rule.runOnIdle {
                val current = findMpv(rule.activity.window.decorView)!!
                model.playbackWanted = true
                current.setPropertyDouble("time-pos", current.getPropertyDouble("duration")!! - .3)
                current.setPropertyBoolean("pause", false)
            }
            rule.waitUntil(10000) { rule.runOnIdle {
                findMpv(rule.activity.window.decorView)?.getPropertyBoolean("idle-active") == true &&
                    findMpvView(rule.activity.window.decorView)?.keepScreenOn == false
            } }
            rule.runOnIdle { model.closeMedia() }
        }
    }

    @Test fun remoteSelectionSearchCopyMoveAndRetryResults() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            rule.runOnIdle { if (vm.grid) vm.toggleGrid() }
            rule.onNodeWithContentDescription("Search").performClick()
            rule.onNodeWithText("Search this folder").performTextReplacement("a")
            rule.onNodeWithText("Done").performClick()
            rule.onNodeWithText("a+b.png").performTouchInput { longClick() }
            rule.onNodeWithContentDescription("Select a b.png").performClick()
            assertEquals(2, vm.selectedPaths.size)
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            assertEquals(2, model.selectedPaths.size)
            rule.onNodeWithText("Copy").performScrollTo().performClick()
            rule.onNodeWithText("sub").performClick()
            rule.waitUntil(10_000) { !model.loading && model.path == "/photos/sub" }
            fixture.failTransfer.set(true)
            rule.onNodeWithText("Paste").performClick()
            rule.onNodeWithText("Confirm paste").performClick()
            rule.waitUntil(10_000) { !model.operationBusy && model.operationResults.size == 2 && !model.loading }
            assertEquals(setOf("Copied", "Failed"), model.operationResults.map { it.status }.toSet())
            assertEquals(1, model.clipboard!!.files.size)
            rule.runOnIdle { assertTrue(model.canRetryResults); model.retryResults() }
            rule.waitUntil(10_000) { !model.operationBusy && model.operationResults.singleOrNull()?.status == "Copied" && !model.loading }
            assertTrue(fixture.files.containsAll(listOf("a+b.png", "a b.png")))
            assertTrue(fixture.destinationFiles.containsAll(listOf("a+b.png", "a b.png")))
            val moving = model.entries.first { it.name == "a+b.png" }
            rule.runOnIdle { model.stageFiles(true, listOf(moving)); model.navigate("/photos") }
            rule.waitUntil(10_000) { !model.loading }
            rule.runOnIdle { model.pasteFiles() }
            rule.waitUntil(10_000) { !model.operationBusy && !model.loading }
            assertEquals("Skipped", model.operationResults.single().status)
            assertTrue(fixture.destinationFiles.contains("a+b.png"))
            fixture.files.remove("a+b.png")
            rule.runOnIdle { model.pasteFiles() }
            rule.waitUntil(10_000) { !model.operationBusy && model.operationResults.singleOrNull()?.status == "Moved" }
            assertTrue(fixture.files.contains("a+b.png"))
            assertFalse(fixture.destinationFiles.contains("a+b.png"))
            rule.runOnIdle { model.clearClipboard() }
        }
    }

    @Test fun textEditorPreservesDraftOnRotationAndConflict() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            rule.runOnIdle { vm.open(vm.entries.first { it.name == "notes.txt" }) }
            rule.waitUntil(10_000) { !vm.textBusy && vm.textOriginal != null }
            rule.onNodeWithText("Text content").performTextReplacement("中文 draft +")
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            assertEquals("中文 draft +", model.textDraft)
            assertTrue(model.textDirty)
            fixture.textEtag = "\"text-v2\""
            rule.onNodeWithText("Save").performClick()
            rule.waitUntil(10_000) { !model.textBusy && model.textError != null }
            assertEquals("中文 draft +", model.textDraft)
            assertEquals("original text", fixture.textContent)
            rule.onNodeWithText("Reload").performClick()
            rule.onNodeWithText("Keep editing").performClick()
            assertTrue(model.textDirty)
            fixture.textEtag = "\"text-v1\""
            rule.onNodeWithText("Save").performClick()
            rule.waitUntil(10_000) { !model.textBusy && !model.textDirty }
            assertEquals("中文 draft +", fixture.textContent)
            assertNull(model.textError)
            rule.runOnIdle { model.closeMedia() }
        }
    }

    @Test fun audioPlaysSeeksRestoresAndPausesInBackground() {
        Fixture(audio = makeWave()).use { fixture ->
            val vm = connect(fixture)
            val audio = vm.entries.first { it.isAudio() }
            rule.runOnIdle { vm.open(audio) }
            rule.waitUntil(20_000) { vm.rangeSupported == true && vm.playbackPosition > 200 }
            val player = findMpv(rule.activity.window.decorView)!!
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val back = rule.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
            assertTrue(back.top >= safe.top)
            val play = rule.onNodeWithContentDescription("Pause").fetchSemanticsNode().boundsInRoot
            assertTrue(play.bottom <= rule.activity.window.decorView.height - safe.bottom)
            assertFalse(findMpvView(rule.activity.window.decorView)!!.keepScreenOn)
            rule.onNodeWithContentDescription("Pause").performClick()
            rule.waitUntil(5000) { rule.runOnIdle { player.getPropertyBoolean("pause") == true } }
            rule.onNodeWithTag("media-seek").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(width * .6f, height / 2f))
                moveTo(androidx.compose.ui.geometry.Offset(width * .7f, height / 2f))
            }
            val dragged = rule.onNodeWithTag("media-seek").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
            rule.mainClock.advanceTimeBy(1000)
            android.os.SystemClock.sleep(700) // Cross at least two real player polling ticks while holding.
            rule.waitForIdle()
            assertEquals(dragged, rule.onNodeWithTag("media-seek").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, .01f)
            rule.onNodeWithTag("media-seek").performTouchInput { up() }
            rule.onNodeWithContentDescription("Play").performClick()
            rule.runOnIdle { player.setPropertyDouble("time-pos", 1.5) }
            rule.waitUntil(10_000) { vm.playbackPosition >= 1500 }
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { assertEquals(true, player.getPropertyBoolean("pause")) }
            val position = vm.playbackPosition
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            assertEquals(audio.path, model.audioPath)
            rule.waitUntil(20000) { rule.runOnIdle {
                (findMpv(rule.activity.window.decorView)?.getPropertyDouble("time-pos") ?: 0.0) * 1000 >= position
            } }
            assertTrue(model.playbackWanted)
            rule.runOnIdle { model.closeMedia() }
        }
    }

    @Test fun thumbnailsDeleteIdentityAndRotation() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            val plus = vm.entries.first { it.name == "a+b.png" }
            val spaced = vm.entries.first { it.name == "a b.png" }
            val bitmap = runBlocking { ThumbnailCache.load(context, vm.client!!, plus) }
            assertTrue(maxOf(bitmap.width, bitmap.height) <= 320)
            val requests = fixture.imageRequests.get()
            val cached = runBlocking { ThumbnailCache.load(context, vm.client!!, plus) }
            assertSame(bitmap, cached)
            assertEquals(bitmap.width, cached.width)
            assertEquals(requests, fixture.imageRequests.get())
            rule.runOnIdle { vm.open(plus) }
            // Thumbnail requests can finish before the full image starts loading.
            // Wait for the fixture's image pixels, rather than a network request count.
            rule.waitUntil(10_000) {
                val frame = rule.onRoot().captureToImage().asAndroidBitmap()
                try {
                    frame.getPixel(frame.width / 3, frame.height / 2) == Color.rgb(28, 160, 180) &&
                        frame.getPixel(frame.width / 2, frame.height / 2) == Color.YELLOW
                } finally { frame.recycle() }
            }
            val shot = rule.onRoot().captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null), "image-viewer.png").outputStream().use {
                shot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertNotEquals(shot.getPixel(shot.width / 3, shot.height / 2), shot.getPixel(shot.width / 2, shot.height / 2))
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            assertEquals(plus.path, model.imagePath)
            assertEquals("/photos", model.path)
            fixture.blockDelete.set(true)
            rule.runOnIdle { vm.deleteImage(plus) }
            assertTrue(fixture.deleteStarted.await(5, TimeUnit.SECONDS))
            rule.runOnIdle { vm.selectImage(spaced); assertEquals(plus.path, vm.imagePath) }
            fixture.deleteRelease.countDown()
            rule.waitUntil(10_000) { !vm.deleting }
            assertFalse(vm.entries.any { it.path == plus.path })
            assertTrue(vm.entries.any { it.path == spaced.path })
            assertEquals("/dav/photos/a+b.png", fixture.deletedPath)
            assertTrue(fixture.files.contains("a b.png"))
            assertFalse(fixture.files.contains("a+b.png"))
            rule.runOnIdle { vm.closeMedia() }
        }
    }

    @Test fun lateDirectoryResponseCannotOverwriteNavigation() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            fixture.blockListing.set(true)
            rule.runOnIdle { vm.refresh() }
            assertTrue(fixture.listStarted.await(5, TimeUnit.SECONDS))
            rule.runOnIdle { vm.navigate("/photos/sub") }
            rule.waitUntil(10_000) { !vm.loading && vm.entries.singleOrNull()?.name == "inner.png" }
            fixture.listRelease.countDown()
            rule.waitForIdle()
            assertEquals("/photos/sub", vm.path)
            assertEquals(listOf("inner.png"), vm.entries.map { it.name })
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            assertEquals("/photos/sub", model.path)
            assertEquals(listOf("inner.png"), model.entries.map { it.name })
        }
    }

    @Test fun invalidConnectionDoesNotReplaceSavedConnection() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            val original = vm.connection!!
            rule.runOnIdle { vm.editConnection(); vm.connect(original.copy(baseUrl = "bad address")) }
            rule.waitUntil(5_000) { !vm.connecting }
            assertNotNull(vm.connectionError)
            assertEquals(original.copy(baseUrl = original.baseUrl.trimEnd('/')), runBlocking { ConnectionStore(context).load() })
            rule.runOnIdle { vm.cancelEditing() }
        }
    }

    @Test fun remotePathJumpIsTransactionalAndCancellationCannotUpdateAnotherTab() {
        Fixture().use { fixture ->
            val vm = connect(fixture)
            val original = vm.entries.map { it.path }
            rule.runOnIdle { vm.openPathEditor(); vm.updatePathInput("/outside"); vm.jumpToPath() }
            rule.waitUntil(10000) { !vm.pathJumpBusy }
            assertNotNull(vm.pathJumpError)
            assertEquals(original, vm.entries.map { it.path })
            rule.runOnIdle { vm.updatePathInput("/photos/sub"); vm.jumpToPath() }
            rule.waitUntil(10000) { !vm.pathJumpBusy }
            assertEquals("/photos/sub", vm.path)
            assertEquals(listOf("inner.png"), vm.entries.map { it.name })
            fixture.blockListing.set(true)
            rule.runOnIdle { vm.openPathEditor(); vm.updatePathInput("/photos"); vm.jumpToPath() }
            assertTrue(fixture.listStarted.await(5, TimeUnit.SECONDS))
            rule.runOnIdle { vm.dismissPathEditor(); vm.selectTab(3) }
            fixture.listRelease.countDown()
            rule.waitUntil(10000) { !vm.loading }
            assertEquals(3, vm.activeTab)
            assertFalse(vm.pathEditorVisible)
            assertFalse(vm.pathJumpBusy)
        }
    }

    @Test fun videoWithoutRangeShowsSeekingWarning() {
        val media = makeVideo(context)
        Fixture(media).use { fixture ->
            fixture.range.set(false)
            val vm = connect(fixture)
            val video = vm.entries.first { it.isVideo() }
            rule.runOnIdle { vm.open(video) }
            rule.waitUntil(10_000) { vm.rangeSupported == false }
            rule.waitUntil(10_000) { findMpvView(rule.activity.window.decorView) != null }
            rule.runOnIdle {
                vm.playbackWanted = false
                findMpv(rule.activity.window.decorView)!!.setPropertyBoolean("pause", true)
            }
            var before = 0.0
            rule.runOnIdle { before = findMpv(rule.activity.window.decorView)!!.getPropertyDouble("time-pos") ?: 0.0 }
            rule.onNodeWithTag("media-gestures").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(width * .25f, height * .45f))
                moveTo(androidx.compose.ui.geometry.Offset(width * .65f, height * .45f), 300)
                up()
            }
            rule.runOnIdle { assertEquals(before, findMpv(rule.activity.window.decorView)!!.getPropertyDouble("time-pos") ?: 0.0, .5) }
            rule.runOnIdle { vm.downloadVideo(video) }
            rule.waitUntil(20000) { !vm.downloading }
            assertNotNull(vm.localVideo)
            assertEquals(media.size.toLong(), vm.localVideo!!.length())
            assertTrue(vm.rangeSupported == true)
            rule.runOnIdle { model.closeMedia() }
        }
        media.fill(0)
    }

    private fun findMpv(view: View): MPV? = findMpvView(view)?.mpv

    private fun findMpvView(view: View): MpvTextureView? {
        if (view is MpvTextureView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findMpvView(view.getChildAt(i))?.let { return it }
        return null
    }
}

private class Fixture(private val video: ByteArray = ByteArray(0), private val audio: ByteArray = ByteArray(0)) : AutoCloseable {
    @Volatile var textContent = "original text"
    @Volatile var textEtag = "\"text-v1\""
    val server = MockWebServer()
    val files = java.util.concurrent.CopyOnWriteArrayList(listOf("a+b.png", "a b.png", "other.png"))
    val destinationFiles = java.util.concurrent.CopyOnWriteArrayList(listOf("inner.png"))
    val failTransfer = AtomicBoolean(false)
    val failListing = AtomicBoolean(false)
    val imageRequests = AtomicInteger()
    val blockDelete = AtomicBoolean(false)
    val deleteStarted = CountDownLatch(1)
    val deleteRelease = CountDownLatch(1)
    val blockListing = AtomicBoolean(false)
    val listStarted = CountDownLatch(1)
    val listRelease = CountDownLatch(1)
    val range = AtomicBoolean(true)
    @Volatile var deletedPath: String? = null
    private val image = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).let { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(28, 160, 180))
        canvas.drawRect(480f, 200f, 720f, 600f, Paint().apply { color = Color.YELLOW })
        ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
            .also { bitmap.recycle() }
    }
    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Basic dXNlcjpwYXNz") return MockResponse().setResponseCode(401)
                val path = request.requestUrl!!.pathSegments.joinToString("/", prefix = "/")
                return when (request.method) {
                    "PROPFIND" -> {
                        if (request.getHeader("Depth") == "1" && failListing.getAndSet(false)) return MockResponse().setResponseCode(503)
                        if (path == "/dav/photos/" && blockListing.getAndSet(false)) {
                            listStarted.countDown(); listRelease.await(5, TimeUnit.SECONDS)
                        }
                        val inspecting = request.getHeader("Depth") == "0"
                        val names = if (inspecting) {
                            val name = request.requestUrl!!.pathSegments.last { it.isNotEmpty() }
                            val known = if (path.startsWith("/dav/photos/sub/")) destinationFiles else files + "notes.txt" + "clip.mp4" + "song.wav"
                            if (!path.endsWith('/') && name !in known) return MockResponse().setResponseCode(404)
                            listOf(name + if (path.endsWith('/')) "/" else "")
                        } else if (path == "/dav/photos/sub/") destinationFiles.toList() else files.toList() + "sub/" + "notes.txt" +
                            (if (video.isNotEmpty()) listOf("clip.mp4") else emptyList()) +
                            (if (audio.isNotEmpty()) listOf("song.wav") else emptyList())
                        val body = names.joinToString("") { name ->
                            val url = if (inspecting) request.requestUrl!! else request.requestUrl!!.newBuilder().addPathSegment(name.trimEnd('/')).build()
                            val resource = if (name.endsWith('/')) "<d:collection/>" else ""
                            val size = if (name.endsWith("mp4")) video.size else image.size
                            """<d:response><d:href>${url.encodedPath}</d:href><d:propstat><d:prop>
                              <d:displayname>${name.trimEnd('/')}</d:displayname><d:getcontentlength>$size</d:getcontentlength>
                              <d:getetag>"fixture-v1"</d:getetag><d:resourcetype>$resource</d:resourcetype>
                              </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                        }
                        MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$body</d:multistatus>")
                    }
                    "DELETE" -> {
                        deletedPath = request.path
                        if (blockDelete.get()) { deleteStarted.countDown(); deleteRelease.await(5, TimeUnit.SECONDS) }
                        val removed = files.remove(request.requestUrl!!.pathSegments.last())
                        MockResponse().setResponseCode(if (removed) 204 else 404)
                    }
                    "GET" -> {
                        if (path.endsWith("notes.txt")) return MockResponse().setBody(textContent).setHeader("ETag", textEtag)
                        val isVideo = path.endsWith(".mp4")
                        val isAudio = path.endsWith(".wav")
                        if (!isVideo && !isAudio) imageRequests.incrementAndGet()
                        val payload = if (isVideo) video else if (isAudio) audio else image
                        val bytes = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore(',')?.split('-')
                        val response = MockResponse().setHeader("Content-Type", if (isVideo) "video/mp4" else if (isAudio) "audio/wav" else "image/png")
                        if (range.get() && bytes != null) {
                            val start = bytes[0].toInt()
                            val end = bytes.getOrNull(1)?.toIntOrNull()?.coerceAtMost(payload.lastIndex) ?: payload.lastIndex
                            response.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                                .setBody(Buffer().write(payload, start, end - start + 1))
                        } else response.setBody(Buffer().write(payload))
                    }
                    "PUT" -> {
                        if (request.getHeader("If-Match") != textEtag) return MockResponse().setResponseCode(412)
                        textContent = request.body.readUtf8()
                        textEtag = "\"text-saved\""
                        MockResponse().setResponseCode(204).setHeader("ETag", textEtag)
                    }
                    "MKCOL" -> {
                        files.add(request.requestUrl!!.pathSegments.last { it.isNotEmpty() } + "/")
                        MockResponse().setResponseCode(201)
                    }
                    "COPY", "MOVE" -> {
                        if (failTransfer.getAndSet(false)) return MockResponse().setResponseCode(403)
                        if (request.getHeader("Overwrite") != "F") return MockResponse().setResponseCode(400)
                        val destination = request.getHeader("Destination")!!.toHttpUrl()
                        val sourceFiles = if (path.startsWith("/dav/photos/sub/")) destinationFiles else files
                        val targetFiles = if (destination.encodedPath.startsWith("/dav/photos/sub/")) destinationFiles else files
                        val sourceName = request.requestUrl!!.pathSegments.last()
                        val targetName = destination.pathSegments.last()
                        if (!sourceFiles.contains(sourceName)) return MockResponse().setResponseCode(404)
                        if (targetFiles.contains(targetName)) return MockResponse().setResponseCode(412)
                        targetFiles.add(targetName)
                        if (request.method == "MOVE") sourceFiles.remove(sourceName)
                        MockResponse().setResponseCode(201)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        server.start()
    }
    override fun close() { deleteRelease.countDown(); listRelease.countDown(); server.close() }
}

internal fun makeWave(): ByteArray {
    val samples = 44100 * 12
    val buffer = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(samples * 2)
    repeat(samples) { buffer.putShort((kotlin.math.sin(it * 2.0 * Math.PI * 440 / 44100) * 1000).toInt().toShort()) }
    return buffer.array()
}

internal fun makeVideo(context: Context): ByteArray {
    val file = File(context.cacheDir, "fixture.mp4")
    val width = 320
    val height = 180
    val codec = MediaCodec.createEncoderByType("video/avc")
    val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started = false
    try {
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var track = -1
        var frame = 0
        var inputEnded = false
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            check(System.currentTimeMillis() < deadline) { "Video fixture encoding timed out" }
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    if (frame == 225) {
                        codec.queueInputBuffer(inputIndex, 0, 0, frame * 1_000_000L / 15, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        val data = ByteArray(width * height * 3 / 2) { if (it < width * height) 90.toByte() else 128.toByte() }
                        val left = frame * 3 % (width - 50)
                        for (y in 50 until 100) for (x in left until left + 50) data[y * width + x] = 220.toByte()
                        codec.getInputBuffer(inputIndex)!!.apply { clear(); put(data) }
                        codec.queueInputBuffer(inputIndex, 0, data.size, frame * 1_000_000L / 15, 0)
                        frame++
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
            } else if (outputIndex >= 0) {
                val buffer = codec.getOutputBuffer(outputIndex)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, buffer, info)
                val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(outputIndex, false)
                if (ended) break
            }
        }
        muxer.stop(); started = false
        return file.readBytes()
    } finally {
        codec.release()
        if (started) muxer.stop()
        muxer.release()
        file.delete()
    }
}
