package com.example.icu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicLocationShareStoreTest {
    @Test
    fun generatedTokenHasEnoughEntropyAndIsUrlSafe() {
        val first = PublicLocationShareStore.newToken()
        val second = PublicLocationShareStore.newToken()

        assertEquals(43, first.length)
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertNotEquals(first, second)
    }

    @Test
    fun tokenHashIsStableSha256Hex() {
        val hash = PublicLocationShareStore.tokenHash("icu-public-location")

        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]+$")))
        assertEquals(hash, PublicLocationShareStore.tokenHash("icu-public-location"))
    }
}
