package io.github.adiker.iconpacktomtz

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformSmokeTest {
    @Test
    fun applicationDoesNotRequestNetworkOrBroadPackageVisibility() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertFalse(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.QUERY_ALL_PACKAGES in permissions)
    }
}
