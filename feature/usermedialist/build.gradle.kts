plugins {
    alias(libs.plugins.anihyou.feature)
}

val appPackageName = rootProject.extra["appPackageName"] as String

android {
    namespace = "$appPackageName.feature.usermedialist"
}

dependencies {
    implementation(project(":core:localization"))
    implementation(project(":feature:editmedia"))
    implementation(project(":feature:genrestags"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
