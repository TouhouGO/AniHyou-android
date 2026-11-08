package com.axiel7.anihyou.core.domain.repository

import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import com.axiel7.anihyou.core.network.localization.CatalogManifest
import com.axiel7.anihyou.core.network.localization.CatalogTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException

sealed interface CatalogCheck {
    data object Unavailable : CatalogCheck
    data object UpToDate : CatalogCheck
    data class Available(val manifest: CatalogManifest, val generation: Long) : CatalogCheck
}

class CatalogUpdater(private val repository: LocalizationCatalogRepository, private val client: CatalogTransport) {
    suspend fun check(): CatalogCheck {
        val generation = repository.captureGeneration()
        val manifest = try {
            client.check()?.also { it.validate() } ?: return CatalogCheck.Unavailable
        } catch (e: CancellationException) { throw e }
        catch (e: CatalogFailureException) { throw e }
        catch (e: IOException) { throw CatalogFailureException(CatalogFailureCode.NETWORK_FAILURE, e) }
        catch (e: Exception) { throw CatalogFailureException(CatalogFailureCode.INVALID_MANIFEST, e) }
        val current = repository.state.value.status
        val currentDate = runCatching { catalogDate(current.version) }.getOrNull()
        val offered = catalogDate(manifest.version)
        if (current.version == manifest.version && current.sha256 != manifest.sha256) {
            throw CatalogFailureException(CatalogFailureCode.VERSION_COLLISION)
        }
        return if (currentDate != null && offered < currentDate || current.version == manifest.version) CatalogCheck.UpToDate
        else CatalogCheck.Available(manifest, generation)
    }
    suspend fun download(offer: CatalogCheck.Available) {
        val manifest = offer.manifest
        val bytes = try { client.download(manifest) }
        catch (e: CancellationException) { throw e }
        catch (e: CatalogFailureException) { throw e }
        catch (e: IOException) { throw CatalogFailureException(CatalogFailureCode.NETWORK_FAILURE, e) }
        catch (e: Exception) { throw CatalogFailureException(CatalogFailureCode.INTERNAL_ERROR, e) }
        repository.install(bytes, CatalogExpected(manifest.version, manifest.size, manifest.sha256), offer.generation)
    }
}
