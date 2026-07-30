plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        maxHeapSize = "1536m"
        maxParallelForks = 1
    }
}
