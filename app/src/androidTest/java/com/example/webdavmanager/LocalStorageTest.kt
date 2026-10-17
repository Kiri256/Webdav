package com.example.webdavmanager

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import android.view.View
import android.view.ViewGroup
import `is`.xyz.mpv.MPV
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalStorageTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val model get() = ViewModelProvider(rule.activity)[BrowserViewModel::class.java]

    @Test fun localTabsKeepIndependentDirectoriesAndClipboard() {
        val storage = select()
        rule.runOnIdle { model.selectTab(0) }
        rule.waitUntil(10000) { !model.localLoading }
        runBlocking {
            storage.create(storage.root.toString(), "tab-folder", true)
            storage.create(storage.root.toString(), "source.txt", false)
        }
        rule.runOnIdle { model.refreshLocal() }
        rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 2 }
        val child = model.localEntries.first { it.isDirectory }
        rule.runOnIdle { model.navigateLocal(child.path) }
        rule.waitUntil(10000) { !model.localLoading }
        rule.runOnIdle { model.setSearch("first-tab"); model.selectTab(1) }
        rule.waitUntil(10000) { !model.localLoading }
        rule.runOnIdle {
            assertEquals(storage.root.toString(), model.localDirectory)
            assertEquals("", model.searchQuery)
            model.stageFiles(false, listOf(model.localEntries.first { !it.isDirectory }))
            model.selectTab(0)
        }
        rule.waitUntil(10000) { !model.localLoading }
        rule.runOnIdle {
            assertEquals(child.path, model.localDirectory)
            assertEquals("first-tab", model.searchQuery)
            assertTrue(model.canPaste)
            model.clearClipboard()
            model.setSearch("")
        }
    }

    @Test fun folderScrollRestoresAfterNavigationRecreationAndFreshViewModel() {
        val storage = select()
        rule.runOnIdle {
            if (model.grid) model.toggleGrid()
            model.setSearch("")
            model.updateSortMode(FileSortMode.NAME)
        }
        runBlocking {
            storage.create(storage.root.toString(), "child", true)
            repeat(60) { storage.create(storage.root.toString(), "item-%02d.txt".format(it), false) }
        }
        rule.runOnIdle { model.refreshLocal() }
        rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 61 }
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)).performScrollToIndex(30)
        val key = model.folderPositionKey("list")
        rule.waitUntil(5000) { model.folderPosition(key).index >= 29 }
        val position = model.folderPosition(key)
        val child = model.localEntries.first { it.isDirectory }
        rule.runOnIdle { model.navigateLocal(child.path) }
        rule.waitUntil(10000) { !model.localLoading }
        rule.runOnIdle { model.localUp() }
        rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 61 }
        rule.onNodeWithText("item-29.txt").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("item-29.txt").assertIsDisplayed()
        rule.waitUntil(10000) { runBlocking { FolderPositionsStore(context).load()[key] }?.path == position.path }
        lateinit var fresh: BrowserViewModel
        val owner = androidx.lifecycle.ViewModelStore()
        rule.runOnIdle { fresh = BrowserViewModel(rule.activity.application, androidx.lifecycle.SavedStateHandle()); owner.put("fresh", fresh) }
        try {
            rule.waitUntil(10000) { !fresh.starting }
            assertEquals(position.path, fresh.folderPosition(key).path)
        } finally { rule.runOnIdle { owner.clear(); model.selectTab(2) } }
    }

    private fun select(prefix: String = ""): LocalStorage {
        val rootId = "fixtures/$prefix${UUID.randomUUID()}"
        val tree = DocumentsContract.buildTreeDocumentUri("com.example.webdavmanager.test.documents", rootId)
        rule.waitUntil(10_000) { !model.starting }
        context.startActivity(Intent().setClassName("com.example.webdavmanager.test", LocalTestDocumentsProvider.GrantActivity::class.java.name)
            .putExtra("root", rootId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        rule.waitUntil(10_000) {
            context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        rule.waitForIdle()
        rule.runOnIdle { model.selectTab(0); model.selectLocalFolder(tree) }
        rule.waitUntil(10_000) { !model.localBusy && !model.localLoading }
        assertNull(model.localError)
        assertEquals(tree, model.localStorage!!.tree)
        return model.localStorage!!
    }

    @Test fun fallbackProviderTraversalChecksBoundaryAndCanCancel() = runBlocking {
        val storage = select("fallback-slow-scan-")
        val root = storage.root.toString()
        repeat(40) { storage.create(root, "folder-$it", true) }
        val folder = storage.list(root).first()
        storage.create(folder.path, "child + 中文.txt", false)
        assertEquals("child + 中文.txt", storage.list(folder.path).single().name)
        val treeId = DocumentsContract.getTreeDocumentId(storage.tree)
        val outside = DocumentsContract.buildDocumentUriUsingTree(storage.tree, "fixtures/outside-$treeId").toString()
        try { storage.stat(outside); fail("Outside tree") } catch (_: IllegalArgumentException) { }
        val missing = DocumentsContract.buildDocumentUriUsingTree(storage.tree, "$treeId/missing").toString()
        val job = async(kotlinx.coroutines.Dispatchers.IO) { storage.stat(missing) }
        kotlinx.coroutines.delay(30)
        kotlinx.coroutines.withTimeout(2000) { job.cancel(); job.join() }
        assertTrue(job.isCancelled)
    }

    @Test fun localImagesSwipeInBothDirections() {
        val storage = select()
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            for (name in listOf("one.png", "two.png")) {
                val uri = DocumentsContract.createDocument(context.contentResolver, storage.root, "image/png", name)!!
                context.contentResolver.openOutputStream(uri, "rwt")!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { bitmap.recycle() }
        rule.runOnIdle { model.refreshLocal() }
        rule.waitUntil(10_000) { !model.localLoading && model.localEntries.size == 2 }
        rule.onNodeWithText("one.png").performClick()
        rule.onNodeWithContentDescription("one.png").performTouchInput { swipeLeft() }
        rule.waitUntil(5000) { model.imagePath == model.localEntries[1].path }
        rule.onNodeWithContentDescription("two.png").performTouchInput { swipeRight() }
        rule.waitUntil(5000) { model.imagePath == model.localEntries[0].path }
        rule.runOnIdle { model.closeMedia(); model.selectTab(2) }
    }

    @Test fun localCrudTextDraftAndNavigationRestore() {
        val storage = select()
        rule.runOnIdle { model.createLocal("中文 + space", true) }
        rule.waitUntil(10_000) { !model.localBusy && model.localEntries.any { it.name == "中文 + space" } }
        val folder = model.localEntries.first()
        rule.onNodeWithText("中文 + space").performClick()
        rule.waitUntil(10_000) { !model.localLoading && model.localDirectory == folder.path }
        rule.runOnIdle { model.createLocal("a+b 中文.txt", false) }
        rule.waitUntil(10_000) { !model.localBusy && model.localEntries.size == 1 }
        rule.onNodeWithText("a+b 中文.txt").performClick()
        rule.waitUntil(10_000) { !model.textBusy && model.textOriginal != null }
        rule.onNodeWithText("Text content").performTextReplacement("local draft 中文 +")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals("local draft 中文 +", model.textDraft)
        assertEquals(folder.path, model.localDirectory)
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { !model.textBusy && !model.textDirty }
        val text = model.localEntries.single()
        assertEquals("local draft 中文 +", runBlocking { storage.readText(text.path) }.content)
        assertEquals(runBlocking { storage.stat(text.path) }, text)
        rule.runOnIdle { model.closeMedia(); model.renameLocal(text, "new + 中文.txt") }
        rule.waitUntil(10_000) { !model.localBusy && model.localEntries.singleOrNull()?.name == "new + 中文.txt" }
        val renamed = model.localEntries.single()
        rule.runOnIdle { model.deleteLocal(renamed) }
        rule.waitUntil(10_000) { !model.localBusy && !model.localLoading && model.localEntries.isEmpty() }
        rule.runOnIdle { model.localUp() }
        rule.waitUntil(10_000) { !model.localLoading && model.localDirectory == storage.root.toString() }
        assertEquals(1, model.localEntries.size)
        rule.runOnIdle { model.selectTab(2) }
    }

    @Test fun localTextConflictAndDirectoryBoundaries() = runBlocking {
        val storage = select()
        val root = storage.root.toString()
        storage.create(root, "a + 中文.txt", false)
        val file = storage.list(root).single()
        val old = storage.readText(file.path)
        storage.saveText(file.path, "changed elsewhere", old)
        try { storage.saveText(file.path, "stale draft", old); fail("Expected conflict") }
        catch (_: IOException) { }
        assertEquals("changed elsewhere", storage.readText(file.path).content)
        for (name in listOf("..", "a/b", "a\\b", "\u0000")) {
            try { storage.create(root, name, true); fail("Invalid name") }
            catch (_: IllegalArgumentException) { }
        }
        val outside = DocumentsContract.buildDocumentUriUsingTree(storage.tree, "other-root/file").toString()
        try { storage.readText(outside); fail("Outside root") }
        catch (e: Exception) { assertTrue(e is IllegalArgumentException || e is SecurityException) }
        try { storage.delete(file); fail("Stale deletion must be rejected") } catch (_: IOException) { }
        storage.delete(storage.stat(file.path))
        rule.runOnIdle { model.selectTab(2) }
    }

    @Test fun localMultiSelectSearchPropertiesCopyConflictAndMove() {
        val storage = select()
        val root = storage.root.toString()
        val seeded = runBlocking {
            storage.create(root, "target", true)
            storage.create(root, "a+b 中文.txt", false)
            storage.create(root, "another.txt", false)
            val files = storage.list(root)
            for (file in files.filterNot { it.isDirectory }) storage.saveText(file.path, "content:${file.name}", storage.readText(file.path))
            val target = files.first { it.isDirectory }
            storage.create(target.path, "a+b 中文.txt", false)
            val existing = storage.list(target.path).single()
            storage.saveText(existing.path, "existing destination", storage.readText(existing.path))
            storage.list(root)
        }
        rule.runOnIdle { model.refreshLocal() }
        rule.waitUntil(10_000) { !model.localLoading && model.localEntries.size == 3 }
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithText("Search this folder").performTextReplacement("a")
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("a+b 中文.txt").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Select another.txt").performClick()
        assertEquals(2, model.selectedPaths.size)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals(2, model.selectedPaths.size)
        assertEquals("a", model.searchQuery)
        rule.onNodeWithText("Properties").performScrollTo().performClick()
        rule.onNodeWithText("Properties · 2 item(s)").assertExists()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Copy").performScrollTo().performClick()
        rule.onNodeWithText("target").performClick()
        rule.waitUntil(10_000) { !model.localLoading && model.localParents.size == 1 }
        rule.onNodeWithText("Paste").performClick()
        rule.onNodeWithText("Confirm paste").performClick()
        rule.waitUntil(15_000) { !model.operationBusy && model.operationResults.size == 2 && !model.localLoading }
        assertEquals(setOf("Skipped", "Copied"), model.operationResults.map { it.status }.toSet())
        val target = seeded.first { it.isDirectory }
        assertEquals("existing destination", runBlocking { storage.readText(storage.list(target.path).first { it.name == "a+b 中文.txt" }.path) }.content)
        rule.runOnIdle { model.pasteFiles() }
        rule.waitUntil(15_000) { !model.operationBusy && !model.localLoading }
        assertEquals("Skipped", model.operationResults.single().status)
        assertFalse(model.localEntries.any { it.name.contains("(1)") })
        // Resolve the conflict explicitly, then move without changing the source name.
        runBlocking { storage.delete(storage.list(target.path).first { it.name == "another.txt" }) }
        rule.runOnIdle { model.localUp() }
        rule.waitUntil(10_000) { !model.localLoading && model.localParents.isEmpty() }
        val moving = model.localEntries.first { it.name == "another.txt" }
        rule.runOnIdle { model.stageFiles(true, listOf(moving)); model.navigateLocal(target.path) }
        rule.waitUntil(10_000) { !model.localLoading }
        rule.runOnIdle { model.pasteFiles() }
        rule.waitUntil(15_000) { !model.operationBusy && model.operationResults.singleOrNull()?.status == "Moved" }
        assertFalse(runBlocking { storage.list(root) }.any { it.path == moving.path })
        assertEquals("content:another.txt", runBlocking { storage.readText(storage.list(target.path).first { it.name == "another.txt" }.path) }.content)
        rule.runOnIdle { model.selectTab(2) }
    }

    @Test fun localFolderCopyMoveRejectsDescendantsAndPreservesContent() = runBlocking {
        val storage = select()
        val root = storage.root.toString()
        storage.create(root, "source", true)
        storage.create(root, "target", true)
        var folder = storage.list(root).first { it.name == "source" }
        val target = storage.list(root).first { it.name == "target" }
        storage.create(folder.path, "nested", true)
        val nested = storage.list(folder.path).single()
        storage.create(nested.path, "中文 +.txt", false)
        val leaf = storage.list(nested.path).single()
        storage.saveText(leaf.path, "nested bytes", storage.readText(leaf.path))
        folder = storage.list(root).first { it.name == "source" }
        try { storage.transfer(folder, root, nested.path, "source", true); fail("Descendant target") }
        catch (_: IllegalArgumentException) { }
        storage.transfer(folder, root, target.path, "source", false)
        assertTrue(storage.list(root).any { it.path == folder.path })
        val outcome = storage.transfer(folder, root, target.path, "moved", true)
        assertFalse(outcome.sourceDeleted)
        // SAF has no atomic empty-directory deletion; empty source folders remain available for review.
        assertTrue(storage.list(root).any { it.path == folder.path })
        assertTrue(storage.list(nested.path).isEmpty())
        val moved = storage.list(target.path).first { it.name == "moved" }
        val movedLeaf = storage.list(storage.list(moved.path).single().path).single()
        assertEquals("nested bytes", storage.readText(movedLeaf.path).content)
        rule.runOnIdle { model.selectTab(2) }
    }

    @Test fun cancelledLocalMoveRemovesPartialDestinationAndRetainsSource() {
        val storage = select()
        val root = storage.root.toString()
        val document = DocumentsContract.createDocument(context.contentResolver, storage.root, "application/octet-stream", "slow.bin")!!
        context.contentResolver.openOutputStream(document, "rwt")!!.use { it.write(ByteArray(512 * 1024) { 65 }) }
        runBlocking { storage.create(root, "target", true) }
        rule.runOnIdle { model.refreshLocal() }
        rule.waitUntil(10_000) { !model.localLoading && model.localEntries.size == 2 }
        val file = model.localEntries.first { !it.isDirectory }
        val target = model.localEntries.first { it.isDirectory }
        rule.runOnIdle { model.stageFiles(true, listOf(file)); model.navigateLocal(target.path) }
        rule.waitUntil(10_000) { !model.localLoading }
        rule.runOnIdle { model.pasteFiles() }
        rule.waitUntil(10_000) { File(context.cacheDir, "file_transfers").listFiles().orEmpty().any { File(it, "data").length() > 0 } }
        rule.runOnIdle { model.cancelFileOperation() }
        rule.waitUntil(10_000) { !model.operationBusy }
        assertTrue(runBlocking { storage.list(root) }.any { it.path == file.path })
        assertTrue(runBlocking { storage.list(target.path) }.isEmpty())
        assertEquals("Cancelled", model.operationResults.single().status)
        rule.runOnIdle { model.selectTab(2) }
    }

    @Test fun localAudioUsesContentUriAndPausesInBackground() {
        val storage = select()
        val uri = DocumentsContract.createDocument(context.contentResolver, storage.root, "audio/wav", "song.wav")!!
        context.contentResolver.openOutputStream(uri, "rwt")!!.use { it.write(makeWave()) }
        rule.runOnIdle { model.refreshLocal() }
        rule.waitUntil(10_000) { !model.localLoading && model.localEntries.any { it.isAudio() } }
        rule.onNodeWithText("song.wav").performClick()
        rule.waitUntil(20_000) { model.playbackPosition > 200 }
        val player = findMpv(rule.activity.window.decorView)!!
        rule.runOnIdle { player.setPropertyDouble("time-pos", 1.5) }
        rule.waitUntil(10_000) { model.playbackPosition >= 1500 }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { assertEquals(true, player.getPropertyBoolean("pause")) }
        val position = model.playbackPosition
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals(uri.toString(), model.audioPath)
        assertTrue(model.playbackPosition >= position)
        rule.runOnIdle { model.closeMedia(); model.selectTab(2) }
    }

    @Test fun pathJumpKeepsListingOnFailureAndSupportsAuthorizedDocumentDirectories() {
        val storage = select()
        runBlocking { storage.create(storage.root.toString(), "中文 + %2F", true) }
        rule.runOnIdle { model.refreshLocal() }
        rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 1 }
        val child = model.localEntries.single()
        rule.runOnIdle { model.openPathEditor(); model.updatePathInput("/outside"); model.jumpToPath() }
        rule.waitUntil(10000) { !model.pathJumpBusy }
        assertNotNull(model.pathJumpError)
        assertEquals(child.path, model.localEntries.single().path)
        assertEquals(storage.root.toString(), model.localDirectory)
        rule.runOnIdle { model.updatePathInput(child.path); model.jumpToPath() }
        rule.waitUntil(10000) { !model.pathJumpBusy }
        assertNull(model.pathJumpError)
        assertEquals(child.path, model.localDirectory)
        assertFalse(model.pathEditorVisible)
        rule.runOnIdle { model.openPathEditor(); model.updatePathInput(storage.root.toString()); model.dismissPathEditor() }
        assertEquals(child.path, model.localDirectory)
        rule.runOnIdle { model.selectTab(2) }
    }

    @Test fun sameDirectorySubtitleSwitchFailureOffsetAndCleanup() {
        val storage = select()
        val audio = DocumentsContract.createDocument(context.contentResolver, storage.root, "audio/wav", "字幕 + song.wav")!!
        context.contentResolver.openOutputStream(audio, "rwt")!!.use { it.write(makeWave()) }
        val subtitle = DocumentsContract.createDocument(context.contentResolver, storage.root, "application/x-subrip", "字幕 + song.srt")!!
        context.contentResolver.openOutputStream(subtitle, "rwt")!!.use { it.write("1\n00:00:00,000 --> 00:00:10,000\n中文 subtitle\n".toByteArray()) }
        rule.runOnIdle { model.subtitles.setAutomatic(true); model.refreshLocal() }
        rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 2 }
        rule.runOnIdle { model.open(model.localEntries.first { it.isAudio() }) }
        rule.waitUntil(20000) { model.subtitles.candidates.isNotEmpty() && !model.subtitles.busy }
        assertEquals(model.subtitles.error, "字幕 + song.srt", model.subtitles.currentName)
        rule.runOnIdle { model.subtitles.load(model.subtitles.candidates.single()) }
        rule.waitUntil(10000) { !model.subtitles.busy }
        rule.runOnIdle { model.subtitles.updateEncoding("UTF-8") }
        rule.waitUntil(10000) { !model.subtitles.busy }
        assertNull(model.subtitles.error)
        assertEquals("UTF-8", model.subtitles.encoding)
        val player = findMpv(rule.activity.window.decorView)!!
        var original = ""
        rule.runOnIdle {
            original = player.getPropertyString("sid")!!
            assertEquals(1, player.getPropertyNode("track-list")!!.asArray()!!.count { it["type"]?.asString() == "sub" })
            assertEquals("true", player.getPropertyBoolean("sub-visibility")?.toString())
            assertTrue(player.getPropertyNode("track-list")!!.asArray()!!.any {
                it["type"]?.asString() == "sub" && it["selected"]?.asBoolean() == true
            })
            model.subtitles.updateOffset(1.5)
            assertEquals(1.5, player.getPropertyDouble("sub-delay")!!, .01)
            model.subtitles.loadLocal(android.net.Uri.parse("content://com.example.webdavmanager.test.documents/missing"))
        }
        rule.waitUntil(10000) { !model.subtitles.busy }
        assertNotNull(model.subtitles.error)
        rule.runOnIdle {
            assertEquals(original, player.getPropertyString("sid"))
            model.subtitles.selectTrack(null, "Off")
            assertEquals("no", player.getPropertyString("sid"))
            model.closeMedia()
        }
        rule.waitForIdle()
        assertTrue(File(context.cacheDir, "subtitles").listFiles().orEmpty().isEmpty())
        rule.runOnIdle { model.selectTab(2) }
    }

    @Test fun pickedSubtitleDisplaysTextOnAnAttachedVideoSurface() {
        val storage = select()
        val video = DocumentsContract.createDocument(context.contentResolver, storage.root, "video/mp4", "movie.mp4")!!
        context.contentResolver.openOutputStream(video, "rwt")!!.use { it.write(makeVideo(context)) }
        val subtitle = DocumentsContract.createDocument(context.contentResolver, storage.root, "application/x-ass", "captions.ass")!!
        context.contentResolver.openOutputStream(subtitle, "rwt")!!.use {
            it.write("""[Script Info]
ScriptType: v4.00+

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:10.00,Default,,0,0,0,,VISIBLE SUBTITLE CHECK
""".toByteArray())
        }
        rule.runOnIdle { model.subtitles.setAutomatic(false); model.refreshLocal() }
        rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 2 }
        rule.runOnIdle { model.open(model.localEntries.single { it.isVideo() }) }
        rule.waitUntil(20000) { model.playbackPosition > 500 && findMpv(rule.activity.window.decorView) != null }
        rule.runOnIdle { model.subtitles.loadLocal(subtitle) }
        rule.waitUntil(10000) {
            val player = findMpv(rule.activity.window.decorView) ?: return@waitUntil false
            !model.subtitles.busy && player.getPropertyString("sub-text")?.contains("VISIBLE SUBTITLE CHECK") == true
        }
        val player = findMpv(rule.activity.window.decorView)!!
        assertTrue(player.getPropertyBoolean("sub-visibility") == true)
        assertTrue(player.getPropertyString("sid") != "no")
        rule.runOnIdle { model.closeMedia(); model.selectTab(2) }
    }

    @Test fun subtitleRetryDoesNotCrossMediaAndSlowMetadataCanBeCancelled() {
        val storage = select()
        val first = DocumentsContract.createDocument(context.contentResolver, storage.root, "audio/wav", "first.wav")!!
        val second = DocumentsContract.createDocument(context.contentResolver, storage.root, "audio/wav", "second.wav")!!
        for (uri in listOf(first, second)) context.contentResolver.openOutputStream(uri, "rwt")!!.use { it.write(makeWave()) }
        val captions = DocumentsContract.createDocument(context.contentResolver, storage.root, "application/x-subrip", "captions.srt")!!
        context.contentResolver.openOutputStream(captions, "rwt")!!.use { it.write("1\n00:00:00,000 --> 00:00:12,000\nFIRST ONLY\n".toByteArray()) }
        val slow = DocumentsContract.createDocument(context.contentResolver, storage.root, "application/x-ass", "slow-subtitle.ass")!!
        context.contentResolver.openOutputStream(slow, "rwt")!!.use { it.write("invalid subtitle".toByteArray()) }
        rule.runOnIdle { model.subtitles.setAutomatic(false); model.refreshLocal() }
        rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 4 }
        rule.runOnIdle { model.open(model.localEntries.first { it.path == first.toString() }) }
        rule.waitUntil(10000) { model.playbackPosition > 200 && findMpv(rule.activity.window.decorView) != null }
        rule.runOnIdle { model.subtitles.loadLocal(captions) }
        rule.waitUntil(10000) { !model.subtitles.busy && model.subtitles.currentName == "captions.srt" }
        var previousSid = ""
        rule.runOnIdle {
            val player = findMpv(rule.activity.window.decorView)!!
            previousSid = player.getPropertyString("sid")!!
            model.subtitles.loadLocal(slow)
            // Simulate mpv changing selection while the asynchronous load is pending.
            player.setPropertyString("sid", "no")
            model.subtitles.cancel()
            assertEquals(previousSid, player.getPropertyString("sid"))
            model.subtitles.loadLocal(slow)
            model.subtitles.selectTrack(null, "Off")
        }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1800) }
        rule.runOnIdle {
            assertEquals("no", findMpv(rule.activity.window.decorView)!!.getPropertyString("sid"))
            model.subtitles.selectTrack(previousSid.toInt(), "captions.srt")
        }
        rule.runOnIdle { model.open(model.localEntries.first { it.path == second.toString() }) }
        rule.waitUntil(10000) { model.playbackPosition > 200 && model.subtitles.currentName == "Embedded / none" }
        rule.runOnIdle {
            model.subtitles.updateEncoding("UTF-8")
            model.subtitles.retry()
            assertFalse(model.subtitles.busy)
            assertEquals("Embedded / none", model.subtitles.currentName)
            assertTrue(findMpv(rule.activity.window.decorView)!!.getPropertyNode("track-list")!!.asArray()!!.none { it["type"]?.asString() == "sub" })
            val started = android.os.SystemClock.elapsedRealtime()
            model.subtitles.loadLocal(slow)
            assertTrue("Metadata must not block the UI", android.os.SystemClock.elapsedRealtime() - started < 500)
            assertTrue(model.subtitles.busy)
            model.subtitles.cancel()
        }
        // Let the blocked provider finish; its cancelled response must not mount a track.
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1800) }
        rule.runOnIdle {
            assertFalse(model.subtitles.busy)
            assertEquals("Embedded / none", model.subtitles.currentName)
            assertNull(model.subtitles.error)
            model.closeMedia(); model.selectTab(2)
        }
        rule.waitForIdle()
        assertTrue(File(context.cacheDir, "subtitles").listFiles().orEmpty().isEmpty())
    }

    private fun findMpv(view: View): MPV? {
        if (view is MpvTextureView) return view.mpv
        if (view is ViewGroup) for (i in 0 until view.childCount) findMpv(view.getChildAt(i))?.let { return it }
        return null
    }
}

