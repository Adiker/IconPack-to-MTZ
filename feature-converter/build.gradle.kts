plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.adiker.iconpacktomtz.feature.converter"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core-model"))
    implementation(project(":core-archive"))
    implementation(project(":core-apk"))
    implementation(project(":core-renderer"))
    implementation(project(":core-mtz"))
    implementation(project(":core-report"))
    implementation(project(":core-data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.commons.compress)
}

tasks.withType<Test>().configureEach {
    dependsOn(":fixture-iconpack:assemblePlainDebug")
    systemProperty(
        "fixturePlainApk",
        rootProject.layout.projectDirectory.file(
            "fixture-iconpack/build/outputs/apk/plain/debug/fixture-iconpack-plain-debug.apk",
        ).asFile.absolutePath,
    )
}
