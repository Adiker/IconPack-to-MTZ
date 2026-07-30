package io.github.adiker.iconpacktomtz.core.mtz

import io.github.adiker.iconpacktomtz.core.archive.ArchiveValidator
import io.github.adiker.iconpacktomtz.core.archive.Sha256
import io.github.adiker.iconpacktomtz.core.archive.configureDeterministic
import io.github.adiker.iconpacktomtz.core.archive.copyRawEntriesFrom
import io.github.adiker.iconpacktomtz.core.archive.putDeflatedBytes
import io.github.adiker.iconpacktomtz.core.archive.putStoredBytes
import io.github.adiker.iconpacktomtz.core.archive.putStoredFile
import io.github.adiker.iconpacktomtz.core.model.MtzBuildRequest
import io.github.adiker.iconpacktomtz.core.model.MtzBuildResult
import io.github.adiker.iconpacktomtz.core.model.MtzBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File

class DefaultMtzBuilder(
    private val validator: ArchiveValidator = ArchiveValidator(),
) : MtzBuilder {
    override suspend fun build(request: MtzBuildRequest): MtzBuildResult =
        withContext(Dispatchers.IO) {
            request.baseMtz?.let { validator.requireValid(it, request.limits) }
            validator.requireValid(request.iconsModule, request.limits)
            check(request.destination.parentFile?.let { it.exists() || it.mkdirs() } != false)
            val temporary = File(request.destination.parentFile, "${request.destination.name}.tmp")
            var replaced = false
            try {
                ZipArchiveOutputStream(temporary).use { output ->
                    output.configureDeterministic()
                    if (request.baseMtz == null) {
                        output.putDeflatedBytes("description.xml", DescriptionXml.create(request.metadata))
                    } else {
                        @Suppress("DEPRECATION")
                        ZipFile(request.baseMtz).use { base ->
                            replaced = base.getEntry("icons") != null
                            output.copyRawEntriesFrom(base, setOf("icons"))
                        }
                    }
                    output.putStoredFile("icons", request.iconsModule)
                    if (request.baseMtz == null) {
                        output.putStoredBytes("preview/preview_icons_0.jpg", request.previewJpeg)
                    }
                }
                validateOutput(temporary, request)
                check(temporary.renameTo(request.destination)) { "Cannot commit MTZ output." }
                MtzBuildResult(
                    file = request.destination,
                    sha256 = Sha256.file(request.destination),
                    byteSize = request.destination.length(),
                    replacedBaseIcons = replaced,
                )
            } finally {
                temporary.delete()
            }
        }

    private fun validateOutput(file: File, request: MtzBuildRequest) {
        validator.requireValid(file, request.limits)
        @Suppress("DEPRECATION")
        ZipFile(file).use { zip ->
            check(zip.getEntry("icons")?.isDirectory == false) { "MTZ has no icons module." }
            check(zip.getEntry("description.xml")?.isDirectory == false) {
                "MTZ has no root description.xml."
            }
            if (request.baseMtz == null) {
                check(zip.getEntry("preview/preview_icons_0.jpg")?.isDirectory == false) {
                    "Standalone MTZ has no preview."
                }
            }
        }
    }
}
