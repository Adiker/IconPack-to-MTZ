package io.github.adiker.iconpacktomtz.core.apk

import com.google.common.truth.Truth.assertThat
import io.github.adiker.iconpacktomtz.core.model.ArchiveLimits
import io.github.adiker.iconpacktomtz.core.model.IssueCode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppFilterParserTest {
    private val parser = AppFilterParser()

    @Test
    fun parsesItemsAndLastDuplicateWins() {
        val xml = """
            <resources>
              <item component="ComponentInfo{com.example/.Main}" drawable="first" />
              <item component="ComponentInfo{com.example/.Main}" drawable="second" />
            </resources>
        """.trimIndent().toByteArray()

        val result = parser.parse(xml, ArchiveLimits())

        assertThat(result.entries).hasSize(1)
        assertThat(result.entries.single().drawableName).isEqualTo("second")
        assertThat(result.issues.map { it.code }).contains(IssueCode.DUPLICATE_COMPONENT)
    }

    @Test
    fun rejectsDoctypeAndEntityDeclarations() {
        val xml = """
            <!DOCTYPE resources [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <resources><item component="com.example/.Main" drawable="icon" /></resources>
        """.trimIndent().toByteArray()

        val result = parser.parse(xml, ArchiveLimits())

        assertThat(result.entries).isEmpty()
        assertThat(result.issues.map { it.code }).contains(IssueCode.XML_FORBIDDEN_DECLARATION)
    }

    @Test
    fun enforcesItemLimit() {
        val xml = """
            <resources>
              <item component="com.one/.Main" drawable="one" />
              <item component="com.two/.Main" drawable="two" />
            </resources>
        """.trimIndent().toByteArray()
        val result = parser.parse(xml, ArchiveLimits(maxAppFilterItems = 1))
        assertThat(result.issues.map { it.code }).contains(IssueCode.APPFILTER_TOO_MANY_ITEMS)
    }
}
