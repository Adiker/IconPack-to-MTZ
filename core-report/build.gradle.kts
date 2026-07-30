plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.adiker.iconpacktomtz.core.report"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core-model"))
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
