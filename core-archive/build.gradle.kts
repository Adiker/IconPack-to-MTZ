plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.adiker.iconpacktomtz.core.archive"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core-model"))
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
