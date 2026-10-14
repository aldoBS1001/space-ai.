package com.businessshop.spaceai.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.businessshop.spaceai.core.CleanupLog
import com.businessshop.spaceai.core.CleanupReport
import com.businessshop.spaceai.core.CleanupStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Entrada de historial: solo métricas y conteos, sin nombres de archivo ni contenido. */
data class HistoryEntry(
    val timestamp: Long,
    val status: CleanupStatus,
    val requestedCount: Int,
    val deletedCount: Int,
    val trashedCount: Int,
    val failedCount: Int,
    val requestedBytes: Long,
    val deletedBytes: Long,
    val trashedBytes: Long,
    val measuredFreedBytes: Long,
) {
    fun toJson() = JSONObject().apply {
        put("t", timestamp); put("s", status.name); put("rc", requestedCount); put("dc", deletedCount)
        put("tc", trashedCount); put("fc", failedCount); put("rb", requestedBytes); put("db", deletedBytes)
        put("tb", trashedBytes); put("mf", measuredFreedBytes)
    }

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            o.getLong("t"), CleanupStatus.valueOf(o.getString("s")), o.getInt("rc"), o.getInt("dc"),
            o.getInt("tc"), o.getInt("fc"), o.getLong("rb"), o.getLong("db"), o.getLong("tb"), o.getLong("mf"),
        )

        fun from(r: CleanupReport) = HistoryEntry(
            System.currentTimeMillis(), r.status, r.requestedCount, r.deletedCount, r.trashedCount, r.failed.size,
            r.requestedBytes, r.deletedBytes, r.trashedBytes, r.measuredFreedBytes,
        )
    }
}

/**
 * Historial cifrado con AES-256-GCM; la clave vive en Android Keystore (no extraíble).
 * Se guarda en noBackupFilesDir: no sale del dispositivo.
 */
class HistoryStore(context: Context) : CleanupLog {
    private val file = File(context.noBackupFilesDir, "history.bin")
    private val lock = Any()

    override fun record(report: CleanupReport) = append(HistoryEntry.from(report))

    fun append(entry: HistoryEntry) = synchronized(lock) {
        val all = (load() + entry).takeLast(MAX)
        save(all)
    }

    fun load(): List<HistoryEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching {
            val json = String(decrypt(file.readBytes()), Charsets.UTF_8)
            val arr = JSONArray(json)
            List(arr.length()) { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrElse { emptyList() } // clave rotada o archivo dañado: se descarta sin bloquear la app
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun save(entries: List<HistoryEntry>) {
        val arr = JSONArray().apply { entries.forEach { put(it.toJson()) } }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(encrypt(arr.toString().toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val ivLen = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivLen)
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return c.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
    }

    companion object {
        private const val ALIAS = "spaceai_history_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val MAX = 200
    }
}
