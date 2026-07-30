package io.github.adiker.iconpacktomtz.core.archive

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    fun bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun file(file: File): String =
        file.inputStream().buffered().use(::stream)

    fun stream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
