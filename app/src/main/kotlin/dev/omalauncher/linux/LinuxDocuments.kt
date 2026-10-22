package dev.omalauncher.linux

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Filesystem operations for the home DocumentsProvider, testable without Android. */
internal class LinuxDocuments(val home: File) {
    fun fileOf(documentId: String): File {
        val file = File(home, relativePath(documentId))
        val real = file.canonicalFile
        val base = home.canonicalFile
        if (real != base && !real.toPath().startsWith(base.toPath())) throw FileNotFoundException("$documentId is outside home")
        if (!file.isDirectory && !file.isFile) throw FileNotFoundException(documentId)
        // Keep the lexical path: deleting or renaming a link must act on the link, not its target.
        return file
    }

    fun children(documentId: String): List<File> {
        val dir = directory(documentId)
        return (dir.listFiles() ?: throw FileNotFoundException("can't list $documentId"))
            .filter { child ->
                try {
                    isChild(documentId, childId(documentId, child.name))
                } catch (_: IOException) {
                    false
                }
            }
            .sortedBy { it.name.lowercase() }
    }

    fun isChild(parentDocumentId: String, documentId: String): Boolean =
        try {
            val parent = directory(parentDocumentId).canonicalFile.toPath()
            val child = fileOf(documentId).canonicalFile.toPath()
            documentId.startsWith("$parentDocumentId/") && child != parent && child.startsWith(parent)
        } catch (_: IOException) {
            false
        }

    fun create(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = directory(parentDocumentId)
        val name = safeName(displayName)
        while (true) {
            val target = File(dir, freeName(dir, name))
            val made = if (mimeType == DIRECTORY_MIME_TYPE) target.mkdir() else target.createNewFile()
            if (made) return childId(parentDocumentId, target.name)
            if (!Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw FileNotFoundException("can't create ${target.name}")
        }
    }

    fun rename(documentId: String, displayName: String, invalidated: (String) -> Unit = {}): String? {
        val file = mutableFile(documentId)
        val name = safeName(displayName)
        if (name == file.name) return null
        val target = File(file.parentFile, freeName(file.parentFile!!, name))
        val staleIds = affectedIds(documentId, file)
        Files.move(file.toPath(), target.toPath())
        // The framework transfers the caller's grant before revoking the renamed document itself.
        staleIds.filter { it != documentId }.forEach(invalidated)
        return childId(parentOf(documentId), target.name)
    }

    fun delete(documentId: String, deleted: (String) -> Unit = {}) {
        val file = mutableFile(documentId)
        val path = file.toPath()
        val staleIds = affectedIds(documentId, file)
        try {
            // walkFileTree does not follow links, including links nested inside a real directory.
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                private fun remove(entry: Path): FileVisitResult {
                    Files.delete(entry)
                    val relative = path.relativize(entry).toString()
                    deleted(if (relative.isEmpty()) documentId else "$documentId/$relative")
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes) = remove(file)

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    return remove(dir)
                }
            })
        } finally {
            // Partial deletion may already invalidate aliases; conservatively revoke the affected subtree.
            staleIds.forEach(deleted)
        }
    }

    private fun affectedIds(documentId: String, file: File): Set<String> {
        val affected = mutableSetOf(file.canonicalFile.toPath())
        // A link removed inside a subtree can invalidate aliases elsewhere in home.
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) {
                    try { affected.add(file.toFile().canonicalFile.toPath()) } catch (_: IOException) { }
                }
                return FileVisitResult.CONTINUE
            }
        })
        val ids = mutableSetOf<String>()
        val pending = ArrayDeque<String>().apply { add(HOME_ID) }
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            val entry = fileOf(id)
            val real = entry.canonicalFile.toPath()
            if (!documentId.startsWith("$id/") && affected.any { real.startsWith(it) }) ids.add(id)
            if (entry.isDirectory) {
                val children = try {
                    children(id)
                } catch (e: IOException) {
                    if (affected.any { real.startsWith(it) || it.startsWith(real) }) throw e
                    emptyList()
                }
                // Every child descends canonically, so even directory-link traversal cannot cycle.
                children.forEach { pending.add(childId(id, it.name)) }
            }
        }
        return ids
    }

    fun canModify(documentId: String): Boolean = fileOf(documentId).canonicalFile != home.canonicalFile

    private fun mutableFile(documentId: String): File {
        val file = fileOf(documentId)
        if (file.canonicalFile == home.canonicalFile) throw FileNotFoundException("home can't be deleted or renamed")
        return file
    }

    private fun directory(documentId: String): File = fileOf(documentId).also {
        if (!it.isDirectory) throw FileNotFoundException("$documentId is not a directory")
    }

    companion object {
        const val HOME_ID = "home"
        const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"

        fun childId(parent: String, name: String): String {
            relativePath(parent)
            validateSegment(name)
            return "$parent/$name"
        }

        fun parentOf(documentId: String): String {
            relativePath(documentId)
            return documentId.substringBeforeLast('/', HOME_ID)
        }

        // DocumentsContract encodes the entire id as a URI segment; percent and plus are literal filenames here.
        fun relativePath(documentId: String): String {
            if (documentId == HOME_ID) return ""
            if (!documentId.startsWith("$HOME_ID/")) throw FileNotFoundException(documentId)
            return documentId.removePrefix("$HOME_ID/").also { path -> path.split('/').forEach(::validateSegment) }
        }

        private fun validateSegment(name: String) {
            if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name || isProotLink(name)) {
                throw FileNotFoundException("not a document name: $name")
            }
        }

        // proot's link2symlink backing files must not be edited separately from their linked names.
        fun isProotLink(name: String) = name.startsWith(".l2s.")

        fun safeName(name: String): String {
            val clean = name.replace('/', '_').replace('\u0000', '_').trim()
            return (if (clean.isEmpty() || clean == "." || clean == "..") "untitled" else clean).also(::validateSegment)
        }

        fun freeName(dir: File, name: String): String {
            validateSegment(name)
            if (!Files.exists(File(dir, name).toPath(), NOFOLLOW_LINKS)) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val (base, ext) = name.substring(0, dot) to name.substring(dot)
            return generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }
                .first { !Files.exists(File(dir, it).toPath(), NOFOLLOW_LINKS) }
        }
    }
}
