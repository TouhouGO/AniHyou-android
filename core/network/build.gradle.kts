import java.security.MessageDigest

plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.apollo)
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":core:base"))

    implementation(libs.apollo.runtime)
    implementation(libs.apollo.normalized.cache)
    api(libs.apollo.api)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.annotations)
    implementation(libs.koin.core)

    testImplementation(libs.junit)
}

apollo {
    val appPackageName = rootProject.extra["appPackageName"] as String
    val cacheVersion = libs.versions.apolloCache.get()
    generateSourcesDuringGradleSync.set(false)
    service("service") {
        packageName.set("$appPackageName.core.network")
        generateFragmentImplementations.set(true)
        mapScalarToKotlinInt("FuzzyDateInt")
        mapScalar(
            "CountryCode",
            "$appPackageName.core.network.api.model.CountryOfOriginDto",
            "$appPackageName.core.network.api.model.CountryOfOriginDto.countryOfOriginAdapter",
        )
        introspection {
            endpointUrl.set("https://graphql.anilist.co")
            schemaFile.set(file("src/main/graphql/schema.graphqls"))
        }
        plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin:$cacheVersion")
        pluginArgument("com.apollographql.cache.packageName", packageName.get())
    }
}

object BundleHashUtils {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        file.inputStream().use { fis ->
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

tasks.register("generateInternalBundleManifest") {
    group = "localization"
    description = "Generates internal bundle_manifest.json with SHA-256 from resources"

    val titlesFileInput = layout.projectDirectory.file("src/main/resources/titles_zh_cn.json")
    val tagsFileInput = layout.projectDirectory.file("src/main/resources/tags_zh_cn.json")
    val staffFileInput = layout.projectDirectory.file("src/main/resources/staff_characters_zh_cn.json")
    val t2sFileInput = layout.projectDirectory.file("src/main/resources/t2s_char_map.json")
    val baselineManifestFileInput = layout.projectDirectory.file("src/main/resources/bundle_manifest.json")

    val outputManifestFile = layout.buildDirectory.file("intermediates/localization/bundle_manifest.json")
    val bundleReleaseTagProp = providers.gradleProperty("bundleReleaseTag")
    val bundleBuildTimestampProp = providers.gradleProperty("bundleBuildTimestamp")

    inputs.file(titlesFileInput)
    inputs.file(tagsFileInput)
    inputs.file(staffFileInput)
    inputs.file(t2sFileInput)
    inputs.file(baselineManifestFileInput)
    inputs.property("bundleReleaseTag", bundleReleaseTagProp).optional(true)
    inputs.property("bundleBuildTimestamp", bundleBuildTimestampProp).optional(true)
    outputs.file(outputManifestFile)

    doLast {
        val baseline = groovy.json.JsonSlurper().parse(baselineManifestFileInput.asFile) as Map<*, *>
        val defaultVersion = baseline["version"]?.toString() ?: "2026.09.08"
        val desc = baseline["description"]?.toString() ?: "AniHyou 内置汉化基线资源包"

        val tag = bundleReleaseTagProp.orNull
        val version = if (tag != null) {
            if (tag.startsWith("v")) tag.substring(1) else tag
        } else {
            defaultVersion
        }
        val timestamp = bundleBuildTimestampProp.orNull?.toLongOrNull() ?: 1788883200000L

        fun entryFor(f: File): Map<String, Any> = mapOf(
            "size" to f.length(),
            "sha256" to BundleHashUtils.sha256(f)
        )

        val titlesFile = titlesFileInput.asFile
        val tagsFile = tagsFileInput.asFile
        val staffFile = staffFileInput.asFile
        val t2sFile = t2sFileInput.asFile

        val manifestMap = linkedMapOf<String, Any>(
            "formatVersion" to 1,
            "version" to version,
            "buildTimestamp" to timestamp,
            "description" to desc,
            "files" to linkedMapOf(
                "titles_zh_cn.json" to entryFor(titlesFile),
                "tags_zh_cn.json" to entryFor(tagsFile),
                "staff_characters_zh_cn.json" to entryFor(staffFile),
                "t2s_char_map.json" to entryFor(t2sFile)
            )
        )

        val out = outputManifestFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(manifestMap)) + "\n")
    }
}

tasks.register<Zip>("packageLocalizationBundle") {
    group = "localization"
    description = "Packages localization resources into localization_bundle.zip"
    dependsOn("generateInternalBundleManifest")

    destinationDirectory.set(layout.buildDirectory.dir("outputs/localization"))
    archiveFileName.set("localization_bundle.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    from(layout.buildDirectory.file("intermediates/localization/bundle_manifest.json"))
    from(layout.projectDirectory.dir("src/main/resources")) {
        include("titles_zh_cn.json")
        include("tags_zh_cn.json")
        include("staff_characters_zh_cn.json")
        include("t2s_char_map.json")
    }
}

tasks.register("generateRemoteBundleManifest") {
    group = "localization"
    description = "Generates remote_bundle_manifest.json matching the packaged ZIP"
    dependsOn("packageLocalizationBundle")

    val zipFileInput = layout.buildDirectory.file("outputs/localization/localization_bundle.zip")
    val internalManifestFileInput = layout.buildDirectory.file("intermediates/localization/bundle_manifest.json")
    val outputManifestFile = layout.buildDirectory.file("outputs/localization/remote_bundle_manifest.json")
    val bundleReleaseTagProp = providers.gradleProperty("bundleReleaseTag")
    val bundleBuildTimestampProp = providers.gradleProperty("bundleBuildTimestamp")

    inputs.file(zipFileInput)
    inputs.file(internalManifestFileInput)
    inputs.property("bundleReleaseTag", bundleReleaseTagProp).optional(true)
    inputs.property("bundleBuildTimestamp", bundleBuildTimestampProp).optional(true)
    outputs.file(outputManifestFile)

    doLast {
        val zipFile = zipFileInput.get().asFile
        if (!zipFile.exists()) {
            throw GradleException("localization_bundle.zip was not found")
        }
        val sha256 = BundleHashUtils.sha256(zipFile)
        val size = zipFile.length()

        val internalManifest = groovy.json.JsonSlurper().parse(internalManifestFileInput.get().asFile) as Map<*, *>
        val version = internalManifest["version"]?.toString() ?: "2026.09.08"
        val desc = internalManifest["description"]?.toString() ?: "AniHyou OTA localization bundle"
        val timestamp = bundleBuildTimestampProp.orNull?.toLongOrNull() ?: 1788883200000L

        val releaseTag = bundleReleaseTagProp.orNull ?: "v$version"
        val downloadUrl = "https://github.com/TouhouGO/AniHyou-android/releases/download/$releaseTag/localization_bundle.zip"

        val remoteMap = linkedMapOf<String, Any>(
            "formatVersion" to 1,
            "version" to version,
            "buildTimestamp" to timestamp,
            "description" to desc,
            "downloadUrl" to downloadUrl,
            "archiveSize" to size,
            "archiveSha256" to sha256
        )

        val outputManifest = outputManifestFile.get().asFile
        outputManifest.parentFile.mkdirs()
        outputManifest.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(remoteMap)) + "\n")
        println("Generated remote_bundle_manifest.json at ${outputManifest.absolutePath}")
        println("ZIP SHA-256: $sha256, Size: $size bytes")
    }
}
