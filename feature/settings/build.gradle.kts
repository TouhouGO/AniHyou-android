import java.util.Properties

plugins {
    alias(libs.plugins.anihyou.feature)
}

val appPackageName = rootProject.extra["appPackageName"] as String

val versionProps = Properties().also {
    it.load(project.rootProject.file("version.properties").reader())
}

android {
    namespace = "$appPackageName.feature.settings"

    buildFeatures {
        buildConfig = true
    }

    buildTypes.all {
        buildConfigField("int", "VERSION_CODE", versionProps.getProperty("code"))
        buildConfigField("String", "VERSION_NAME", "\"${versionProps.getProperty("name")}\"")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(project(":feature:worker"))
    implementation(project(":core:localization"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.accompanist.permissions)
}
