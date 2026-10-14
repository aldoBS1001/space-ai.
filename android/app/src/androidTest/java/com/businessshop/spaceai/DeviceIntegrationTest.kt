package com.businessshop.spaceai

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.businessshop.spaceai.core.CancellationSignal
import com.businessshop.spaceai.core.CleanupStatus
import com.businessshop.spaceai.core.ExactDuplicateFinder
import com.businessshop.spaceai.core.HashCache
import com.businessshop.spaceai.core.ItemOutcome
import com.businessshop.spaceai.core.ItemResult
import com.businessshop.spaceai.core.CleanupExecutor
import com.businessshop.spaceai.core.CleanupPlan
import com.businessshop.spaceai.core.PlannedItem
import com.businessshop.spaceai.core.RiskLevel
import com.businessshop.spaceai.core.CleanupCategory
import com.businessshop.spaceai.core.SafeCleanupEngine
import com.businessshop.spaceai.data.AndroidContentSource
import com.businessshop.spaceai.data.AndroidStorageMeter
import com.businessshop.spaceai.data.HistoryEntry
import com.businessshop.spaceai.data.HistoryStore
import com.businessshop.spaceai.data.MediaStoreScanner
import com.businessshop.spaceai.data.ThumbnailAnalyzer
import com.businessshop.spaceai.data.UriCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Pruebas en dispositivo/emulador real: crean fotos propias en MediaStore, las analizan con el
 * pipeline de producción y las eliminan. Solo tocan archivos que la propia prueba creó.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<Uri>()
    private val prefix = "spaceai_it_${System.currentTimeMillis()}"

    @get:Rule val perms: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun jpeg(seed: Int): ByteArray {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint()
        val r = java.util.Random(seed.toLong())
        c.drawColor(Color.rgb(r.nextInt(255), r.nextInt(255), r.nextInt(255)))
        repeat(25) { p.color = Color.rgb(r.nextInt(255), r.nextInt(255), r.nextInt(255)); c.drawCircle(r.nextFloat() * 640, r.nextFloat() * 480, 20f + r.nextFloat() * 80, p) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    private fun insert(name: String, data: ByteArray): Uri {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpaceAITest")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        created += uri
        return uri
    }

    @After fun cleanup() { created.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } } }

    @Test fun storageMeterReturnsRealVolume() {
        val s = AndroidStorageMeter(ctx).measure()
        assertTrue(s.totalBytes > 0); assertTrue(s.freeBytes in 1..s.totalBytes)
    }

    @Test fun scansFindsExactDuplicatesAndAnalyzesThumbnails() {
        val a = jpeg(1)
        insert("${prefix}_a.jpg", a); insert("${prefix}_b.jpg", a); insert("${prefix}_c.jpg", jpeg(2))
        val scanner = MediaStoreScanner(ctx)
        val mine = scanner.scanPhotos(CancellationSignal.NONE).filter { it.displayName.startsWith(prefix) }
        assertEquals(3, mine.size)
        val result = ExactDuplicateFinder(AndroidContentSource(ctx)).find(mine)
        assertEquals(1, result.groups.size)
        assertEquals(2, result.groups[0].items.size)
        val thumbs = ThumbnailAnalyzer(ctx, HashCache())
        val sigs = mine.map { assertNotNull(thumbs.signature(it)); thumbs.signature(it)!! }
        val dA = sigs.first { it.item.displayName.endsWith("_a.jpg") }.dHash
        val dB = sigs.first { it.item.displayName.endsWith("_b.jpg") }.dHash
        assertEquals(dA, dB)
    }

    @Test fun safeCleanupDeletesOwnItemAndVerifiesWithMediaStore() {
        val data = jpeg(3)
        insert("${prefix}_k.jpg", data); val dupUri = insert("${prefix}_d.jpg", data)
        val scanner = MediaStoreScanner(ctx)
        val mine = scanner.scanPhotos(CancellationSignal.NONE).filter { it.displayName.startsWith(prefix) }
        val group = ExactDuplicateFinder(AndroidContentSource(ctx)).find(mine).groups.single()
        val victim = group.duplicates.single()
        // Ejecutor de prueba: borrado directo de un elemento propio (no requiere diálogo) + verificación real.
        val exec = CleanupExecutor { items, _ ->
            items.map { item ->
                val uri = UriCodec.toUri(item.id)
                ctx.contentResolver.delete(uri, null, null)
                ItemResult(item, if (scanner.exists(uri)) ItemOutcome.FAILED else ItemOutcome.DELETED)
            }
        }
        val plan = CleanupPlan.build(listOf(PlannedItem(victim, RiskLevel.LOW, CleanupCategory.EXACT_DUPLICATE_PHOTOS)), listOf(group))
        val report = SafeCleanupEngine(exec, AndroidStorageMeter(ctx)).run(plan, null)
        assertEquals(ItemOutcome.DELETED, report.results.single().outcome)
        assertFalse(scanner.exists(UriCodec.toUri(victim.id)))
        assertTrue(scanner.exists(UriCodec.toUri(group.keeper.id)))
        // Un archivo de ~100 KB no siempre mueve el contador de bloques: el estado debe ser honesto.
        assertTrue(report.status == CleanupStatus.VERIFIED_FREED || report.status == CleanupStatus.UNVERIFIED)
        created.remove(dupUri)
    }

    @Test fun historyIsEncryptedAndReadable() {
        val store = HistoryStore(ctx)
        store.clear()
        store.append(HistoryEntry(1L, CleanupStatus.VERIFIED_FREED, 2, 2, 0, 0, 10, 10, 0, 10))
        assertEquals(1, store.load().size)
        val raw = java.io.File(ctx.noBackupFilesDir, "history.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("El historial no debe estar en texto plano", raw.contains("VERIFIED_FREED"))
        store.clear()
    }
}
