package com.axiel7.anihyou.core.network.localization

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

@Single
class LocalizationBundleManager(
    private val fileOps: BundleFileOps = DefaultBundleFileOps()
) {

    @Volatile
    private var customStorageDir: File? = null

    private val reloadListeners = CopyOnWriteArrayList<() -> Unit>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun setStorageDirectory(dir: File) {
        val parent = dir.parentFile ?: dir
        val nextDir = File(parent, dir.name + ".next")
        val backupDir = File(parent, dir.name + ".backup")
        recoverStorageState(dir, nextDir, backupDir)

        if (!dir.exists()) {
            dir.mkdirs()
        }
        customStorageDir = dir
    }

    fun getStorageDirectory(): File? = customStorageDir

    fun isOverlayActive(): Boolean {
        val dir = customStorageDir ?: return false
        val manifestFile = File(dir, BUNDLE_MANIFEST_FILE)
        return manifestFile.exists() && manifestFile.length() > 0
    }

    fun registerReloadListener(listener: () -> Unit) {
        if (!reloadListeners.contains(listener)) {
            reloadListeners.add(listener)
        }
    }

    fun unregisterReloadListener(listener: () -> Unit) {
        reloadListeners.remove(listener)
    }

    fun notifyReload() {
        for (listener in reloadListeners) {
            try {
                listener.invoke()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getManifest(): BundleManifest? {
        val manifestText = readResourceText(BUNDLE_MANIFEST_FILE) ?: return null
        return try {
            json.decodeFromString<BundleManifest>(manifestText)
        } catch (_: Exception) {
            null
        }
    }

    fun getCurrentVersion(): String {
        return getManifest()?.version ?: "2026.09.08"
    }

    fun openResource(fileName: String): InputStream? {
        customStorageDir?.let { dir ->
            val externalFile = File(dir, fileName)
            if (externalFile.exists() && externalFile.canRead() && externalFile.length() > 0) {
                return FileInputStream(externalFile)
            }
        }
        return javaClass.classLoader?.getResourceAsStream(fileName)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(fileName)
    }

    fun readResourceText(fileName: String): String? {
        return openResource(fileName)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }

    fun installBundleFromZip(zipInputStream: InputStream, expectedVersion: String? = null): BundleInstallResult {
        val activeDir = customStorageDir
            ?: return BundleInstallResult(isSuccess = false, message = "Storage directory not configured")

        val parent = activeDir.parentFile ?: activeDir
        val nextDir = File(parent, activeDir.name + ".next")
        val backupDir = File(parent, activeDir.name + ".backup")

        // 1. Clean previous staging
        if (fileOps.exists(nextDir)) {
            fileOps.deleteRecursively(nextDir)
        }
        nextDir.mkdirs()

        try {
            val buffer = ByteArray(8192)
            var totalExtractedBytes = 0L
            var entryCount = 0

            ZipInputStream(zipInputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.isDirectory) {
                        fileOps.deleteRecursively(nextDir)
                        return BundleInstallResult(isSuccess = false, message = "Security error: zip must not contain directories")
                    }

                    entryCount++
                    if (entryCount > MAX_BUNDLE_ENTRIES) {
                        fileOps.deleteRecursively(nextDir)
                        return BundleInstallResult(isSuccess = false, message = "Security error: too many entries in zip")
                    }

                    val entryName = entry.name

                    // Strict whitelist check
                    if (!ALLOWED_BUNDLE_FILES.contains(entryName)) {
                        fileOps.deleteRecursively(nextDir)
                        return BundleInstallResult(isSuccess = false, message = "Security error: non-whitelisted entry: $entryName")
                    }

                    // Strict API-24 safe path traversal check
                    if (entryName.contains('/') || entryName.contains('\\') || entryName.contains('\u0000') || entryName.startsWith(".")) {
                        fileOps.deleteRecursively(nextDir)
                        return BundleInstallResult(isSuccess = false, message = "Security error: invalid path component in zip entry: $entryName")
                    }

                    val targetFile = File(nextDir, entryName)

                    // Canonical containment check (API 24 safe)
                    if (!fileOps.isCanonicallyContainedIn(targetFile, nextDir)) {
                        fileOps.deleteRecursively(nextDir)
                        return BundleInstallResult(isSuccess = false, message = "Security error: invalid path in zip")
                    }

                    targetFile.parentFile?.mkdirs()
                    var entryBytes = 0L
                    FileOutputStream(targetFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            entryBytes += len
                            totalExtractedBytes += len

                            if (entryBytes > MAX_ENTRY_BYTES) {
                                fileOps.deleteRecursively(nextDir)
                                return BundleInstallResult(isSuccess = false, message = "Security error: entry $entryName exceeds max size")
                            }
                            if (totalExtractedBytes > MAX_TOTAL_BYTES) {
                                fileOps.deleteRecursively(nextDir)
                                return BundleInstallResult(isSuccess = false, message = "Security error: total extracted size exceeds limit")
                            }
                            fos.write(buffer, 0, len)
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 2. Validate extracted content thoroughly
            val validation = validateExtractedDirectory(
                dir = nextDir,
                expectedVersion = expectedVersion,
                currentVersion = getCurrentVersion()
            )
            if (!validation.isSuccess) {
                fileOps.deleteRecursively(nextDir)
                return BundleInstallResult(isSuccess = false, message = validation.message)
            }

            val newManifest = validation.manifest!!

            // 3. Atomic directory switch with rollback & post-switch validation
            val switched = atomicSwitch(activeDir, nextDir, backupDir)
            if (!switched) {
                fileOps.deleteRecursively(nextDir)
                return BundleInstallResult(isSuccess = false, message = "Atomic switch failed")
            }

            // 4. Notify all providers to reload data
            notifyReload()

            return BundleInstallResult(
                isSuccess = true,
                message = "已成功安装语言包 v${newManifest.version}",
                installedVersion = newManifest.version
            )
        } catch (e: Exception) {
            fileOps.deleteRecursively(nextDir)
            return BundleInstallResult(isSuccess = false, message = "Error extracting bundle: ${e.message}")
        }
    }

    internal data class ValidationResult(
        val isSuccess: Boolean,
        val message: String,
        val manifest: BundleManifest? = null
    )

    internal fun validateExtractedDirectory(
        dir: File,
        expectedVersion: String? = null,
        currentVersion: String? = null
    ): ValidationResult {
        val manifestFile = File(dir, BUNDLE_MANIFEST_FILE)
        if (!manifestFile.exists() || manifestFile.length() == 0L) {
            return ValidationResult(false, "Missing bundle_manifest.json in archive")
        }

        val manifest = try {
            json.decodeFromString<BundleManifest>(manifestFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            return ValidationResult(false, "Corrupted bundle_manifest.json: ${e.message}")
        }

        if (manifest.formatVersion != 1) {
            return ValidationResult(false, "Unsupported manifest formatVersion: ${manifest.formatVersion}")
        }

        if (expectedVersion != null && manifest.version != expectedVersion) {
            return ValidationResult(false, "Version mismatch: expected $expectedVersion, got ${manifest.version}")
        }

        if (currentVersion != null && !isNewerVersion(manifest.version, currentVersion)) {
            return ValidationResult(false, "Version ${manifest.version} is not newer than current version $currentVersion")
        }

        // Verify declared files match exact required dataset
        if (manifest.files.keys != REQUIRED_DATA_FILES) {
            return ValidationResult(false, "Manifest files do not match required data files: ${manifest.files.keys}")
        }

        // Verify no extra undeclared files in directory
        val dirFiles = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        if (dirFiles != ALLOWED_BUNDLE_FILES) {
            return ValidationResult(false, "Archive contains unexpected or missing files: $dirFiles")
        }

        // Verify checksum and size of declared files
        for ((filename, entryMeta) in manifest.files) {
            val file = File(dir, filename)
            if (!file.exists()) {
                return ValidationResult(false, "Missing required file: $filename")
            }
            if (file.length() != entryMeta.size) {
                return ValidationResult(false, "Size mismatch for $filename: expected ${entryMeta.size}, got ${file.length()}")
            }

            if (entryMeta.sha256 != null) {
                val actualSha256 = computeSha256(file)
                if (!actualSha256.equals(entryMeta.sha256, ignoreCase = true)) {
                    return ValidationResult(false, "SHA-256 mismatch for $filename: expected ${entryMeta.sha256}, got $actualSha256")
                }
            } else if (entryMeta.md5 != null) {
                val actualMd5 = computeMd5(file)
                if (!actualMd5.equals(entryMeta.md5, ignoreCase = true)) {
                    return ValidationResult(false, "MD5 mismatch for $filename: expected ${entryMeta.md5}, got $actualMd5")
                }
            } else {
                return ValidationResult(false, "Missing checksum metadata for $filename")
            }
        }

        // Deep content format validation
        try {
            // 1. titles_zh_cn.json: Map<String, String>, mixed numeric AniList IDs and title-name fallbacks
            val titlesFile = File(dir, TITLES_FILE)
            val titles = json.decodeFromString<Map<String, String>>(titlesFile.readText(Charsets.UTF_8))
            val titleValidation = validateTitleMap(titles)
            if (!titleValidation.isSuccess) {
                return titleValidation
            }

            // 2. tags_zh_cn.json: Map<String, String>
            val tagsFile = File(dir, TAGS_FILE)
            val tags = json.decodeFromString<Map<String, String>>(tagsFile.readText(Charsets.UTF_8))
            if (tags.isEmpty()) {
                return ValidationResult(false, "tags_zh_cn.json must not be empty")
            }

            // 3. staff_characters_zh_cn.json: Map<String, String>, keys must match allowed patterns
            val staffFile = File(dir, STAFF_CHARACTERS_FILE)
            val staff = json.decodeFromString<Map<String, String>>(staffFile.readText(Charsets.UTF_8))
            if (staff.isEmpty()) {
                return ValidationResult(false, "staff_characters_zh_cn.json must not be empty")
            }
            val staffKeyRegex = Regex("^(person_\\d+|char_\\d+|name_[a-z0-9_]+|id_\\d+)$")
            for (key in staff.keys) {
                if (!staffKeyRegex.matches(key)) {
                    return ValidationResult(false, "staff_characters_zh_cn.json contains invalid key pattern: $key")
                }
            }

            // 4. t2s_char_map.json: Map<String, String>, every key and value must be single code point
            val t2sFile = File(dir, T2S_MAP_FILE)
            val t2s = json.decodeFromString<Map<String, String>>(t2sFile.readText(Charsets.UTF_8))
            if (t2s.isEmpty()) {
                return ValidationResult(false, "t2s_char_map.json must not be empty")
            }
            for ((key, value) in t2s) {
                if (key.codePointCount(0, key.length) != 1 || value.codePointCount(0, value.length) != 1) {
                    return ValidationResult(false, "t2s_char_map.json contains multi-codepoint mapping: '$key' -> '$value'")
                }
            }
        } catch (e: Exception) {
            return ValidationResult(false, "Content schema validation failed: ${e.message}")
        }

        return ValidationResult(true, "Validation successful", manifest)
    }

    internal fun validateTitleMap(
        titles: Map<String, String>
    ): ValidationResult {
        if (titles.isEmpty()) {
            return ValidationResult(false, "titles_zh_cn.json must not be empty")
        }
        val integerPattern = Regex("^[-+]?\\d+$")
        for ((key, value) in titles) {
            val trimmedKey = key.trim()
            val trimmedVal = value.trim()
            if (trimmedKey.isEmpty() || trimmedVal.isEmpty()) {
                return ValidationResult(false, "titles_zh_cn.json contains blank key or value")
            }
            if (key.any { it.isISOControl() }) {
                return ValidationResult(false, "titles_zh_cn.json key contains control character: $key")
            }
            if (key.codePointCount(0, key.length) > 256) {
                return ValidationResult(false, "titles_zh_cn.json key exceeds 256 code points: $key")
            }
            if (value.codePointCount(0, value.length) > 512) {
                return ValidationResult(false, "titles_zh_cn.json value exceeds 512 code points: $value")
            }

            val isNumericKey = integerPattern.matches(key)
            if (isNumericKey) {
                val numericId = key.toIntOrNull()
                if (numericId == null || numericId <= 0) {
                    return ValidationResult(false, "titles_zh_cn.json numeric key must parse to positive Int: $key")
                }
            }

            if (value.contains('|')) {
                if (!isNumericKey) {
                    return ValidationResult(false, "titles_zh_cn.json entry with pipe '|' must have numeric key: $key")
                }
                val lastPipe = value.lastIndexOf('|')
                val bgmIdStr = value.substring(lastPipe + 1)
                val bgmId = bgmIdStr.toIntOrNull()
                if (bgmId == null || bgmId <= 0) {
                    return ValidationResult(false, "titles_zh_cn.json contains invalid Bangumi ID after pipe: $value")
                }
            }
        }
        return ValidationResult(true, "Titles valid")
    }

    internal fun atomicSwitch(activeDir: File, nextDir: File, backupDir: File): Boolean {
        if (fileOps.exists(backupDir)) {
            if (!fileOps.deleteRecursively(backupDir)) return false
        }
        val hadActive = fileOps.exists(activeDir)
        if (hadActive) {
            if (!fileOps.rename(activeDir, backupDir)) {
                return false
            }
        }
        if (!fileOps.rename(nextDir, activeDir)) {
            if (hadActive && fileOps.exists(backupDir) && !fileOps.exists(activeDir)) {
                fileOps.rename(backupDir, activeDir)
            }
            return false
        }
        // Post-switch validation
        if (!validateExtractedDirectory(activeDir).isSuccess) {
            fileOps.deleteRecursively(activeDir)
            if (hadActive && fileOps.exists(backupDir)) {
                fileOps.rename(backupDir, activeDir)
            }
            return false
        }
        // Never delete backup until the new active directory has been validated after the rename
        if (fileOps.exists(backupDir)) {
            fileOps.deleteRecursively(backupDir)
        }
        return true
    }

    internal fun recoverStorageState(
        activeDir: File,
        nextDir: File,
        backupDir: File
    ): StorageRecoveryResult {
        val hasActive = fileOps.exists(activeDir) && fileOps.isDirectory(activeDir)
        val hasNext = fileOps.exists(nextDir) && fileOps.isDirectory(nextDir)
        val hasBackup = fileOps.exists(backupDir) && fileOps.isDirectory(backupDir)

        return when {
            // Case 1: active, next, no backup -> delete incomplete next
            hasActive && hasNext && !hasBackup -> {
                val deleted = fileOps.deleteRecursively(nextDir)
                StorageRecoveryResult.CleanedIncompleteNext(deleted)
            }
            // Case 2: active, no next, backup -> delete old backup after verifying active
            hasActive && !hasNext && hasBackup -> {
                if (validateExtractedDirectory(activeDir).isSuccess) {
                    val deleted = fileOps.deleteRecursively(backupDir)
                    StorageRecoveryResult.RetainedValidActive(deleted)
                } else {
                    fileOps.deleteRecursively(activeDir)
                    val restored = fileOps.rename(backupDir, activeDir)
                    StorageRecoveryResult.DiscardedInvalidActiveAndRestoredBackup(restored)
                }
            }
            // Case 3: no active, next, backup -> restore backup, delete next
            !hasActive && hasNext && hasBackup -> {
                val restored = fileOps.rename(backupDir, activeDir)
                fileOps.deleteRecursively(nextDir)
                StorageRecoveryResult.RestoredFromBackup(restored)
            }
            // Case 4: no active, no next, backup -> restore backup
            !hasActive && !hasNext && hasBackup -> {
                val restored = fileOps.rename(backupDir, activeDir)
                StorageRecoveryResult.RestoredFromBackup(restored)
            }
            // Case 5: no active, next, no backup -> validate next, promote if valid else delete
            !hasActive && hasNext && !hasBackup -> {
                if (validateExtractedDirectory(nextDir).isSuccess) {
                    val promoted = fileOps.rename(nextDir, activeDir)
                    StorageRecoveryResult.PromotedNext(promoted)
                } else {
                    val deleted = fileOps.deleteRecursively(nextDir)
                    StorageRecoveryResult.DiscardedInvalidNext(deleted)
                }
            }
            // Case 6: all three exist -> prefer valid active, clean others
            hasActive && hasNext && hasBackup -> {
                if (validateExtractedDirectory(activeDir).isSuccess) {
                    fileOps.deleteRecursively(nextDir)
                    val deletedBackup = fileOps.deleteRecursively(backupDir)
                    StorageRecoveryResult.RetainedValidActive(deletedBackup)
                } else {
                    fileOps.deleteRecursively(activeDir)
                    fileOps.deleteRecursively(nextDir)
                    val restored = fileOps.rename(backupDir, activeDir)
                    StorageRecoveryResult.DiscardedInvalidActiveAndRestoredBackup(restored)
                }
            }
            else -> {
                StorageRecoveryResult.SteadyState
            }
        }
    }

    fun resetToBuiltIn(): Boolean {
        val dir = customStorageDir ?: return false
        val parent = dir.parentFile ?: dir
        val nextDir = File(parent, dir.name + ".next")
        val backupDir = File(parent, dir.name + ".backup")

        if (fileOps.exists(nextDir)) fileOps.deleteRecursively(nextDir)
        if (fileOps.exists(backupDir)) fileOps.deleteRecursively(backupDir)
        if (fileOps.exists(dir)) fileOps.deleteRecursively(dir)

        notifyReload()
        return true
    }

    private val VERSION_REGEX = Regex("^(\\d{4})\\.(\\d{2})\\.(\\d{2})$")

    internal fun parseVersion(version: String): Triple<Int, Int, Int>? {
        val match = VERSION_REGEX.matchEntire(version.trim()) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        return Triple(year, month, day)
    }

    internal fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParsed = parseVersion(remote) ?: return false
        val currentParsed = parseVersion(current) ?: return false
        if (remoteParsed.first != currentParsed.first) {
            return remoteParsed.first > currentParsed.first
        }
        if (remoteParsed.second != currentParsed.second) {
            return remoteParsed.second > currentParsed.second
        }
        return remoteParsed.third > currentParsed.third
    }

    companion object {
        const val BUNDLE_MANIFEST_FILE = "bundle_manifest.json"
        const val TITLES_FILE = "titles_zh_cn.json"
        const val TAGS_FILE = "tags_zh_cn.json"
        const val STAFF_CHARACTERS_FILE = "staff_characters_zh_cn.json"
        const val T2S_MAP_FILE = "t2s_char_map.json"

        val ALLOWED_BUNDLE_FILES = setOf(
            BUNDLE_MANIFEST_FILE,
            TITLES_FILE,
            TAGS_FILE,
            STAFF_CHARACTERS_FILE,
            T2S_MAP_FILE
        )

        val REQUIRED_DATA_FILES = setOf(
            TITLES_FILE,
            TAGS_FILE,
            STAFF_CHARACTERS_FILE,
            T2S_MAP_FILE
        )

        const val MAX_ENTRY_COUNT = 10
        const val MAX_BUNDLE_ENTRIES = 10
        const val MAX_ENTRY_BYTES = 15L * 1024 * 1024 // 15MB
        const val MAX_TOTAL_BYTES = 30L * 1024 * 1024 // 30MB

        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun computeMd5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
