package com.axiel7.anihyou.core.network.localization

import kotlinx.serialization.Serializable

@Serializable
data class RemoteBundleManifest(
    val formatVersion: Int = 1,
    val version: String,
    val buildTimestamp: Long = 0L,
    val description: String? = null,
    val downloadUrl: String,
    val archiveSize: Long,
    val archiveSha256: String
)

sealed interface RemoteManifestValidation {
    data class Valid(val manifest: RemoteBundleManifest) : RemoteManifestValidation
    data class Invalid(val message: String) : RemoteManifestValidation
}

object RemoteBundleManifestValidator {
    val DOWNLOAD_URL_REGEX = Regex("^https://github\\.com/TouhouGO/AniHyou-android/releases/download/v([0-9]{4}\\.[0-9]{2}\\.[0-9]{2})/localization_bundle\\.zip$")
    val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    val VERSION_REGEX = Regex("^\\d{4}\\.\\d{2}\\.\\d{2}$")

    fun validate(manifest: RemoteBundleManifest): RemoteManifestValidation {
        if (manifest.formatVersion != 1) {
            return RemoteManifestValidation.Invalid("Unsupported formatVersion: ${manifest.formatVersion}")
        }
        if (!VERSION_REGEX.matches(manifest.version)) {
            return RemoteManifestValidation.Invalid("Invalid version format: ${manifest.version}")
        }
        if (manifest.archiveSize <= 0 || manifest.archiveSize > BundleUpdateManager.MAX_ARCHIVE_BYTES) {
            return RemoteManifestValidation.Invalid("Invalid archiveSize: ${manifest.archiveSize} bytes")
        }
        if (!SHA256_REGEX.matches(manifest.archiveSha256)) {
            return RemoteManifestValidation.Invalid("Invalid archiveSha256: ${manifest.archiveSha256}")
        }
        val urlMatch = DOWNLOAD_URL_REGEX.matchEntire(manifest.downloadUrl)
            ?: return RemoteManifestValidation.Invalid("Invalid downloadUrl pattern: ${manifest.downloadUrl}")
        val urlVersion = urlMatch.groupValues[1]
        if (urlVersion != manifest.version) {
            return RemoteManifestValidation.Invalid("Version mismatch between URL tag ($urlVersion) and manifest version (${manifest.version})")
        }
        return RemoteManifestValidation.Valid(manifest)
    }
}
