import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.adiker.iconpacktomtz.fixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.adiker.iconpacktomtz.fixture"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    flavorDimensions += "mapping"
    productFlavors {
        create("plain") { dimension = "mapping" }
        create("compiled") { dimension = "mapping" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val generatedRasterResources = layout.buildDirectory.dir("generated/fixtureRaster")
android.sourceSets.getByName("main").res.directories.add(
    generatedRasterResources.get().asFile.absolutePath,
)

val generateFixtureRasters by tasks.registering {
    outputs.dir(generatedRasterResources)
    doLast {
        val root = generatedRasterResources.get().asFile
        listOf("drawable-mdpi" to 48, "drawable-xxxhdpi" to 192).forEach { (folder, size) ->
            val directory = root.resolve(folder).apply { mkdirs() }
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.color = Color(255, 196, 0, 255)
                graphics.fillOval(0, 0, size, size)
                graphics.color = Color(35, 35, 35, 255)
                graphics.fillRect(size / 3, size / 4, size / 3, size / 2)
            } finally {
                graphics.dispose()
            }
            ImageIO.write(image, "png", directory.resolve("fixture_raster.png"))
        }
    }
}

tasks.named("preBuild").configure { dependsOn(generateFixtureRasters) }
