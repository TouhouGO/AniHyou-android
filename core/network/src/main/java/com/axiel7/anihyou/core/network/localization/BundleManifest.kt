package com.axiel7.anihyou.core.network.localization

import kotlinx.serialization.Serializable

@Serializable
data class BundleManifest(
    val formatVersion: Int = 1,
    val version: String,
    val buildTimestamp: Long = 0L,
    val description: String? = null,
    val files: Map<String, BundleFileEntry> = emptyMap()
)

@Serializable
data class BundleFileEntry(
    val size: Long = 0L,
    val sha256: String? = null,
    val md5: String? = null
)

data class BundleInstallResult(
    val isSuccess: Boolean,
    val message: String,
    val installedVersion: String? = null
)

data class RemoteBundleUpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val remoteVersion: String,
    val downloadUrl: String,
    val archiveSize: Long,
    val archiveSha256: String,
    val description: String? = null
)
