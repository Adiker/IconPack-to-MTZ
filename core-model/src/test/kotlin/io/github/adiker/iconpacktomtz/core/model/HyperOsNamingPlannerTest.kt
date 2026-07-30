package io.github.adiker.iconpacktomtz.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HyperOsNamingPlannerTest {
    private val planner = HyperOsNamingPlanner()

    @Test
    fun optimized_singleDrawable_createsOnlyPackageFile() {
        val entries = listOf(
            entry("com.example", "com.example.First", "same", 0),
            entry("com.example", "com.example.Second", "same", 1),
        )

        val plan = planner.plan(entries, NamingStrategy.OPTIMIZED)

        assertThat(plan.aliases.map { it.fileName }).containsExactly("com.example.png")
    }

    @Test
    fun optimized_multipleDrawables_usesMostFrequentAndAliasesOthers() {
        val entries = listOf(
            entry("com.example", "com.example.First", "minority", 0),
            entry("com.example", "com.example.Second", "default", 1),
            entry("com.example", "com.example.Third", "default", 2),
        )

        val plan = planner.plan(entries, NamingStrategy.OPTIMIZED)

        assertThat(plan.aliases).containsExactly(
            OutputAlias("com.example.png", "default", "com.example"),
            OutputAlias(
                "com.example.First.png",
                "minority",
                "com.example",
                "com.example.First",
            ),
        )
    }

    @Test
    fun tie_isResolvedByFirstOccurrence() {
        val entries = listOf(
            entry("com.example", "com.example.First", "first", 0),
            entry("com.example", "com.example.Second", "second", 1),
        )
        assertThat(planner.plan(entries, NamingStrategy.PACKAGES_ONLY).aliases.single().drawableName)
            .isEqualTo("first")
    }

    @Test
    fun fullCompatibility_createsEveryActivityAlias() {
        val entries = listOf(
            entry("com.example", "com.example.First", "same", 0),
            entry("com.example", "com.example.Second", "same", 1),
        )
        assertThat(planner.plan(entries, NamingStrategy.FULL_COMPATIBILITY).aliases.map { it.fileName })
            .containsExactly(
                "com.example.png",
                "com.example.First.png",
                "com.example.Second.png",
            )
    }

    @Test
    fun unsafeNames_areRejected() {
        val unsafe = entry("../escape", "../escape.Activity", "icon", 0)
        val result = planner.plan(listOf(unsafe), NamingStrategy.OPTIMIZED)
        assertThat(result.aliases).isEmpty()
        assertThat(result.issues.map { it.code }).contains(IssueCode.INVALID_OUTPUT_NAME)
    }

    @Test
    fun fileNameNormalization_rejectsTraversalAndSeparators() {
        assertThat(HyperOsFileName.normalizeStem("../a")).isNull()
        assertThat(HyperOsFileName.normalizeStem("a/b")).isNull()
        assertThat(HyperOsFileName.normalizeStem("a\\b")).isNull()
        assertThat(HyperOsFileName.normalizeStem("com.example.App")).isEqualTo("com.example.App")
    }

    private fun entry(pkg: String, activity: String, drawable: String, order: Int) =
        AppFilterEntry(
            AppComponent(pkg, activity, activity.substringAfterLast('.')),
            drawable,
            order,
        )
}
