package com.example.webdavmanager

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun largeVisibleListingCachesAndNeverPublishesOldQueryOrTab() {
        val source = (0 until 10000).map { FileEntry("item-%05d".format(it), "/first/$it", false, it.toLong(), null, null, null) }
        val input = mutableStateOf(VisibleFilesState.Input(source, "", FileSortMode.NAME, 0))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var state: VisibleFilesState
        rule.runOnIdle { state = VisibleFilesState(scope) { input.value } }
        try {
            rule.waitUntil(10000) { rule.runOnIdle { state.files.size == 10000 } }
            val first = rule.runOnIdle { state.files }
            rule.runOnIdle { repeat(30) { assertSame(first, state.files) } }
            rule.runOnIdle {
                input.value = input.value.copy(query = "0001")
                assertTrue(state.files.isEmpty()) // Never display results for the previous query.
                input.value = input.value.copy(files = source.map { it.copy(path = it.path.replace("first", "second")) }, query = "0099", tab = 1)
            }
            rule.waitUntil(10000) { rule.runOnIdle { state.files.isNotEmpty() } }
            rule.runOnIdle {
                assertTrue(state.files.all { it.path.startsWith("/second/") && it.name.contains("0099") })
                val second = state.files
                repeat(30) { assertSame(second, state.files) }
            }
        } finally { rule.runOnIdle { scope.cancel() } }
    }
}
