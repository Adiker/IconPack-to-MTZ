package io.github.adiker.iconpacktomtz.core.mtz

import android.util.Xml
import io.github.adiker.iconpacktomtz.core.model.ThemeMetadata
import java.io.ByteArrayOutputStream

object DescriptionXml {
    fun create(metadata: ThemeMetadata): ByteArray {
        val output = ByteArrayOutputStream()
        val serializer = Xml.newSerializer()
        serializer.setOutput(output, Charsets.UTF_8.name())
        serializer.startDocument(Charsets.UTF_8.name(), true)
        serializer.startTag(null, "MIUI-Theme")
        serializer.element("version", metadata.version)
        serializer.element("uiVersion", metadata.uiVersion.toString())
        serializer.element("author", metadata.author)
        serializer.element("designer", metadata.designer.ifBlank { metadata.author })
        serializer.element("title", metadata.title)
        serializer.element("description", metadata.description)
        serializer.endTag(null, "MIUI-Theme")
        serializer.endDocument()
        serializer.flush()
        return output.toByteArray()
    }

    private fun org.xmlpull.v1.XmlSerializer.element(name: String, value: String) {
        startTag(null, name)
        text(value)
        endTag(null, name)
    }
}
