package com.axiel7.anihyou.core.domain.repository

import com.axiel7.anihyou.core.localization.LocalizationCatalog
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CatalogStatus(val version: String = "", val sha256: String = "", val recordCount: Int = 0, val installed: Boolean = false, val error: Throwable? = null)
data class CatalogHostState(val snapshot: LocalizationSnapshot = LocalizationSnapshot.EMPTY, val status: CatalogStatus = CatalogStatus())
data class CatalogExpected(val version: String, val size: Long, val sha256: String)

class LocalizationCatalogRepository(
    private val readCatalog: () -> String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val storage: CatalogStorage? = null,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var generation = 0L
    private val mutableState = MutableStateFlow(CatalogHostState())
    val state = mutableState.asStateFlow()
    // Preserve the original interface for existing consumers.
    private val mutableSnapshot = MutableStateFlow(LocalizationSnapshot.EMPTY)
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error = mutableError.asStateFlow()

    private data class Validated(val bytes: ByteArray, val snapshot: LocalizationSnapshot, val digest: String)
    private fun validate(bytes: ByteArray, dateRequired: Boolean): Validated {
        if (bytes.size > MAX_CATALOG_BYTES) throw CatalogFailureException(CatalogFailureCode.FILE_TOO_LARGE)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw CatalogFailureException(CatalogFailureCode.INVALID_UTF8, e)
        }
        val snapshot = try { LocalizationCatalog.decode(text) } catch (e: Exception) {
            throw CatalogFailureException(CatalogFailureCode.INVALID_FORMAT, e)
        }
        if (dateRequired) {
            if (snapshot.recordCount == 0) throw CatalogFailureException(CatalogFailureCode.EMPTY_CATALOG)
            try { catalogDate(snapshot.version) } catch (e: Exception) {
                throw CatalogFailureException(CatalogFailureCode.INVALID_VERSION, e)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return Validated(bytes, snapshot, digest)
    }
    private fun publish(v: Validated, installed: Boolean, diagnostic: Throwable? = null) {
        val status = CatalogStatus(v.snapshot.version, v.digest, v.snapshot.recordCount, installed, diagnostic)
        mutableState.value = CatalogHostState(v.snapshot, status)
        mutableSnapshot.value = v.snapshot
        mutableError.value = diagnostic
    }
    private fun fail(e: Throwable) {
        mutableState.value = mutableState.value.copy(status = mutableState.value.status.copy(error = e))
        mutableError.value = e
    }
    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        try {
            val (result, diagnostic, installed) = withContext(dispatcher) {
                val builtin = validate(readCatalog().toByteArray(Charsets.UTF_8), false)
                try {
                    val overlay = storage?.read()
                    if (overlay == null) Triple(builtin, null, false) else {
                        val candidate = validate(overlay, true)
                        val builtinDate = runCatching { catalogDate(builtin.snapshot.version) }.getOrNull()
                        if (builtinDate != null && catalogDate(candidate.snapshot.version) < builtinDate) throw CatalogFailureException(CatalogFailureCode.DOWNGRADE)
                        if (candidate.snapshot.version == builtin.snapshot.version && candidate.digest != builtin.digest) throw CatalogFailureException(CatalogFailureCode.VERSION_COLLISION)
                        Triple(candidate, null, true)
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { Triple(builtin, e, false) }
            }
            publish(result, installed, diagnostic)
            loaded = true
            generation++
        } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
    }
    suspend fun captureGeneration(): Long { load(); return mutex.withLock { check(loaded); generation } }
    suspend fun install(bytes: ByteArray, expected: CatalogExpected? = null, token: Long? = null) = mutex.withLock {
        try {
            check(loaded) { "Catalog is not loaded" }
            val candidate = withContext(dispatcher) { validate(bytes.copyOf(), true) }
            if (expected != null) {
                if (candidate.snapshot.version != expected.version || bytes.size.toLong() != expected.size || candidate.digest != expected.sha256) throw CatalogFailureException(CatalogFailureCode.METADATA_MISMATCH)
            }
            if (token != null && token != generation) throw CatalogFailureException(CatalogFailureCode.STALE_OPERATION)
            val current = mutableState.value.status
            val oldDate = runCatching { catalogDate(current.version) }.getOrNull()
            val newDate = catalogDate(candidate.snapshot.version)
            if (oldDate != null && newDate < oldDate) throw CatalogFailureException(CatalogFailureCode.DOWNGRADE)
            if (current.version == candidate.snapshot.version) {
                if (current.sha256 != candidate.digest) throw CatalogFailureException(CatalogFailureCode.VERSION_COLLISION)
                mutableState.value = mutableState.value.copy(status = current.copy(error = null))
                mutableError.value = null
                return@withLock
            }
            val target = storage ?: throw CatalogFailureException(CatalogFailureCode.STORAGE_FAILURE)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                withContext(dispatcher) {
                    try { target.commit(candidate.bytes) } catch (e: Exception) {
                        throw CatalogFailureException(CatalogFailureCode.STORAGE_FAILURE, e)
                    }
                    publish(candidate, true)
                    generation++
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val failure = e as? CatalogFailureException ?: CatalogFailureException(CatalogFailureCode.INTERNAL_ERROR, e)
            fail(failure); throw failure
        }
    }
    suspend fun reset() = mutex.withLock {
        try {
            val builtin = withContext(dispatcher) { validate(readCatalog().toByteArray(Charsets.UTF_8), false) }
            val target = storage ?: throw CatalogFailureException(CatalogFailureCode.STORAGE_FAILURE)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                withContext(dispatcher) {
                    try { target.delete() } catch (e: Exception) {
                        throw CatalogFailureException(CatalogFailureCode.STORAGE_FAILURE, e)
                    }
                    publish(builtin, false)
                    loaded = true
                    generation++
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val failure = e as? CatalogFailureException ?: CatalogFailureException(CatalogFailureCode.INTERNAL_ERROR, e)
            fail(failure); throw failure
        }
    }
}

fun catalogDate(value: String): LocalDate {
    require(value.matches(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}"))) { "Invalid catalog version" }
    val year = value.substring(0, 4).toInt()
    require(year in 1..9999) { "Invalid catalog version" }
    return try { LocalDate.of(year, value.substring(5, 7).toInt(), value.substring(8, 10).toInt()) }
    catch (e: java.time.DateTimeException) { throw IllegalArgumentException("Invalid catalog version", e) }
}
