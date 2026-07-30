package io.github.adiker.iconpacktomtz.core.apk

import io.github.adiker.iconpacktomtz.core.archive.hasForbiddenXmlDeclaration
import io.github.adiker.iconpacktomtz.core.model.AppFilterEntry
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.ConversionIssue
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

data class AppFilterParseResult(
    val entries: List<AppFilterEntry>,
    val issues: List<ConversionIssue>,
)

class AppFilterParser {
    fun parse(bytes: ByteArray, limits: ArchiveLimits): AppFilterParseResult {
        if (bytes.size > limits.maxAppFilterBytes) {
            return AppFilterParseResult(
                emptyList(),
                listOf(
                    ConversionIssue(
                        IssueCode.APPFILTER_TOO_LARGE,
                        IssueSeverity.ERROR,
                        detail = "Mapping XML exceeds the configured size limit.",
                    ),
                ),
            )
        }
        if (bytes.hasForbiddenXmlDeclaration()) {
            return AppFilterParseResult(
                emptyList(),
                listOf(
                    ConversionIssue(
                        IssueCode.XML_FORBIDDEN_DECLARATION,
                        IssueSeverity.ERROR,
                        detail = "DOCTYPE and entity declarations are not accepted.",
                    ),
                ),
            )
        }
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                runCatching { setFeature(FEATURE_PROCESS_DOCDECL, false) }
                setInput(ByteArrayInputStream(bytes), "UTF-8")
            }
            parse(parser, limits)
        } catch (exception: Exception) {
            AppFilterParseResult(
                emptyList(),
                listOf(
                    ConversionIssue(
                        IssueCode.XML_PARSE_ERROR,
                        IssueSeverity.ERROR,
                        detail = "Cannot parse mapping XML: ${exception.javaClass.simpleName}",
                    ),
                ),
            )
        }
    }

    fun parse(parser: XmlPullParser, limits: ArchiveLimits): AppFilterParseResult {
        val entriesByComponent = linkedMapOf<String, AppFilterEntry>()
        val issues = mutableListOf<ConversionIssue>()
        var depth = 0
        var sourceOrder = 0

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> {
                        issues += ConversionIssue(
                            IssueCode.XML_FORBIDDEN_DECLARATION,
                            IssueSeverity.ERROR,
                            detail = "DOCTYPE and entity references are not accepted.",
                        )
                        return AppFilterParseResult(emptyList(), issues)
                    }
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth > limits.maxXmlDepth) {
                            issues += ConversionIssue(
                                IssueCode.XML_DEPTH_LIMIT,
                                IssueSeverity.ERROR,
                                detail = "Mapping XML nesting exceeds ${limits.maxXmlDepth}.",
                            )
                            return AppFilterParseResult(emptyList(), issues)
                        }
                        if (parser.name.equals("item", ignoreCase = true)) {
                            if (sourceOrder >= limits.maxAppFilterItems) {
                                issues += ConversionIssue(
                                    IssueCode.APPFILTER_TOO_MANY_ITEMS,
                                    IssueSeverity.ERROR,
                                    detail = "Mapping has more than ${limits.maxAppFilterItems} items.",
                                )
                                return AppFilterParseResult(emptyList(), issues)
                            }
                            val componentValue = parser.getAttributeValue(null, "component")
                                ?: parser.getAttributeValue("", "component")
                            val drawableValue = parser.getAttributeValue(null, "drawable")
                                ?: parser.getAttributeValue("", "drawable")
                            sourceOrder++
                            if (componentValue.isNullOrBlank() || drawableValue.isNullOrBlank()) {
                                issues += ConversionIssue(
                                    IssueCode.INVALID_COMPONENT,
                                    IssueSeverity.WARNING,
                                    detail = "Mapping item is missing component or drawable.",
                                )
                            } else {
                                val component = ComponentParser.parse(componentValue)
                                val drawable = drawableValue.trim()
                                if (component == null || !isSafeDrawableName(drawable)) {
                                    issues += ConversionIssue(
                                        IssueCode.INVALID_COMPONENT,
                                        IssueSeverity.WARNING,
                                        componentValue.take(256),
                                        "Invalid component or drawable name.",
                                    )
                                } else {
                                    val key = "${component.packageName}/${component.activityName}"
                                    val entry = AppFilterEntry(component, drawable, sourceOrder - 1)
                                    val previous = entriesByComponent.put(key, entry)
                                    if (previous != null && previous.drawableName != drawable) {
                                        issues += ConversionIssue(
                                            IssueCode.DUPLICATE_COMPONENT,
                                            IssueSeverity.WARNING,
                                            key,
                                            "Duplicate component; the last valid mapping wins.",
                                        )
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> depth = (depth - 1).coerceAtLeast(0)
                }
                event = parser.nextToken()
            }
        } catch (exception: Exception) {
            issues += ConversionIssue(
                IssueCode.XML_PARSE_ERROR,
                IssueSeverity.ERROR,
                detail = "Cannot parse mapping XML: ${exception.javaClass.simpleName}",
            )
        }
        return AppFilterParseResult(entriesByComponent.values.toList(), issues)
    }

    private fun isSafeDrawableName(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= 256 &&
            value.first().let { it.isLetter() || it == '_' } &&
            value.drop(1).all { it.isLetterOrDigit() || it == '_' }

    private companion object {
        const val FEATURE_PROCESS_DOCDECL =
            "http://xmlpull.org/v1/doc/features.html#process-docdecl"
    }
}
