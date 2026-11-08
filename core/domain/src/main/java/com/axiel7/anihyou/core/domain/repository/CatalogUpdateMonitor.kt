package com.axiel7.anihyou.core.domain.repository

import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CatalogCheckPreferences(
    val enabled: Boolean = true,
    val lastAttempt: Long? = null,
    val nextAttempt: Long = 0,
    val offerVersion: String? = null,
    val catalogVersion: String? = null,
    val catalogHash: String? = null,
)

interface CatalogCheckPreferencePort {
    suspend fun read(): CatalogCheckPreferences
    suspend fun write(value: CatalogCheckPreferences)
}

data class CatalogMonitorState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val checking: Boolean = false,
    val lastAttempt: Long? = null,
    val offerVersion: String? = null,
    val result: CatalogCheck? = null,
    val error: CatalogFailureCode? = null,
)

/** A single application-owned coordinator. Close it with its owning scope. */
class CatalogUpdateMonitor(
    private val updater: CatalogUpdater,
    private val catalog: LocalizationCatalogRepository,
    private val preferences: CatalogCheckPreferencePort,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    override fun close() {
        scope.cancel()
    }

    private val mutex = Mutex()
    private var saved: CatalogCheckPreferences? = null
    private var running: Deferred<CatalogCheck?>? = null
    private var automatic = false
    private var epoch = 0L
    private val mutableState = MutableStateFlow(CatalogMonitorState())
    val state = mutableState.asStateFlow()

    private suspend fun initialize() {
        if (saved != null) return
        val value = preferences.read()
        catalog.load()
        val status = catalog.state.value.status
        val valid = value.offerVersion != null &&
            value.catalogVersion == status.version && value.catalogHash == status.sha256
        val clean = if (valid) value else value.copy(
            offerVersion = null,
            catalogVersion = null,
            catalogHash = null,
        )
        if (clean != value) preferences.write(clean)
        saved = clean
        mutableState.value = CatalogMonitorState(
            loaded = true,
            enabled = clean.enabled,
            lastAttempt = clean.lastAttempt,
            offerVersion = clean.offerVersion,
        )
    }

    suspend fun initializeForUi() = mutex.withLock { initialize() }

    suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        initialize()
        if (!enabled && automatic) {
            epoch++
            running?.cancel()
            running = null
            automatic = false
        }
        val value = saved!!.copy(enabled = enabled)
        preferences.write(value)
        saved = value
        mutableState.value = mutableState.value.copy(enabled = enabled, checking = running != null)
    }

    suspend fun invalidate() = mutex.withLock {
        initialize()
        epoch++
        running?.cancel()
        running = null
        automatic = false
        val value = saved!!.copy(offerVersion = null, catalogVersion = null, catalogHash = null)
        preferences.write(value)
        saved = value
        mutableState.value = mutableState.value.copy(checking = false, offerVersion = null, result = null)
    }

    suspend fun check(manual: Boolean): CatalogCheck? {
        val task = mutex.withLock {
            initialize()
            running?.let { return@withLock it }
            val value = saved!!
            if (!manual && !value.enabled) return@withLock null
            val time = now()
            // A clock moved backwards: cap its delay to one retry window.
            if (!manual && value.nextAttempt > time) {
                if (value.nextAttempt - time > DAY) {
                    val corrected = value.copy(nextAttempt = time + HOUR)
                    preferences.write(corrected)
                    saved = corrected
                }
                return@withLock null
            }
            val started = value.copy(
                lastAttempt = time,
                nextAttempt = time + HOUR,
                offerVersion = if (manual) null else value.offerVersion,
                catalogVersion = if (manual) null else value.catalogVersion,
                catalogHash = if (manual) null else value.catalogHash,
            )
            preferences.write(started)
            saved = started
            mutableState.value = mutableState.value.copy(
                checking = true,
                lastAttempt = time,
                error = null,
                offerVersion = started.offerVersion,
            )
            val requestEpoch = epoch
            automatic = !manual
            scope.async(start = CoroutineStart.LAZY) { perform(requestEpoch, time) }.also {
                running = it
                it.start()
            }
        }
        return task?.await()
    }

    private suspend fun perform(requestEpoch: Long, started: Long): CatalogCheck? {
        try {
            val result = updater.check()
            currentCoroutineContext().ensureActive()
            return mutex.withLock {
                if (epoch != requestEpoch) return@withLock null
                val status = catalog.state.value.status
                val available = result as? CatalogCheck.Available
                if (available != null && catalog.captureGeneration() != available.generation) return@withLock null
                val value = saved!!.copy(
                    nextAttempt = started + if (result == CatalogCheck.Unavailable) HOUR else DAY,
                    offerVersion = available?.manifest?.version,
                    catalogVersion = status.version,
                    catalogHash = status.sha256,
                )
                preferences.write(value)
                saved = value
                mutableState.value = mutableState.value.copy(
                    offerVersion = value.offerVersion,
                    result = result,
                    error = null,
                )
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutex.withLock {
                if (epoch == requestEpoch) {
                    mutableState.value = mutableState.value.copy(
                        error = (e as? CatalogFailureException)?.code ?: CatalogFailureCode.INTERNAL_ERROR,
                    )
                }
            }
            throw e
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (epoch == requestEpoch) {
                        running = null
                        automatic = false
                        mutableState.value = mutableState.value.copy(checking = false)
                    }
                }
            }
        }
    }

    suspend fun download() {
        // Never restore a generation token from storage. Recheck before each download.
        val offer = check(manual = true) as? CatalogCheck.Available
            ?: throw CatalogFailureException(CatalogFailureCode.STALE_OPERATION)
        var failure: Throwable? = null
        try {
            updater.download(offer)
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                withContext(NonCancellable) { reconcileOffer() }
            } catch (cleanup: Exception) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    // A commit may complete after its caller is cancelled. Reconcile the current
    // offer against the installed catalog without cancelling a newer check.
    private suspend fun reconcileOffer() = mutex.withLock {
        val value = saved ?: return@withLock
        val version = value.offerVersion ?: return@withLock
        val status = catalog.state.value.status
        if (value.catalogVersion == status.version && value.catalogHash == status.sha256) return@withLock
        val stillNewer = runCatching { catalogDate(version) > catalogDate(status.version) }.getOrDefault(false)
        val updated = if (stillNewer) {
            value.copy(catalogVersion = status.version, catalogHash = status.sha256)
        } else {
            value.copy(offerVersion = null, catalogVersion = null, catalogHash = null)
        }
        preferences.write(updated)
        saved = updated
        if (!stillNewer) {
            mutableState.value = mutableState.value.copy(offerVersion = null, result = null)
        }
    }

    companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
