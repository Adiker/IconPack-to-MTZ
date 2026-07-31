import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.adiker.iconpacktomtz"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.adiker.iconpacktomtz"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }
    androidResources {
        localeFilters += listOf("en", "pl")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += setOf(
        "META-INF/DEPENDENCIES",
        "META-INF/LICENSE.md",
        "META-INF/NOTICE.md",
    )
    testOptions {
        managedDevices {
            localDevices {
                create("pixel2Api30") {
                    device = "Pixel 2"
                    apiLevel = 30
                    systemImageSource = "google"
                    require64Bit = true
                    testedAbi = "x86_64"
                }
                create("pixel2Api37") {
                    device = "Pixel 2"
                    apiLevel = 37
                    systemImageSource = "google"
                    require64Bit = true
                    testedAbi = "x86_64"
                    pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_4KB_PAGES
                }
            }
        }
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-archive"))
    implementation(project(":core-apk"))
    implementation(project(":core-renderer"))
    implementation(project(":core-mtz"))
    implementation(project(":core-report"))
    implementation(project(":core-data"))
    implementation(project(":feature-converter"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-history"))
    implementation(project(":integration-shizuku"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
}
