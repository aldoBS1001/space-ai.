package com.businessshop.spaceai.core

import com.businessshop.spaceai.core.Fixtures.MemorySource
import com.businessshop.spaceai.core.Fixtures.photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class Sha256Test {
    @Test fun knownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of("abc".toByteArray()))
    }

    @Test fun streamingMatchesInMemoryForLargeInput() {
        val data = Random(1).nextBytes(1_000_003)
        assertEquals(Sha256.of(data), Sha256.of(ByteArrayInputStream(data)))
    }

    @Test fun limitHashesOnlyPrefix() {
        val data = Random(2).nextBytes(200_000)
        assertEquals(Sha256.of(data.copyOf(65_536)), Sha256.of(ByteArrayInputStream(data), 65_536))
    }

    @Test(expected = ScanCancelledException::class) fun honorsCancellation() {
        Sha256.of(ByteArrayInputStream(ByteArray(500_000)), cancel = { true })
    }
}

class ExactDuplicateFinderTest {
    private fun bytes(seed: Int, n: Int) = Random(seed).nextBytes(n)

    @Test fun emptyLibraryProducesNothing() {
        val r = ExactDuplicateFinder(MemorySource(emptyMap())).find(emptyList())
        assertTrue(r.groups.isEmpty()); assertTrue(r.unreadable.isEmpty()); assertEquals(0, r.bytesHashed)
    }

    @Test fun findsExactDuplicatesAndKeepsOneCopy() {
        val a = bytes(1, 300_000)
        val items = listOf(photo("1", 300_000), photo("2", 300_000), photo("3", 300_000))
        val src = MemorySource(mapOf("1" to a, "2" to a.copyOf(), "3" to bytes(9, 300_000)))
        val r = ExactDuplicateFinder(src).find(items)
        assertEquals(1, r.groups.size)
        assertEquals(setOf("1", "2"), r.groups[0].items.map { it.id }.toSet())
        assertEquals(1, r.groups[0].duplicates.size)
        assertEquals(300_000L, r.groups[0].reclaimableBytes)
    }

    @Test fun sameSizeAndSamePrefixButDifferentTailIsNotDuplicate() {
        val a = bytes(3, 200_000)
        val b = a.copyOf().also { it[199_999] = (it[199_999] + 1).toByte() }
        val items = listOf(photo("a", 200_000), photo("b", 200_000))
        val r = ExactDuplicateFinder(MemorySource(mapOf("a" to a, "b" to b))).find(items)
        assertTrue("Falso duplicado detectado", r.groups.isEmpty())
    }

    @Test fun differentSizesAreNeverOpened() {
        val src = MemorySource(mapOf("a" to ByteArray(10), "b" to ByteArray(20)))
        ExactDuplicateFinder(src).find(listOf(photo("a", 10), photo("b", 20)))
        assertEquals(0, src.opens.get())
    }

    @Test fun smallFilesAreConfirmedWithoutSecondRead() {
        val d = bytes(4, 1000)
        val src = MemorySource(mapOf("a" to d, "b" to d.copyOf()))
        val r = ExactDuplicateFinder(src).find(listOf(photo("a", 1000), photo("b", 1000)))
        assertEquals(1, r.groups.size)
        assertEquals(2, src.opens.get())
    }

    @Test fun unreadableAndCorruptAreReportedNotGrouped() {
        val d = bytes(5, 5000)
        val items = listOf(photo("ok1", 5000), photo("ok2", 5000), photo("missing", 5000))
        val throwing = ContentSource { if (it.id == "missing") throw java.io.IOException("corrupto") else ByteArrayInputStream(d) }
        val r = ExactDuplicateFinder(throwing).find(items)
        assertEquals(listOf("missing"), r.unreadable.map { it.id })
        assertEquals(setOf("ok1", "ok2"), r.groups.single().items.map { it.id }.toSet())
    }

    @Test fun favoriteIsKeptOverOlderCopy() {
        val d = bytes(6, 5000)
        val items = listOf(photo("old", 5000, taken = 1), photo("fav", 5000, taken = 999, favorite = true))
        val r = ExactDuplicateFinder(MemorySource(mapOf("old" to d, "fav" to d))).find(items)
        assertEquals("fav", r.groups.single().keeper.id)
    }

    @Test fun cacheAvoidsRereadingOnSecondScan() {
        val d = bytes(7, 150_000)
        val items = listOf(photo("a", 150_000), photo("b", 150_000))
        val src = MemorySource(mapOf("a" to d, "b" to d))
        val cache = HashCache()
        ExactDuplicateFinder(src, cache).find(items)
        val first = src.opens.get()
        val r = ExactDuplicateFinder(src, cache).find(items)
        assertEquals(first, src.opens.get())
        assertEquals(1, r.groups.size)
    }

    @Test(expected = ScanCancelledException::class) fun cancellationStopsScan() {
        val d = bytes(8, 100)
        ExactDuplicateFinder(MemorySource(mapOf("a" to d, "b" to d))).find(listOf(photo("a", 100), photo("b", 100)), cancel = { true })
    }
}

class HashCacheTest {
    @Test fun persistsAndReloadsAtomically() {
        val dir = Files.createTempDirectory("cache").toFile()
        val f = File(dir, "hashes.tsv")
        val item = photo("x", 10)
        HashCache(f).apply { put(item, HashCache.Kind.FULL, "abc"); flush() }
        assertEquals("abc", HashCache(f).get(item, HashCache.Kind.FULL))
        assertFalse(File(dir, "hashes.tsv.tmp").exists())
    }

    @Test fun modifiedFileInvalidatesEntry() {
        val c = HashCache()
        c.put(photo("x", 10), HashCache.Kind.FULL, "abc")
        assertEquals(null, c.get(photo("x", 10).copy(dateModifiedMillis = 5), HashCache.Kind.FULL))
    }

    @Test fun corruptOrForeignFileIsIgnored() {
        val f = File.createTempFile("cache", ".tsv").apply { writeText("basura\u0000\nmas basura") }
        assertEquals(0, HashCache(f).size())
    }
}
