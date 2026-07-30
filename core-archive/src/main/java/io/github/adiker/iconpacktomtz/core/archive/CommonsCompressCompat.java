package io.github.adiker.iconpacktomtz.core.archive;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * Calls the long-lived ArchiveEntry overload. Android 17's boot class path contains an older
 * Commons Compress build that does not expose the newer ZipArchiveEntry overload.
 */
final class CommonsCompressCompat {
    private CommonsCompressCompat() {
    }

    static void putArchiveEntry(
            ZipArchiveOutputStream output,
            ZipArchiveEntry entry
    ) throws IOException {
        try {
            Method method = ZipArchiveOutputStream.class.getMethod(
                    "putArchiveEntry",
                    ArchiveEntry.class
            );
            method.invoke(output, entry);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IOException("Commons Compress is missing a compatible ZIP API.", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Commons Compress rejected a ZIP entry.", cause);
        }
    }
}
