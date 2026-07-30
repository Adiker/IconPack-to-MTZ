plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.adiker.iconpacktomtz.core.apk"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(project(":core-model"))
    implementation(project(":core-archive"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.arsclib)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

tasks.withType<Test>().configureEach {
    dependsOn(":fixture-iconpack:assemblePlainDebug", ":fixture-iconpack:assembleCompiledDebug")
    systemProperty(
        "fixturePlainApk",
        rootProject.layout.projectDirectory.file(
            "fixture-iconpack/build/outputs/apk/plain/debug/fixture-iconpack-plain-debug.apk",
        ).asFile.absolutePath,
    )
    systemProperty(
        "fixtureCompiledApk",
        rootProject.layout.projectDirectory.file(
            "fixture-iconpack/build/outputs/apk/compiled/debug/fixture-iconpack-compiled-debug.apk",
        ).asFile.absolutePath,
    )
}
