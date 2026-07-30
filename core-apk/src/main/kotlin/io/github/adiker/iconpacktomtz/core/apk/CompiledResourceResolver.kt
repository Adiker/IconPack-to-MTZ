package io.github.adiker.iconpacktomtz.core.apk

import com.reandroid.apk.ApkModule
import java.io.Closeable
import java.io.File

data class ResolvedCompiledResource(
    val archivePath: String? = null,
    val colorArgb: Int? = null,
)

/**
 * Resolves resource-table references without installing or executing the source APK.
 */
class CompiledResourceResolver private constructor(
    private val module: ApkModule,
) : Closeable {
    fun decodeXml(archivePath: String): String = synchronized(ARSCLIB_LOCK) {
        requireNotNull(module.loadResXmlDocument(archivePath)) {
            "Compiled XML resource is missing."
        }.serializeToXml()
    }

    fun resolve(reference: String): ResolvedCompiledResource? = synchronized(ARSCLIB_LOCK) {
        if (!reference.startsWith("@") || reference.startsWith("@android:")) return null
        val unqualified = reference.removePrefix("@").substringAfter(':')
        val slash = unqualified.indexOf('/')
        if (slash !in 1 until unqualified.lastIndex) return null
        val type = unqualified.substring(0, slash)
        val name = unqualified.substring(slash + 1)
        val table = module.tableBlock ?: return null
        val packages = table.packages
        while (packages.hasNext()) {
            val entry = packages.next().getEntry(type, name) ?: continue
            entry.valueAsColor?.let { return ResolvedCompiledResource(colorArgb = it.intValue()) }
            entry.valueAsString
                ?.takeIf { it.startsWith("res/") }
                ?.let { return ResolvedCompiledResource(archivePath = it) }
        }
        return null
    }

    override fun close() = synchronized(ARSCLIB_LOCK) {
        module.close()
    }

    companion object {
        private val ARSCLIB_LOCK = Any()

        fun open(apkFile: File): CompiledResourceResolver = synchronized(ARSCLIB_LOCK) {
            CompiledResourceResolver(ApkModule.loadApkFile(apkFile))
        }
    }
}
