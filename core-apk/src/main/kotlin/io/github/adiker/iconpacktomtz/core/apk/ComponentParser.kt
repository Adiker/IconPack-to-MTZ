package io.github.adiker.iconpacktomtz.core.apk

import android.content.ComponentName
import io.github.adiker.iconpacktomtz.core.model.AppComponent

object ComponentParser {
    private const val PREFIX = "ComponentInfo{"

    fun parse(value: String): AppComponent? {
        val trimmed = value.trim()
        val flattened = if (trimmed.startsWith(PREFIX) && trimmed.endsWith('}')) {
            trimmed.substring(PREFIX.length, trimmed.length - 1)
        } else {
            trimmed
        }
        val slash = flattened.indexOf('/')
        if (slash <= 0 || slash == flattened.lastIndex || flattened.indexOf('/', slash + 1) >= 0) {
            return null
        }
        val packageName = flattened.substring(0, slash).trim()
        val rawActivity = flattened.substring(slash + 1).trim()
        if (!isSafeJavaName(packageName) || rawActivity.isEmpty()) return null

        val fullActivity = when {
            rawActivity.startsWith('.') -> packageName + rawActivity
            '.' !in rawActivity -> "$packageName.$rawActivity"
            else -> rawActivity
        }
        if (!isSafeJavaName(fullActivity)) return null

        val componentName = ComponentName(packageName, fullActivity)
        val shortName = componentName.shortClassName
        return AppComponent(
            packageName = componentName.packageName,
            activityName = componentName.className,
            shortActivityName = shortName,
        )
    }

    private fun isSafeJavaName(value: String): Boolean {
        if (value.isBlank() || value.startsWith('.') || value.endsWith('.') || ".." in value) return false
        return value.split('.').all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_' || segment.first() == '$') &&
                segment.drop(1).all { it.isLetterOrDigit() || it == '_' || it == '$' }
        }
    }
}
