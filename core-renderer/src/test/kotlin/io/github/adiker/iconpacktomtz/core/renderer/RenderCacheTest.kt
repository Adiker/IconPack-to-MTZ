package io.github.adiker.iconpacktomtz.core.renderer

import com.google.common.truth.Truth.assertThat
import io.github.adiker.iconpacktomtz.core.model.DrawableKind
import io.github.adiker.iconpacktomtz.core.model.DrawableSource
import io.github.adiker.iconpacktomtz.core.model.RenderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files

class RenderCacheTest {
    @Test
    fun cacheKeyDeduplicatesIdenticalSourcesButIncludesRendererConfig() {
        val first = DrawableSource("one", DrawableKind.SVG_ASSET, sourceSha256 = "a".repeat(64))
        val second = DrawableSource("two", DrawableKind.SVG_ASSET, sourceSha256 = "a".repeat(64))

        assertThat(RendererCacheKey.create(first, RenderConfig()))
            .isEqualTo(RendererCacheKey.create(second, RenderConfig()))
        assertThat(RendererCacheKey.create(first, RenderConfig(sizePx = 192)))
            .isNotEqualTo(RendererCacheKey.create(first, RenderConfig()))
    }

    @Test
    fun diskCacheStoresTouchesTrimsAndClears() = runBlocking {
        val directory = Files.createTempDirectory("render-cache-test").toFile()
        try {
            val cache = DiskLruRenderCache(directory)
            val firstKey = "1".repeat(64)
            val secondKey = "2".repeat(64)
            cache.put(firstKey, ByteArray(128) { 1 })
            cache.put(secondKey, ByteArray(128) { 2 })
            assertThat(cache.get(firstKey)?.readBytes()).isEqualTo(ByteArray(128) { 1 })

            cache.trimToSize(128)
            assertThat(directory.listFiles().orEmpty().sumOf { it.length() }).isAtMost(128)
            cache.clear()
            assertThat(directory.listFiles().orEmpty()).isEmpty()
        } finally {
            directory.deleteRecursively()
        }
    }
}
