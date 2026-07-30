package io.github.adiker.iconpacktomtz.core.mtz

import io.github.adiker.iconpacktomtz.core.archive.configureDeterministic
import io.github.adiker.iconpacktomtz.core.archive.putStoredFile
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.OutputAlias
import io.github.adiker.iconpacktomtz.core.model.RenderedIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext

class IconsModuleBuilder {
    suspend fun build(
        destination: File,
        aliases: List<OutputAlias>,
        rendered: Map<String, RenderedIcon>,
        limits: ArchiveLimits,
        onAliasWritten: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(aliases.size <= limits.maxOutputEntries) {
            "Icons module would exceed the configured output entry limit."
        }
        check(destination.parentFile?.let { it.exists() || it.mkdirs() } != false)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        try {
            ZipArchiveOutputStream(temporary).use { output ->
                output.configureDeterministic()
                aliases.sortedBy { it.fileName }.forEachIndexed { index, alias ->
                    coroutineContext.ensureActive()
                    val icon = requireNotNull(rendered[alias.drawableName]) {
                        "Missing rendered drawable ${alias.drawableName}."
                    }
                    output.putStoredFile("res/drawable-xxhdpi/${alias.fileName}", icon.pngFile)
                    onAliasWritten(index + 1)
                }
            }
            check(temporary.renameTo(destination)) { "Cannot commit icons module." }
            destination
        } finally {
            temporary.delete()
        }
    }
}
