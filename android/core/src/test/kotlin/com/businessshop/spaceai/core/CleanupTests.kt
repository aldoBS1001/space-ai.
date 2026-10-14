package com.businessshop.spaceai.core

import com.businessshop.spaceai.core.Fixtures.FakeMeter
import com.businessshop.spaceai.core.Fixtures.file
import com.businessshop.spaceai.core.Fixtures.photo
import com.businessshop.spaceai.core.Fixtures.video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

private const val MB = 1024L * 1024

class VideoCompressionTest {
    @Test fun fourKVideoIsWorthCompressing() {
        val e = VideoCompressionPlanner.estimate(VideoInfo(400 * MB, 60_000, 3840, 2160, 30.0))
        assertEquals(1280, e.targetWidth); assertEquals(720, e.targetHeight)
        assertTrue(e.worthIt); assertTrue(e.estimatedBytes < 400 * MB)
        assertEquals(400 * MB, e.estimatedBytes + e.estimatedSavingsBytes)
    }

    @Test fun alreadyEfficientVideoIsNotOffered() {
        // 720p a ~1 Mbps: comprimir no ahorra lo suficiente.
        val e = VideoCompressionPlanner.estimate(VideoInfo(8 * MB, 60_000, 1280, 720, 30.0))
        assertFalse(e.worthIt)
    }

    @Test fun estimateNeverExceedsSourceBitrate() {
        val e = VideoCompressionPlanner.estimate(VideoInfo(2 * MB, 120_000, 1920, 1080, 30.0))
        assertTrue(e.estimatedBytes <= (2 * MB * 1.03).toLong())
    }

    @Test fun dimensionsAreEven() {
        val e = VideoCompressionPlanner.estimate(VideoInfo(300 * MB, 30_000, 1081, 1921, 30.0))
        assertEquals(0, e.targetWidth % 2); assertEquals(0, e.targetHeight % 2)
    }

    @Test(expected = IllegalArgumentException::class) fun zeroDurationRejected() {
        VideoCompressionPlanner.estimate(VideoInfo(10, 0, 100, 100))
    }

    @Test fun validatorRules() {
        val orig = VideoInfo(100 * MB, 60_000, 1920, 1080)
        assertEquals(CompressionValidation.Valid, CompressionValidator.validate(orig, CompressedOutput(30 * MB, 60_400, 1280, 720, true)))
        fun reason(o: CompressedOutput) = (CompressionValidator.validate(orig, o) as CompressionValidation.Invalid).reason
        assertEquals(CompressionValidation.Reason.NOT_DECODABLE, reason(CompressedOutput(30 * MB, 60_000, 1280, 720, false)))
        assertEquals(CompressionValidation.Reason.DURATION_MISMATCH, reason(CompressedOutput(30 * MB, 50_000, 1280, 720, true)))
        assertEquals(CompressionValidation.Reason.NOT_SMALLER, reason(CompressedOutput(100 * MB, 60_000, 1280, 720, true)))
        assertEquals(CompressionValidation.Reason.EMPTY_FILE, reason(CompressedOutput(0, 60_000, 1280, 720, true)))
    }

    @Test fun requiresFreeSpaceMargin() {
        val e = VideoCompressionPlanner.estimate(VideoInfo(400 * MB, 60_000, 3840, 2160))
        assertTrue(CompressionValidator.requiredFreeBytes(e) > e.estimatedBytes + 200 * MB)
    }
}

class ScanAnalyzerTest {
    private val storage = StorageSnapshot(128_000_000_000, 10_000_000_000, 0)
    private val access = AccessReport(AccessState.FULL, AccessState.FULL, AccessState.LIMITED)
    private val now = 1_800_000_000_000

    @Test fun emptyDeviceReportsZeroNotInvented() {
        val r = ScanAnalyzer(clock = { now }).analyze(storage, access, emptyList(), ExactDuplicateResult(emptyList(), emptyList(), 0), emptyList(), emptyMap())
        assertEquals(0L, r.potentiallyRecoverableBytes)
        assertTrue(r.categories.isEmpty()); assertTrue(r.recommendations.isEmpty())
    }

    @Test fun recoverableCountsEachFileOnce() {
        val shot = photo("s", size = 10 * MB, screenshot = true)
        val shotCopy = photo("s2", size = 10 * MB, screenshot = true)
        val group = DuplicateGroup("h", listOf(shot, shotCopy), shot)
        val r = ScanAnalyzer(clock = { now }).analyze(storage, access, listOf(shot, shotCopy),
            ExactDuplicateResult(listOf(group), emptyList(), 0), emptyList(), emptyMap())
        // s2 está en duplicados exactos Y en capturas; s está en capturas. Total = 20 MB, no 30 MB.
        assertEquals(20 * MB, r.potentiallyRecoverableBytes)
        assertEquals(RiskLevel.LOW, r.recommendations.first().risk)
    }

    @Test fun favoritesAndKeepersNeverSuggestedForQuality() {
        val fav = photo("fav", favorite = true)
        val blurry = QualityMetrics(5.0, 120.0, 0.0, 0.0)
        val r = ScanAnalyzer(clock = { now }).analyze(storage, access, listOf(fav),
            ExactDuplicateResult(emptyList(), emptyList(), 0), emptyList(), mapOf("fav" to blurry))
        assertEquals(null, r.category(CleanupCategory.BLURRY_PHOTOS))
    }

    @Test fun categoriesAndRisks() {
        val items = listOf(
            video("big", 300 * MB, taken = now - 3L * 365 * 24 * 3600 * 1000),
            video("rec", 50 * MB, recording = true),
            file("pdf", 80 * MB),
            photo("dark"),
        )
        val r = ScanAnalyzer(clock = { now }).analyze(storage, access, items, ExactDuplicateResult(emptyList(), emptyList(), 0), emptyList(),
            mapOf("dark" to QualityMetrics(200.0, 10.0, 0.95, 0.0)),
            mapOf("big" to VideoInfo(300 * MB, 120_000, 3840, 2160)), appTempBytes = 3 * MB)
        assertEquals(RiskLevel.HIGH, r.category(CleanupCategory.LARGE_VIDEOS)!!.risk)
        assertEquals(RiskLevel.HIGH, r.category(CleanupCategory.LARGE_FILES)!!.risk)
        assertEquals(RiskLevel.MEDIUM, r.category(CleanupCategory.SCREEN_RECORDINGS)!!.risk)
        assertTrue(r.category(CleanupCategory.COMPRESSIBLE_VIDEOS)!!.isEstimate)
        assertEquals(listOf("dark"), r.category(CleanupCategory.DARK_PHOTOS)!!.items.map { it.id })
        assertTrue(r.category(CleanupCategory.OLD_VIDEOS) != null)
        // Riesgo bajo primero en las recomendaciones.
        val risks = r.recommendations.map { it.risk.ordinal }
        assertEquals(risks.sorted(), risks)
    }
}

class SafeCleanupTest {
    private val a = photo("a", 100 * MB); private val b = photo("b", 100 * MB)
    private val group = DuplicateGroup("h", listOf(a, b), a)

    private fun exec(outcome: ItemOutcome) = CleanupExecutor { items, _ -> items.map { ItemResult(it, outcome) } }

    @Test fun lowRiskRunsWithoutApprovalAndVerifies() {
        val plan = CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)), listOf(group))
        val r = SafeCleanupEngine(exec(ItemOutcome.DELETED), FakeMeter(1_000 * MB, 1_100 * MB)).run(plan, null)
        assertEquals(CleanupStatus.VERIFIED_FREED, r.status)
        assertEquals(100 * MB, r.measuredFreedBytes)
    }

    @Test(expected = UnsafePlanException::class) fun mediumRiskWithoutApprovalIsRefused() {
        val plan = CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.MEDIUM, CleanupCategory.SIMILAR_PHOTOS)))
        SafeCleanupEngine(exec(ItemOutcome.DELETED), FakeMeter(0, 0)).run(plan, null)
    }

    @Test(expected = UnsafePlanException::class) fun approvalForOtherPlanIsRefused() {
        val plan = CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.HIGH, CleanupCategory.LARGE_FILES)))
        SafeCleanupEngine(exec(ItemOutcome.DELETED), FakeMeter(0, 0)).run(plan, CleanupApproval("otro", 0, RiskLevel.HIGH))
    }

    @Test(expected = UnsafePlanException::class) fun approvalMustCoverRisk() {
        val plan = CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.HIGH, CleanupCategory.LARGE_FILES)))
        SafeCleanupEngine(exec(ItemOutcome.DELETED), FakeMeter(0, 0)).run(plan, CleanupApproval(plan.id, 0, RiskLevel.MEDIUM))
    }

    @Test(expected = UnsafePlanException::class) fun cannotDeleteAllCopies() {
        CleanupPlan.build(listOf(PlannedItem(a, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS), PlannedItem(b, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)), listOf(group))
    }

    @Test(expected = UnsafePlanException::class) fun protectedItemCannotBePlanned() {
        CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)), protectedIds = setOf("b"))
    }

    @Test fun trashIsReportedAsPendingNotFreed() {
        val plan = CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)), listOf(group))
        val r = SafeCleanupEngine(exec(ItemOutcome.MOVED_TO_TRASH), FakeMeter(1_000 * MB, 1_000 * MB)).run(plan, null)
        assertEquals(CleanupStatus.PENDING_SYSTEM_TRASH, r.status)
        assertEquals(100 * MB, r.trashedBytes); assertEquals(0L, r.deletedBytes)
    }

    @Test fun deletionWithoutMeasuredGainIsUnverified() {
        val plan = CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)), listOf(group))
        val r = SafeCleanupEngine(exec(ItemOutcome.DELETED), FakeMeter(1_000 * MB, 1_000 * MB)).run(plan, null)
        assertEquals(CleanupStatus.UNVERIFIED, r.status)
    }

    @Test fun failuresAreLoggedAndReported() {
        val c = photo("c", 10 * MB)
        val plan = CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS), PlannedItem(c, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)), listOf(group))
        val logged = mutableListOf<CleanupReport>()
        val ex = CleanupExecutor { items, _ -> items.map { ItemResult(it, if (it.id == "c") ItemOutcome.FAILED else ItemOutcome.DELETED, if (it.id == "c") "EPERM" else null) } }
        val r = SafeCleanupEngine(ex, FakeMeter(0, 100 * MB), CleanupLog { logged += it }).run(plan, null)
        assertEquals(CleanupStatus.PARTIAL_FAILURE, r.status)
        assertEquals(listOf("c"), r.failed.map { it.item.id })
        assertEquals(1, logged.size)
    }

    @Test fun userDeniedSystemDialogDeletesNothing() {
        val plan = CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)), listOf(group))
        val r = SafeCleanupEngine(exec(ItemOutcome.USER_DENIED), FakeMeter(5, 5)).run(plan, null)
        assertEquals(CleanupStatus.NOTHING_DELETED, r.status)
    }

    @Test fun cancelledBeforeStartExecutesNothing() {
        var called = false
        val plan = CleanupPlan.build(listOf(PlannedItem(b, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)), listOf(group))
        val r = SafeCleanupEngine({ _, _ -> called = true; emptyList() }, FakeMeter(5, 5)).run(plan, null) { true }
        assertFalse(called); assertEquals(CleanupStatus.CANCELLED, r.status)
    }
}

class ByteFormatTest {
    @Test fun matchesSystemBase1000() {
        assertEquals("999 B", ByteFormat.format(999, Locale.US))
        assertEquals("2.4 GB", ByteFormat.format(2_400_000_000, Locale.US))
        assertEquals("2,4 GB", ByteFormat.format(2_400_000_000, Locale.GERMANY))
        assertEquals("2.4 GB", ByteFormat.format(2_400_000_000, Locale.forLanguageTag("es-MX")))
        assertEquals("128 GB", ByteFormat.format(128_000_000_000, Locale.US))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidSnapshotRejected() {
        StorageSnapshot(10, 20, 0)
    }
}
