package io.github.adiker.iconpacktomtz.core.apk

import android.content.res.XmlResourceParser
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlPullParser
import java.io.ByteArrayInputStream

/**
 * Public Android-facing bridge around ARSCLib. Consumers do not need to depend on ARSCLib
 * directly and can treat compiled Android XML as a regular [XmlResourceParser].
 */
object CompiledXml {
    fun newPullParser(bytes: ByteArray): XmlResourceParser {
        val document = ResXmlDocument().apply {
            readBytes(ByteArrayInputStream(bytes))
        }
        return ResXmlPullParser(document)
    }

}
