import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.anihyou.feature)
}

val appPackageName = rootProject.extra["appPackageName"] as String

android {
    namespace = "$appPackageName.feature.explore"
}

dependencies {
    implementation(project(":core:localization"))
    implementation(project(":feature:editmedia"))
    implementation(project(":feature:genrestags"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register<JavaExec>("benchmarkMediaSearchPlanning") {
    group = "verification"
    description = "Measures host-JVM media search selection and filter/sort planning; never uses a device"
    dependsOn("compileDebugUnitTestKotlin")
    val debugUnitTestClasspath = tasks.named<Test>("testDebugUnitTest").get().classpath.files
    classpath = files(debugUnitTestClasspath)
    mainClass.set("com.axiel7.anihyou.feature.explore.search.MediaSearchPlanningBenchmarkKt")
    workingDir(rootProject.layout.projectDirectory.asFile)
    jvmArgs("-Xms128m", "-Xmx512m")
    providers.gradleProperty("mediaSearchPlanningOutput").orNull?.let { args(it) }
}
