package io.github.adiker.iconpacktomtz.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.adiker.iconpacktomtz.core.model.InstalledAppsProvider
import io.github.adiker.iconpacktomtz.core.model.PackageListCompleteness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StandardInstalledAppsProvider(
    private val context: Context,
) : InstalledAppsProvider {
    override val completeness = PackageListCompleteness.FILTERED_BY_ANDROID

    override suspend fun installedPackages(): Set<String> = withContext(Dispatchers.IO) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.MATCH_ALL,
            )
        }
        activities.mapTo(linkedSetOf()) { it.activityInfo.packageName }
    }
}
