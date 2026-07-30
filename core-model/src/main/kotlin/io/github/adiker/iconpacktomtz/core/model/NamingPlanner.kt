package io.github.adiker.iconpacktomtz.core.model

import java.text.Normalizer

object HyperOsFileName {
    private const val MAX_STEM_LENGTH = 240

    fun normalizeStem(value: String): String? {
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
        if (normalized.isEmpty() || normalized.length > MAX_STEM_LENGTH) return null
        if (normalized == "." || normalized == ".." || normalized.startsWith('.')) return null
        if (normalized.contains("..") || normalized.any { it == '/' || it == '\\' || it.isISOControl() }) {
            return null
        }
        val segments = normalized.split('.')
        if (segments.any { it.isEmpty() }) return null
        val valid = normalized.all { character ->
            character == '.' ||
                character == '_' ||
                character == '$' ||
                character.isLetterOrDigit()
        }
        return normalized.takeIf { valid }
    }
}

class HyperOsNamingPlanner {
    fun plan(entries: List<AppFilterEntry>, strategy: NamingStrategy): NamingPlan {
        val aliases = mutableListOf<OutputAlias>()
        val issues = mutableListOf<ConversionIssue>()
        val usedNames = linkedMapOf<String, OutputAlias>()

        entries.groupBy { it.component.packageName }.forEach { (packageName, packageEntries) ->
            val packageStem = HyperOsFileName.normalizeStem(packageName)
            if (packageStem == null) {
                issues += ConversionIssue(
                    IssueCode.INVALID_OUTPUT_NAME,
                    IssueSeverity.ERROR,
                    packageName,
                    "Package name cannot be represented safely in the icons module.",
                )
                return@forEach
            }

            val defaultDrawable = mostFrequentDrawable(packageEntries)
            addAlias(
                alias = OutputAlias("$packageStem.png", defaultDrawable, packageName),
                aliases = aliases,
                usedNames = usedNames,
                issues = issues,
            )

            val activityEntries = when (strategy) {
                NamingStrategy.PACKAGES_ONLY -> emptyList()
                NamingStrategy.FULL_COMPATIBILITY -> packageEntries
                NamingStrategy.OPTIMIZED -> packageEntries.filter { it.drawableName != defaultDrawable }
            }

            activityEntries.forEach { entry ->
                val activityStem = HyperOsFileName.normalizeStem(entry.component.activityName)
                if (activityStem == null) {
                    issues += ConversionIssue(
                        IssueCode.INVALID_OUTPUT_NAME,
                        IssueSeverity.ERROR,
                        entry.component.activityName,
                        "Activity name cannot be represented safely in the icons module.",
                    )
                    return@forEach
                }
                addAlias(
                    alias = OutputAlias(
                        fileName = "$activityStem.png",
                        drawableName = entry.drawableName,
                        packageName = packageName,
                        activityName = entry.component.activityName,
                    ),
                    aliases = aliases,
                    usedNames = usedNames,
                    issues = issues,
                )
            }
        }

        return NamingPlan(aliases = aliases.sortedBy { it.fileName }, issues = issues)
    }

    private fun mostFrequentDrawable(entries: List<AppFilterEntry>): String {
        val counts = linkedMapOf<String, Int>()
        entries.sortedBy { it.sourceOrder }.forEach { entry ->
            counts[entry.drawableName] = (counts[entry.drawableName] ?: 0) + 1
        }
        return counts.maxBy { it.value }.key
    }

    private fun addAlias(
        alias: OutputAlias,
        aliases: MutableList<OutputAlias>,
        usedNames: MutableMap<String, OutputAlias>,
        issues: MutableList<ConversionIssue>,
    ) {
        val previous = usedNames[alias.fileName]
        when {
            previous == null -> {
                usedNames[alias.fileName] = alias
                aliases += alias
            }
            previous.drawableName == alias.drawableName -> Unit
            else -> issues += ConversionIssue(
                IssueCode.OUTPUT_NAME_COLLISION,
                IssueSeverity.ERROR,
                alias.fileName,
                "Two different icons normalize to the same output name.",
            )
        }
    }
}
