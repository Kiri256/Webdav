package com.example.webdavmanager

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun directory() = File(context.cacheDir.canonicalFile, "safety-${UUID.randomUUID()}").apply { mkdir() }

    @Test fun replacedFileAndNewDirectoryChildrenAreNeverDeletedByOldSnapshot() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source.txt").apply { writeText("old") }
            val expected = DirectFiles.entry(source)
            Files.move(source.toPath(), File(root, "old.txt").toPath())
            source.writeText("new")
            source.setLastModified(expected.lastModified!!.toLong())
            try { DirectFiles.deleteVerified(expected); fail("Replacement must be retained") } catch (_: java.io.IOException) { }
            assertEquals("new", source.readText())
            val folder = File(root, "folder").apply { mkdir() }
            val planned = DirectFiles.entry(folder)
            File(folder, "new.txt").writeText("added after verification")
            try { DirectFiles.deleteEmpty(planned); fail("New children must be retained") } catch (_: java.io.IOException) { }
            assertEquals("added after verification", File(folder, "new.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun atomicTextSaveConflictsKeepOriginalAndRejectOutsideRootAndLinks() = runBlocking {
        val root = directory()
        val outside = directory()
        try {
            val storage = LocalStorage(context, Uri.fromFile(root))
            val text = File(root, "空格 + 中文.txt").apply { writeText("original") }
            val path = Uri.fromFile(text).toString()
            val old = storage.readText(path)
            storage.saveText(path, "saved", old)
            assertEquals("saved", text.readText())
            try { storage.saveText(path, "stale", old); fail("Conflict") } catch (_: java.io.IOException) { }
            assertEquals("saved", text.readText())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".webdav-save-") })
            val external = File(outside, "outside.txt").apply { writeText("private") }
            try { storage.readText(Uri.fromFile(external).toString()); fail("Root boundary") } catch (_: IllegalArgumentException) { }
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            try { storage.list(Uri.fromFile(File(root, "link")).toString()); fail("Symbolic link") } catch (_: IllegalArgumentException) { }
            assertFalse(storage.list(storage.root.toString()).any { it.name == "link" })
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun real513MiBLocalTransferStagesOnlyOneFileAndVerifiesPublishedContent() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "large.bin")
            java.io.RandomAccessFile(source, "rw").use { it.setLength(513L * 1024 * 1024); it.seek(513L * 1024 * 1024 - 3); it.write(byteArrayOf(1, 2, 3)) }
            val storage = LocalStorage(context, Uri.fromFile(root))
            val entry = storage.list(storage.root.toString()).single()
            storage.transfer(entry, storage.root.toString(), storage.root.toString(), "copy.bin", false)
            val copy = File(root, "copy.bin")
            assertEquals(source.length(), copy.length())
            assertEquals(fileDigest(source), fileDigest(copy))
            assertTrue(File(context.cacheDir, "file_transfers").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedJournalAndClipboardSurviveNewViewModelWithoutResumingDeletion() = runBlocking {
        val store = BrowserStateStore(context)
        val previous = store.load()
        val root = directory()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        try {
            val source = File(root, "keep.txt").apply { writeText("keep") }
            val entry = DirectFiles.entry(source)
            val clip = FileClipboard(listOf(entry), Uri.fromFile(root).toString(), Uri.fromFile(root).toString(), true, true)
            val internal = Uri.fromFile(android.os.Environment.getExternalStorageDirectory()).toString()
            val lastFolder = Uri.fromFile(File(android.os.Environment.getExternalStorageDirectory(), "Download")).toString()
            store.save(BrowserState(true, null, "/", lastFolder, listOf(internal), false, clip))
            val journal = TransferJournal(context, entry, "destination / keep.txt", "local", true)
            journal.record("creation outcome unknown", ".webdav-transfer-test")
            val vm = withContext(Dispatchers.Main) {
                ViewModelProvider(owner, object : ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return BrowserViewModel(context.applicationContext as Application, SavedStateHandle()) as T
                    }
                })[BrowserViewModel::class.java]
            }
            withTimeout(10000) { while (vm.starting) delay(20) }
            assertEquals(entry.path, vm.clipboard!!.files.single().path)
            assertEquals(lastFolder, vm.localDirectory)
            assertEquals(listOf(internal), vm.localParents)
            assertTrue(vm.localMode)
            assertFalse(vm.grid)
            assertTrue(vm.operationResults.any { it.file.path == entry.path && it.status == "Needs review" })
            assertFalse(vm.operationBusy)
            assertEquals("keep", source.readText())
            journal.complete()
        } finally {
            withContext(Dispatchers.Main) { owner.viewModelStore.clear() }
            if (previous != null) store.save(previous) else store.clear()
            root.deleteRecursively()
        }
    }
}
