package com.axiel7.anihyou.core.domain.repository

import android.util.AtomicFile
import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import java.io.File
import java.io.IOException
import java.io.InputStream

const val MAX_CATALOG_BYTES = 20 * 1024 * 1024

interface CatalogStorage {
    fun read(): ByteArray?
    fun commit(bytes: ByteArray)
    fun delete()
}

/** AtomicFile handles interrupted writes and recovery on supported Android versions. */
class AtomicCatalogStorage(directory: File) : CatalogStorage {
    private val file = AtomicFile(File(directory, "catalog.json"))
    override fun read(): ByteArray? = try {
        file.openRead().use { it.readBounded() }
    } catch (e: java.io.FileNotFoundException) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()) throw e
        null
    }

    override fun commit(bytes: ByteArray) {
        val parent = file.baseFile.parentFile ?: throw IOException("Missing catalog directory")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create catalog directory")
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.flush()
            // API 24 AtomicFile logs a failed fsync instead of throwing. Syncing
            // the descriptor explicitly gives storage failures an exception.
            output.fd.sync()
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        // finishWrite can also log a close/commit failure. Verify the exact
        // committed bytes before the repository publishes the new snapshot.
        verifyExactFile(file.baseFile, bytes)
    }

    override fun delete() {
        // AtomicFile.delete has no failure result; verify both the main and backup files.
        file.delete()
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()) throw IOException("Cannot delete catalog")
    }
}

internal fun verifyExactFile(baseFile: File, expected: ByteArray) {
    if (!baseFile.isFile) throw IOException("Catalog commit file is missing")
    if (baseFile.length() != expected.size.toLong()) throw IOException("Catalog commit size mismatch")
    val actual = try { baseFile.readBytes() } catch (e: IOException) { throw IOException("Cannot verify catalog commit", e) }
    if (!actual.contentEquals(expected)) throw IOException("Catalog commit digest mismatch")
}

fun InputStream.readBounded(limit: Int = MAX_CATALOG_BYTES): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (n < 0) break
        output.write(buffer, 0, n)
        if (output.size() > limit) throw CatalogFailureException(CatalogFailureCode.FILE_TOO_LARGE)
    }
    return output.toByteArray()
}
