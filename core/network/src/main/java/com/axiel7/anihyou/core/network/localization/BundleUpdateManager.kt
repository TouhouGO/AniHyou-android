package com.axiel7.anihyou.core.network.localization

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.annotation.Single

sealed interface BundleUpdateCheckResult {
    data class UpdateAvailable(val info: RemoteBundleUpdateInfo) : BundleUpdateCheckResult
    data class NoUpdate(val info: RemoteBundleUpdateInfo) : BundleUpdateCheckResult
    data object RemoteUnavailable : BundleUpdateCheckResult
    data class ManifestInvalid(val message: String) : BundleUpdateCheckResult
    data class NetworkUnavailable(val message: String) : BundleUpdateCheckResult

    companion object {
        @Deprecated("Use RemoteUnavailable", ReplaceWith("RemoteUnavailable"))
        val Unavailable: BundleUpdateCheckResult get() = RemoteUnavailable
    }
}

@Single
class BundleUpdateManager(
    private val bundleManager: LocalizationBundleManager,
    private val bundleService: LocalizationBundleService? = null,
    private val customHttpClient: OkHttpClient? = null
) {
    private val httpClient: OkHttpClient by lazy {
        customHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun checkUpdate(manifestUrl: String = DEFAULT_REMOTE_MANIFEST_URL): BundleUpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(manifestUrl)
                .header("User-Agent", "TouhouGO/AniHyou-android (https://github.com/TouhouGO)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@withContext BundleUpdateCheckResult.RemoteUnavailable
                }
                if (!response.isSuccessful) {
                    return@withContext BundleUpdateCheckResult.NetworkUnavailable("HTTP ${response.code}: failed to fetch manifest")
                }
                val body = response.body.string()
                val remoteManifest = try {
                    json.decodeFromString<RemoteBundleManifest>(body)
                } catch (e: Exception) {
                    return@withContext BundleUpdateCheckResult.ManifestInvalid("Malformed remote manifest JSON: ${e.message}")
                }

                when (val validation = RemoteBundleManifestValidator.validate(remoteManifest)) {
                    is RemoteManifestValidation.Invalid -> {
                        return@withContext BundleUpdateCheckResult.ManifestInvalid("Invalid remote manifest: ${validation.message}")
                    }
                    is RemoteManifestValidation.Valid -> {
                        val currentVersion = bundleManager.getCurrentVersion()
                        val hasUpdate = isNewerVersion(remoteManifest.version, currentVersion)

                        val info = RemoteBundleUpdateInfo(
                            hasUpdate = hasUpdate,
                            currentVersion = currentVersion,
                            remoteVersion = remoteManifest.version,
                            downloadUrl = remoteManifest.downloadUrl,
                            archiveSize = remoteManifest.archiveSize,
                            archiveSha256 = remoteManifest.archiveSha256,
                            description = remoteManifest.description
                        )
                        return@withContext if (hasUpdate) {
                            BundleUpdateCheckResult.UpdateAvailable(info)
                        } else {
                            BundleUpdateCheckResult.NoUpdate(info)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            BundleUpdateCheckResult.NetworkUnavailable("Network error checking update: ${e.message}")
        }
    }

    suspend fun downloadAndInstall(updateInfo: RemoteBundleUpdateInfo): BundleInstallResult = withContext(Dispatchers.IO) {
        val targetDir = bundleManager.getStorageDirectory()
            ?: return@withContext BundleInstallResult(isSuccess = false, message = "Storage directory not configured")

        val parentDir = targetDir.parentFile ?: targetDir
        val tempFile = File(parentDir, ".download_" + System.currentTimeMillis() + ".tmp")

        try {
            val request = Request.Builder()
                .url(updateInfo.downloadUrl)
                .header("User-Agent", "TouhouGO/AniHyou-android (https://github.com/TouhouGO)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext BundleInstallResult(
                        isSuccess = false,
                        message = "HTTP ${response.code}: failed to download bundle"
                    )
                }

                val body = response.body
                val buffer = ByteArray(8192)
                var downloadedBytes = 0L

                FileOutputStream(tempFile).use { fos ->
                    body.byteStream().use { bis ->
                        var len: Int
                        while (bis.read(buffer).also { len = it } != -1) {
                            downloadedBytes += len
                            if (downloadedBytes > MAX_ARCHIVE_BYTES) {
                                return@withContext BundleInstallResult(
                                    isSuccess = false,
                                    message = "Security error: downloaded archive exceeds maximum limit (10MB)"
                                )
                            }
                            fos.write(buffer, 0, len)
                        }
                    }
                }
            }

            // 1. Verify downloaded file size
            if (tempFile.length() != updateInfo.archiveSize) {
                return@withContext BundleInstallResult(
                    isSuccess = false,
                    message = "Downloaded archive size mismatch: expected ${updateInfo.archiveSize}, got ${tempFile.length()}"
                )
            }

            // 2. Pre-verify SHA-256 before any zip decompression begins
            val actualSha256 = LocalizationBundleManager.computeSha256(tempFile)
            if (!actualSha256.equals(updateInfo.archiveSha256, ignoreCase = true)) {
                return@withContext BundleInstallResult(
                    isSuccess = false,
                    message = "Downloaded archive SHA-256 mismatch: expected ${updateInfo.archiveSha256}, got $actualSha256"
                )
            }

            // 3. Decompress and install
            tempFile.inputStream().use { fis ->
                bundleService?.installBundleFromZip(fis, expectedVersion = updateInfo.remoteVersion)
                    ?: bundleManager.installBundleFromZip(fis, expectedVersion = updateInfo.remoteVersion)
            }
        } catch (e: Exception) {
            BundleInstallResult(
                isSuccess = false,
                message = "Download/Install error: ${e.message}"
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    internal fun isNewerVersion(remote: String, current: String): Boolean {
        return bundleManager.isNewerVersion(remote, current)
    }

    companion object {
        const val DEFAULT_REMOTE_MANIFEST_URL =
            "https://github.com/TouhouGO/AniHyou-android/releases/download/localization-latest/remote_bundle_manifest.json"
        const val MAX_ARCHIVE_BYTES = 10L * 1024 * 1024 // 10MB
    }
}
