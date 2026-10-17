package com.example.webdavmanager

import org.junit.Assert.fail
import org.junit.Test

class LocalNamesTest {
    @Test fun acceptsLiteralNamesWithoutPathInterpretation() {
        listOf("a b.txt", "a+b.txt", "中文.txt", "a%2Fb.txt").forEach(::validateLocalName)
    }
    @Test fun rejectsSeparatorsAndDirectoryTraversal() {
        listOf("", " ", ".", "..", "a/b", "a\\b", "a\u0000b").forEach {
            try { validateLocalName(it); fail("Invalid name should be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
