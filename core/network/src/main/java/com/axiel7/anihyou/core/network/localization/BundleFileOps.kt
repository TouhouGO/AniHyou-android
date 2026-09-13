package com.axiel7.anihyou.core.network.localization

import java.io.File

interface BundleFileOps {
    fun rename(source: File, target: File): Boolean
    fun deleteRecursively(file: File): Boolean
    fun exists(file: File): Boolean
    fun isDirectory(file: File): Boolean
    fun isCanonicallyContainedIn(child: File, parent: File): Boolean
}

class DefaultBundleFileOps : BundleFileOps {
    override fun rename(source: File, target: File): Boolean = source.renameTo(target)

    override fun deleteRecursively(file: File): Boolean = file.deleteRecursively()

    override fun exists(file: File): Boolean = file.exists()

    override fun isDirectory(file: File): Boolean = file.isDirectory

    override fun isCanonicallyContainedIn(child: File, parent: File): Boolean {
        return try {
            val childCanonical = child.canonicalFile
            val parentCanonical = parent.canonicalFile
            childCanonical.parentFile == parentCanonical
        } catch (_: Exception) {
            false
        }
    }
}

sealed interface StorageRecoveryResult {
    data object SteadyState : StorageRecoveryResult
    data class CleanedIncompleteNext(val success: Boolean) : StorageRecoveryResult
    data class RestoredFromBackup(val success: Boolean) : StorageRecoveryResult
    data class PromotedNext(val success: Boolean) : StorageRecoveryResult
    data class DiscardedInvalidActiveAndRestoredBackup(val success: Boolean) : StorageRecoveryResult
    data class DiscardedInvalidNext(val success: Boolean) : StorageRecoveryResult
    data class RetainedValidActive(val success: Boolean) : StorageRecoveryResult
    data class RecoveryFailed(val message: String) : StorageRecoveryResult
}
