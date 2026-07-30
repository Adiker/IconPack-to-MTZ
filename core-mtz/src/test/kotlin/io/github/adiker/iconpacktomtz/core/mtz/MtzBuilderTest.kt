package io.github.adiker.iconpacktomtz.core.mtz

import com.google.common.truth.Truth.assertThat
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.MtzBuildRequest
import io.github.adiker.iconpacktomtz.core.model.OutputAlias
import io.github.adiker.iconpacktomtz.core.model.RenderedIcon
import io.github.adiker.iconpacktomtz.core.model.ThemeMetadata
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class MtzBuilderTest {
    @Test
    fun descriptionXmlEscapesUserValues() {
        val bytes = DescriptionXml.create(
            ThemeMetadata(
                title = "A & <B>",
                author = "\"Author\"",
                description = "x > y",
            ),
        )
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(bytes.inputStream())
        assertThat(document.getElementsByTagName("title").item(0).textContent).isEqualTo("A & <B>")
        assertThat(document.getElementsByTagName("author").item(0).textContent)
            .isEqualTo("\"Author\"")
    }

    @Test
    fun iconsModuleIsSortedAndDeterministic() = runBlocking {
        val directory = Files.createTempDirectory("icons-module").toFile()
        try {
            val firstPng = directory.resolve("first.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val secondPng = directory.resolve("second.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val rendered = mapOf(
                "a" to rendered("a", firstPng),
                "b" to rendered("b", secondPng),
            )
            val aliases = listOf(
                OutputAlias("z.package.png", "b", "z.package"),
                OutputAlias("a.package.png", "a", "a.package"),
            )
            val one = IconsModuleBuilder().build(
                directory.resolve("one"),
                aliases,
                rendered,
                ArchiveLimits(),
            )
            val two = IconsModuleBuilder().build(
                directory.resolve("two"),
                aliases,
                rendered,
                ArchiveLimits(),
            )
            assertThat(one.readBytes()).isEqualTo(two.readBytes())
            assertThat(zipNames(one)).containsExactly(
                "res/drawable-xxhdpi/a.package.png",
                "res/drawable-xxhdpi/z.package.png",
            ).inOrder()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun standaloneAndBaseMtzHaveExpectedStructure() = runBlocking {
        val directory = Files.createTempDirectory("mtz-builder").toFile()
        try {
            val icons = emptyZip(directory.resolve("icons"))
            val standalone = DefaultMtzBuilder().build(
                MtzBuildRequest(
                    destination = directory.resolve("standalone.mtz"),
                    iconsModule = icons,
                    metadata = ThemeMetadata(title = "Fixture"),
                    previewJpeg = byteArrayOf(0x7f),
                    baseMtz = null,
                    limits = ArchiveLimits(),
                ),
            )
            assertThat(zipNames(standalone.file)).containsExactly(
                "description.xml",
                "icons",
                "preview/preview_icons_0.jpg",
            )

            val base = directory.resolve("base.mtz")
            ZipOutputStream(base.outputStream()).use { zip ->
                listOf(
                    "description.xml" to "<MIUI-Theme><title>Base</title></MIUI-Theme>".toByteArray(),
                    "icons" to byteArrayOf(9, 9),
                    "wallpaper/default.jpg" to byteArrayOf(1, 4, 7),
                ).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val based = DefaultMtzBuilder().build(
                MtzBuildRequest(
                    destination = directory.resolve("based.mtz"),
                    iconsModule = icons,
                    metadata = ThemeMetadata(title = "Ignored"),
                    previewJpeg = byteArrayOf(),
                    baseMtz = base,
                    limits = ArchiveLimits(),
                ),
            )
            assertThat(based.replacedBaseIcons).isTrue()
            @Suppress("DEPRECATION")
            ZipFile(based.file).use { zip ->
                assertThat(zip.getInputStream(zip.getEntry("wallpaper/default.jpg")).readBytes())
                    .isEqualTo(byteArrayOf(1, 4, 7))
                assertThat(zip.getInputStream(zip.getEntry("description.xml")).readBytes().decodeToString())
                    .contains("<title>Base</title>")
                assertThat(zip.getInputStream(zip.getEntry("icons")).readBytes())
                    .isEqualTo(icons.readBytes())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun rendered(name: String, file: File) =
        RenderedIcon(name, "0".repeat(64), file, "1".repeat(64), file.length(), false)

    private fun emptyZip(file: File): File {
        ZipOutputStream(file.outputStream()).use { }
        return file
    }

    private fun zipNames(file: File): List<String> {
        val result = mutableListOf<String>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result += entry.name
            }
        }
        return result
    }
}
