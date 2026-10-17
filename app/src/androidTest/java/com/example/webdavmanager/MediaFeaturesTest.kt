package com.example.webdavmanager

import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaFeaturesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[BrowserViewModel::class.java]
    private fun temp() = File(rule.activity.cacheDir.canonicalFile, "media-test-${UUID.randomUUID()}").apply { mkdir() }

    @Test fun internalStorageAndLandscapeBrowser() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set ${rule.activity.packageName} MANAGE_EXTERNAL_STORAGE allow").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
        rule.waitUntil(10000) { !model.starting }
        rule.runOnIdle { model.selectTab(0) }
        rule.waitUntil(10000) { !model.localLoading }
        assertEquals("file", model.localStorage!!.root.scheme)
        assertEquals(android.os.Environment.getExternalStorageDirectory().path, model.localStorage!!.root.path)
        assertEquals(model.localStorage!!.root.toString(), model.localDirectory)
        assertNull(model.localError)
        rule.onNodeWithText("Select folder").assertDoesNotExist()
        rule.onNodeWithContentDescription("Storage access").assertIsDisplayed()
        try {
            rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            rule.onNodeWithText("Search this folder").assertDoesNotExist()
            rule.onNodeWithContentDescription("Search").performClick()
            rule.onNodeWithText("Search this folder").assertIsDisplayed()
            rule.onNodeWithText("Done").performClick()
        } finally {
            rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; model.selectTab(2) }
        }
    }

    @Test fun directJumpPathsPreserveLiteralNamesAndRejectSymlinksAndBoundary() {
        val directory = temp()
        val root = Uri.fromFile(directory).toString()
        try {
            for (name in listOf("a space", "a+b", "中文", "a%2Fb")) {
                val child = File(directory, name).apply { mkdir() }
                assertEquals(child, DirectFiles.file(localJumpUri(root, child.path)))
                assertEquals(listOf(root), localParentChain(root, Uri.fromFile(child).toString()))
            }
            val link = File(directory, "link")
            Files.createSymbolicLink(link.toPath(), directory.parentFile!!.toPath())
            for (path in listOf(link.path, directory.path + "-outside", directory.path + "/../outside")) {
                try { localJumpUri(root, path); fail("Unsafe jump") } catch (_: IllegalArgumentException) { }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun previouslySavedLinuxRootMigratesToInternalStorage() {
        lateinit var restored: BrowserViewModel
        val owner = androidx.lifecycle.ViewModelStore()
        rule.runOnIdle {
            restored = BrowserViewModel(rule.activity.application, androidx.lifecycle.SavedStateHandle(mapOf(
                "localMode" to true, "localDirectory" to "file:///", "localParents" to arrayListOf("file:///"))))
            owner.put("restored", restored)
        }
        try {
            rule.waitUntil(10000) { !restored.starting && !restored.localLoading }
            assertEquals(android.os.Environment.getExternalStorageDirectory().path, Uri.parse(restored.localDirectory).path)
            assertTrue(restored.localParents.isEmpty())
        } finally { rule.runOnIdle { owner.clear() } }
    }

    @Test fun directCopyMoveTextAndSymlinkBoundaries() = runBlocking {
        val directory = temp()
        try {
            val storage = LocalStorage(rule.activity, Uri.fromFile(File("/")))
            val root = Uri.fromFile(directory).toString()
            storage.create(root, "中文 + space.txt", false)
            val source = storage.list(root).single()
            storage.saveText(source.path, "UTF-8 中文 +", storage.readText(source.path))
            val refreshed = storage.list(root).single()
            storage.create(root, "target", true)
            val target = storage.list(root).first { it.isDirectory }
            storage.transfer(refreshed, root, target.path, "encoded%2F + 中文.txt", false)
            val copy = storage.list(target.path).single()
            assertEquals("encoded%2F + 中文.txt", copy.name)
            assertEquals("UTF-8 中文 +", storage.readText(copy.path).content)
            storage.transfer(copy, target.path, root, "moved.txt", true)
            assertTrue(storage.list(target.path).isEmpty())
            assertTrue(storage.list(root).any { it.name == "moved.txt" })
            val link = File(directory, "link")
            Files.createSymbolicLink(link.toPath(), File(directory, "target").toPath())
            try {
                storage.create(Uri.fromFile(link).toString(), "blocked.txt", false)
                fail("Writes through symbolic links must be rejected")
            } catch (_: IllegalArgumentException) { }
            try { DirectFiles.file(Uri.fromFile(File(directory, "../outside")).toString()); fail("Traversal must be rejected") }
            catch (_: IllegalArgumentException) { }
            link.delete()
            storage.delete(storage.list(root).first { it.name == "target" })
        } finally { directory.deleteRecursively() }
    }

    @Test fun animatedGifDecodesAsAnimation() = runBlocking {
        val directory = temp()
        try {
            val gif = File(directory, "animated.gif")
            val header = "47494638396101000100800000ff00000000ff21ff0b".hexBytes()
            gif.writeBytes(header + "NETSCAPE2.0".toByteArray() + "030100000021f904000a0000002c000000000100010000020244010021f904000a0000002c00000000010001000002024c01003b".hexBytes())
            val result = rule.activity.imageLoader.execute(ImageRequest.Builder(rule.activity).data(gif).build())
            assertTrue(result is SuccessResult)
            val drawable = (result as SuccessResult).drawable
            assertTrue(drawable is Animatable)
            rule.runOnIdle { (drawable as Animatable).start(); assertTrue(drawable.isRunning); drawable.stop() }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelledDirectCopyRemovesPartialDestination() = runBlocking {
        val directory = temp()
        try {
            val source = File(directory, "large.bin")
            java.io.RandomAccessFile(source, "rw").use { it.setLength(64L * 1024 * 1024) }
            val storage = LocalStorage(rule.activity, Uri.fromFile(File("/")))
            val root = Uri.fromFile(directory).toString()
            val entry = storage.list(root).single()
            val destination = File(directory, "copy.bin")
            val job = launch(Dispatchers.IO) { storage.transfer(entry, root, root, destination.name, false) }
            withTimeout(10000) {
                while (File(rule.activity.cacheDir, "file_transfers").listFiles().orEmpty().none { File(it, "data").length() > 0 }) delay(1)
            }
            job.cancelAndJoin()
            assertTrue(source.exists())
            assertFalse(destination.exists())
        } finally { directory.deleteRecursively() }
    }

}

private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
