package com.businessshop.spaceai.core

import com.businessshop.spaceai.core.Fixtures.photo
import com.businessshop.spaceai.core.Fixtures.scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PerceptualHashTest {
    @Test fun resizedCopyHashesNearlyIdentical() {
        val a = scene(1, 640, 480)
        val b = a.resize(320, 240)
        assertTrue(PerceptualHash.hamming(PerceptualHash.dHash(a), PerceptualHash.dHash(b)) <= 2)
    }

    @Test fun brightnessShiftIsTolerated() {
        val a = scene(2)
        val b = Fixtures.map(a) { it * 0.85f + 10f }
        assertTrue(PerceptualHash.hamming(PerceptualHash.dHash(a), PerceptualHash.dHash(b)) <= 4)
    }

    @Test fun differentScenesAreFarApart() {
        var minDist = 64
        for (i in 0 until 30) for (j in i + 1 until 30) {
            minDist = minOf(minDist, PerceptualHash.hamming(PerceptualHash.dHash(scene(100 + i)), PerceptualHash.dHash(scene(100 + j))))
        }
        assertTrue("Distancia mínima entre escenas distintas: $minDist", minDist > Sensitivity.NORMAL.maxHamming)
    }

    @Test fun hexRoundTripIncludingNegative() {
        for (h in listOf(0L, -1L, Long.MIN_VALUE, 0x0123456789abcdefL)) assertEquals(h, PerceptualHash.fromHex(PerceptualHash.toHex(h)))
    }

    @Test fun argbConversionUsesRec601() {
        val img = GrayImage.fromArgb(2, 1, intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals(255f, img[0, 0], 0.01f); assertEquals(0f, img[1, 0], 0.01f)
        val rgba = GrayImage.fromRgba(1, 1, byteArrayOf(255.toByte(), 0, 0, 255.toByte()))
        assertEquals(0.299f * 255, rgba[0, 0], 0.01f)
    }
}

class QualityAnalyzerTest {
    @Test fun blurredCopyHasLowerSharpnessAndIsFlagged() {
        val sharp = scene(3, 512, 384)
        // Ruido fino = textura real; la escena sin textura no es buena referencia de nitidez.
        val r = Random(3)
        val textured = Fixtures.map(sharp) { it + r.nextInt(-25, 25) }
        val blurred = Fixtures.boxBlur(textured, 4)
        val qs = QualityAnalyzer.analyze(textured); val qb = QualityAnalyzer.analyze(blurred)
        assertTrue(qs.sharpness > qb.sharpness * 5)
        assertFalse(qs.isBlurry()); assertTrue("varianza=${qb.sharpness}", qb.isBlurry())
    }

    @Test fun darkPhotoDetected() {
        val dark = Fixtures.map(scene(4)) { it * 0.08f }
        val normal = scene(4)
        assertTrue(QualityAnalyzer.analyze(dark).isDark()); assertFalse(QualityAnalyzer.analyze(normal).isDark())
    }

    @Test fun scorePrefersSharpWellExposed() {
        val r = Random(5)
        val good = Fixtures.map(scene(5, 400, 300)) { it + r.nextInt(-20, 20) }
        val bad = Fixtures.map(Fixtures.boxBlur(good, 3)) { it * 0.2f }
        assertTrue(QualityAnalyzer.analyze(good).score() > QualityAnalyzer.analyze(bad).score())
    }

    @Test fun metricsEncodeDecode() {
        val m = QualityMetrics(12.5, 100.0, 0.1, 0.2)
        assertEquals(m, QualityMetrics.decode(m.encode()))
        assertEquals(null, QualityMetrics.decode("roto"))
    }

    @Test fun tinyImageDoesNotCrash() {
        QualityAnalyzer.analyze(GrayImage(1, 1, floatArrayOf(10f)))
        QualityAnalyzer.analyze(GrayImage(2, 2, FloatArray(4)))
    }
}

class SimilarityGrouperTest {
    private fun sig(id: String, img: GrayImage, taken: Long = 1_700_000_000_000, screenshot: Boolean = false, burst: String? = null, w: Int = 4032, h: Int = 3024) =
        PhotoSignature(photo(id, taken = taken, screenshot = screenshot, burst = burst, w = w, h = h), PerceptualHash.dHash(img), QualityAnalyzer.analyze(img))

    @Test fun groupsSimilarAndKeepsSharpest() {
        val base = scene(10, 400, 300)
        val r = Random(10)
        val sharp = Fixtures.map(base) { it + r.nextInt(-15, 15) }
        val soft = Fixtures.boxBlur(sharp, 2)
        val other = scene(77, 400, 300)
        val groups = SimilarityGrouper().group(listOf(sig("soft", soft), sig("sharp", sharp, taken = 1_700_000_005_000), sig("other", other)))
        assertEquals(1, groups.size)
        assertEquals("sharp", groups[0].keeper.item.id)
        assertEquals(listOf("soft"), groups[0].candidates.map { it.item.id })
    }

    @Test fun screenshotsExcluded() {
        val img = scene(11)
        assertTrue(SimilarityGrouper().group(listOf(sig("a", img, screenshot = true), sig("b", img, screenshot = true))).isEmpty())
    }

    /** Variante con ruido local (no monótono) cuya distancia dHash cae en [lo, hi]. */
    private fun variantAtDistance(base: GrayImage, lo: Int, hi: Int): GrayImage {
        val h0 = PerceptualHash.dHash(base)
        for (seed in 0 until 500) {
            val r = Random(seed)
            val amp = 5 + seed % 60
            val v = Fixtures.map(base) { it + r.nextInt(-amp, amp + 1) }
            val d = PerceptualHash.hamming(h0, PerceptualHash.dHash(v))
            if (d in lo..hi) return v
        }
        throw AssertionError("No se encontró variante con distancia en [$lo,$hi]")
    }

    @Test fun distantDatesRequireNearExactMatch() {
        val a = scene(12, 400, 300)
        val b = variantAtDistance(a, 3, 8) // similar, no casi-exacta
        val far = 1_700_000_000_000 + 400L * 24 * 3600 * 1000
        assertEquals(1, SimilarityGrouper().group(listOf(sig("a", a), sig("b", b, taken = 1_700_000_060_000))).size)
        assertTrue(SimilarityGrouper().group(listOf(sig("a", a), sig("b", b, taken = far))).isEmpty())
        // Copia idéntica años después sí se agrupa.
        assertEquals(1, SimilarityGrouper().group(listOf(sig("a", a), sig("c", a, taken = far))).size)
    }

    @Test fun differentAspectRatioNotGrouped() {
        val a = scene(13)
        assertTrue(SimilarityGrouper().group(listOf(sig("h", a), sig("v", a, w = 3024, h = 4032))).isEmpty())
    }

    @Test fun burstGroupedEvenWithMotion() {
        val g = SimilarityGrouper().group(listOf(sig("1", scene(20), burst = "B"), sig("2", scene(21), burst = "B"), sig("3", scene(22), burst = "B")))
        assertEquals(1, g.size); assertTrue(g[0].isBurst); assertEquals(2, g[0].candidates.size)
    }

    @Test fun sensitivityControlsGrouping() {
        val a = scene(30, 400, 300)
        val b = variantAtDistance(a, Sensitivity.STRICT.maxHamming + 1, Sensitivity.RELAXED.maxHamming)
        val strict = SimilarityGrouper(Sensitivity.STRICT).group(listOf(sig("a", a), sig("b", b)))
        val relaxed = SimilarityGrouper(Sensitivity.RELAXED).group(listOf(sig("a", a), sig("b", b)))
        assertTrue(strict.isEmpty()); assertEquals(1, relaxed.size)
    }

    @Test fun monotonicBrightnessChangeIsNearExact() {
        val a = scene(31)
        val b = Fixtures.map(a) { it * 0.8f + 30f }
        assertTrue(PerceptualHash.hamming(PerceptualHash.dHash(a), PerceptualHash.dHash(b)) <= 2)
    }

    @Test fun bkTreeMatchesBruteForce() {
        val r = Random(42)
        val hashes = LongArray(3000) { r.nextLong() }
        // Inyectar vecinos cercanos.
        for (i in 0 until 300) hashes[i * 10] = hashes[i * 10 + 1] xor (1L shl r.nextInt(64)) xor (1L shl r.nextInt(64))
        val tree = BkTree(); hashes.forEachIndexed { i, h -> tree.add(h, i) }
        for (q in 0 until 200) {
            val expected = hashes.indices.filter { PerceptualHash.hamming(hashes[q], hashes[it]) <= 8 }.toSet()
            assertEquals(expected, tree.query(hashes[q], 8).toSet())
        }
    }

    @Test fun tenThousandPhotosGroupQuickly() {
        val r = Random(7)
        val sigs = List(10_000) { i ->
            PhotoSignature(photo("p$i", taken = 1_700_000_000_000 + i * 60_000L), r.nextLong(), null)
        }
        val t0 = System.nanoTime()
        SimilarityGrouper().group(sigs)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("    10.000 firmas agrupadas en $ms ms")
        assertTrue("Demasiado lento: $ms ms", ms < 5_000)
    }
}
