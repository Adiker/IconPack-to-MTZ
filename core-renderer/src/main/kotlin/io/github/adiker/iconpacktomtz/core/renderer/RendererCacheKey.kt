package io.github.adiker.iconpacktomtz.core.renderer

import io.github.adiker.iconpacktomtz.core.model.DrawableSource
import io.github.adiker.iconpacktomtz.core.model.RenderCacheKey as SharedRenderCacheKey
import io.github.adiker.iconpacktomtz.core.model.RenderConfig

object RendererCacheKey {
    fun create(source: DrawableSource, config: RenderConfig): String =
        SharedRenderCacheKey.create(source, config)
}
