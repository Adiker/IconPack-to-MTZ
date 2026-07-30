package io.github.adiker.iconpacktomtz.core.apk

import android.content.Context
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.TypedValue
import com.reandroid.apk.ApkModule
import java.io.Closeable
import java.io.File

data class ArscResourceFile(
    val path: String,
    val densityDpi: Int,
)

class ApkResourceSession private constructor(
    val packageInfo: PackageInfo,
    val resources: Resources,
    private val descriptor: ParcelFileDescriptor,
    private val provider: ResourcesProvider,
    private val arscModule: ApkModule?,
) : Closeable {
    val packageName: String get() = packageInfo.packageName

    fun resourceId(name: String, type: String): Int =
        resources.getIdentifier(name, type, packageName)

    fun valueForDensity(resourceId: Int, densityDpi: Int): TypedValue? {
        val value = TypedValue()
        return runCatching {
            resources.getValueForDensity(resourceId, densityDpi, value, true)
            value
        }.getOrNull()
    }

    fun resourceFiles(name: String): List<ArscResourceFile> {
        val table = arscModule?.tableBlock ?: return emptyList()
        val result = mutableListOf<ArscResourceFile>()
        table.packages.forEachRemaining { packageBlock ->
            sequenceOf("drawable", "mipmap").forEach { type ->
                packageBlock.getEntries(type, name).forEachRemaining { entry ->
                    val path = entry.valueAsString ?: return@forEachRemaining
                    if (path.startsWith("res/")) {
                        result += ArscResourceFile(path, entry.resConfig.densityValue)
                    }
                }
            }
        }
        return result.distinctBy { it.path to it.densityDpi }
    }

    override fun close() {
        runCatching { arscModule?.close() }
        runCatching { provider.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        fun open(
            context: Context,
            apkFile: File,
            indexCompiledResources: Boolean = true,
        ): ApkResourceSession {
            val packageManager = context.packageManager
            val packageInfo = requireNotNull(
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0),
            ) { "APK manifest could not be read." }
            packageInfo.applicationInfo?.apply {
                sourceDir = apkFile.absolutePath
                publicSourceDir = apkFile.absolutePath
            }

            val descriptor = ParcelFileDescriptor.open(apkFile, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val provider = ResourcesProvider.loadFromApk(descriptor)
                var arscModule: ApkModule? = null
                try {
                    arscModule = if (indexCompiledResources) {
                        ApkModule.loadApkFile(apkFile)
                    } else {
                        null
                    }
                    val loader = ResourcesLoader().apply { addProvider(provider) }
                    val base = context.resources
                    val configuration = Configuration(base.configuration)
                    val metrics = DisplayMetrics().also { it.setTo(base.displayMetrics) }
                    @Suppress("DEPRECATION")
                    val isolatedResources = Resources(base.assets, metrics, configuration).apply {
                        addLoaders(loader)
                    }
                    return ApkResourceSession(
                        packageInfo,
                        isolatedResources,
                        descriptor,
                        provider,
                        arscModule,
                    )
                } catch (exception: Exception) {
                    runCatching { arscModule?.close() }
                    runCatching { provider.close() }
                    throw exception
                }
            } catch (exception: Exception) {
                descriptor.close()
                throw exception
            }
        }
    }
}
