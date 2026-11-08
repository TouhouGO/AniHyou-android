plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
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

tasks.register<JavaExec>("benchmarkSearch") {
    group = "verification"
    description = "Measures host JVM catalog loading and local search; never uses a device"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.axiel7.anihyou.core.localization.SearchPerformanceProbeKt")
    jvmArgs("-Xms128m", "-Xmx512m")
    providers.gradleProperty("searchBenchmarkOutput").orNull?.let { args(it) }
}
