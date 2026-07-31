package io.github.adiker.iconpacktomtz

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.ShizukuProvider

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

    @Test
    fun applicationRegistersProtectedShizukuBinderProvider() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, ShizukuProvider::class.java),
            0,
        )

        assertEquals("${context.packageName}.shizuku", provider.authority)
        assertEquals(SHIZUKU_PROVIDER_PERMISSION, provider.readPermission)
        assertEquals(SHIZUKU_PROVIDER_PERMISSION, provider.writePermission)
        assertTrue(provider.enabled)
        assertTrue(provider.exported)
    }

    private companion object {
        const val SHIZUKU_PROVIDER_PERMISSION = "android.permission.INTERACT_ACROSS_USERS_FULL"
    }
}
