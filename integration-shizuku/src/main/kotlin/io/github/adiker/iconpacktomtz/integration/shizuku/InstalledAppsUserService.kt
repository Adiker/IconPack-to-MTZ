package io.github.adiker.iconpacktomtz.integration.shizuku

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class InstalledAppsUserService : IInstalledAppsService.Stub() {
    override fun listPackages(): List<String> {
        val process = ProcessBuilder(
            "cmd",
            "package",
            "list",
            "packages",
            "--user",
            "current",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().useLines { lines ->
            lines.take(MAX_OUTPUT_LINES)
                .mapNotNull { line ->
                    line.removePrefix("package:")
                        .trim()
                        .takeIf(::isSafePackageName)
                }
                .toList()
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Package query timed out.")
        }
        check(process.exitValue() == 0) { "Package query failed." }
        return output.distinct()
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun isSafePackageName(value: String): Boolean =
        value.length in 3..255 &&
            value.contains('.') &&
            value.split('.').all { segment ->
                segment.isNotEmpty() &&
                    (segment.first().isLetter() || segment.first() == '_') &&
                    segment.drop(1).all { it.isLetterOrDigit() || it == '_' }
            }

    private companion object {
        const val MAX_OUTPUT_LINES = 100_000
        const val TIMEOUT_SECONDS = 15L
    }
}
