package dev.omalauncher.linux

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Shared Android files enter the guest namespace, never Android's interpretation of a guest symlink. */
internal fun importGuestFile(fs: GuestFs, displayName: String, input: InputStream): String {
    val name = displayName.replace('/', '_').replace('\u0000', '_').takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "shared"
    val folder = "/root/Downloads"
    val dir = fs.host(folder)
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create Downloads")
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    var index = 0
    while (true) {
        val candidate = if (index == 0) name else name.substring(0, dot) + " ($index)" + name.substring(dot)
        index++
        val path = "$folder/$candidate"
        // Leave the final component unfollowed; CREATE_NEW refuses existing files and dangling links atomically.
        val target = fs.host(path, followLast = false).toPath()
        val output = try {
            Files.newOutputStream(target, CREATE_NEW, WRITE)
        } catch (_: FileAlreadyExistsException) {
            continue
        }
        try {
            output.use { input.copyTo(it) }
            return path
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(target) }
            throw e
        }
    }
}

/** Resolve again when answering: the guest may have changed a link while its picker was open. */
internal fun guestReplyFile(fs: GuestFs, path: String): File? = runCatching {
    val prefix = Proot.GUEST_RUNTIME_DIR + "/"
    if (!path.startsWith(prefix) || path.split('/').any { it == ".." }) return null
    val resolved = fs.resolve(path)
    if (!resolved.startsWith(prefix)) return null
    fs.host(resolved)
}.getOrNull()
