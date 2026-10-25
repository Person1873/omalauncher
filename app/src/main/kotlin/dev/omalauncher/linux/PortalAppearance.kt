package dev.omalauncher.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale

/** The Settings portal's snapshot. The containing runtime directory is an Android-owned bind.
 * Replace the final entry, never follow a guest-supplied destination symlink. */
internal object PortalAppearance {
    const val FILE_NAME = "omalauncher-color-scheme"

    fun text(light: Boolean, accent: Int): String =
        (if (light) "light" else "dark") + "\n" + String.format(Locale.ROOT, "#%06x", accent and 0xffffff) + "\n"

    fun write(runtimeDir: File, light: Boolean, accent: Int) {
        val temporary = Files.createTempFile(runtimeDir.toPath(), ".portal-appearance-", ".tmp")
        try {
            Files.write(temporary, text(light, accent).toByteArray(Charsets.UTF_8))
            Files.move(temporary, File(runtimeDir, FILE_NAME).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
