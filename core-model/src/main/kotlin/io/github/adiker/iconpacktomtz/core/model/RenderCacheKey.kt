package io.github.adiker.iconpacktomtz.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Stable cache identity shared by APK analysis and the conversion pipeline.
 *
 * Bump [RENDERER_VERSION] whenever rendering semantics change in a way that can
 * alter the generated PNG bytes.
 */
object RenderCacheKey {
    private const val RENDERER_VERSION = 1

    fun create(source: DrawableSource, config: RenderConfig): String {
        val identity = source.sourceSha256 ?: listOf(
            source.drawableName,
            source.kind.name,
            source.resourceId,
            source.assetPath,
            source.archivePath,
            source.densityDpi,
        ).joinToString("|")
        val value = "$RENDERER_VERSION|$identity|${config.sizePx}|${config.marginFraction}"
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }
}
