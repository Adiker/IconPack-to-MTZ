package io.github.adiker.iconpacktomtz.core.apk

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComponentParserTest {
    @Test
    fun parsesFullComponentInfo() {
        val result = ComponentParser.parse(
            "ComponentInfo{com.example.app/com.example.app.MainActivity}",
        )
        assertThat(result?.packageName).isEqualTo("com.example.app")
        assertThat(result?.activityName).isEqualTo("com.example.app.MainActivity")
        assertThat(result?.shortActivityName).isEqualTo(".MainActivity")
    }

    @Test
    fun expandsDotAndUnqualifiedActivityNames() {
        assertThat(
            ComponentParser.parse("com.example/.MainActivity")?.activityName,
        ).isEqualTo("com.example.MainActivity")
        assertThat(
            ComponentParser.parse("com.example/MainActivity")?.activityName,
        ).isEqualTo("com.example.MainActivity")
    }

    @Test
    fun rejectsTraversalAndMalformedComponents() {
        assertThat(ComponentParser.parse("com.example/../Bad")).isNull()
        assertThat(ComponentParser.parse("no-slash")).isNull()
        assertThat(ComponentParser.parse("ComponentInfo{/Bad}")).isNull()
    }
}
