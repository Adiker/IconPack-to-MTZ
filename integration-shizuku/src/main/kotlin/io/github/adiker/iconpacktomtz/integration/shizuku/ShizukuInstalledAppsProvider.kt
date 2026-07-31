package io.github.adiker.iconpacktomtz.integration.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.github.adiker.iconpacktomtz.core.model.InstalledAppsProvider
import io.github.adiker.iconpacktomtz.core.model.PackageListCompleteness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuInstalledAppsProvider(
    context: Context,
) : InstalledAppsProvider {
    private val applicationContext = context.applicationContext
    override val completeness = PackageListCompleteness.COMPLETE_VIA_SHIZUKU

    val isAvailable: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val hasPermission: Boolean
        get() = isAvailable &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        if (isAvailable && !hasPermission) Shizuku.requestPermission(requestCode)
    }

    override suspend fun installedPackages(): Set<String> {
        check(isAvailable) { "Shizuku is not available." }
        check(hasPermission) { "Shizuku permission has not been granted." }
        val args = Shizuku.UserServiceArgs(
            ComponentName(applicationContext, InstalledAppsUserService::class.java),
        )
            .daemon(false)
            .processNameSuffix("installed_apps")
            .tag("installed_apps")
            .debuggable(false)
            .version(1)

        return suspendCancellableCoroutine { continuation ->
            lateinit var connection: ServiceConnection
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val api = IInstalledAppsService.Stub.asInterface(service)
                    if (api == null) {
                        Shizuku.unbindUserService(args, connection, true)
                        continuation.resumeWithException(IllegalStateException("Invalid Shizuku service."))
                        return
                    }
                    val callbackScope = CoroutineScope(Dispatchers.IO)
                    callbackScope.launch {
                            try {
                                val result = api.listPackages().toSet()
                                runCatching { api.destroy() }
                                Shizuku.unbindUserService(args, connection, true)
                                if (continuation.isActive) continuation.resume(result)
                            } catch (exception: Exception) {
                                Shizuku.unbindUserService(args, connection, true)
                                if (continuation.isActive) {
                                    continuation.resumeWithException(exception)
                                }
                            } finally {
                                callbackScope.cancel()
                            }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Shizuku service disconnected."),
                        )
                    }
                }
            }
            continuation.invokeOnCancellation {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
            Shizuku.bindUserService(args, connection)
        }
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 7431
    }
}
