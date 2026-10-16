package dev.omalauncher.linux

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dev.omalauncher.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Linux's home folder in Android's file picker and Files app, so Android
 * apps open and save Linux files directly. Only the system's documents UI
 * can bind to a documents provider, and apps get just what the user picks.
 *
 * Document ids are "home" and "home/<path relative to home>". Nothing
 * resolves outside home: a symlink that points elsewhere in the guest (or
 * into the host) can't be opened through here.
 */
class LinuxDocumentsProvider : DocumentsProvider() {
    private val home: File by lazy { File(context!!.filesDir, "arch/root") }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        // No Linux, no root: the picker doesn't show an empty "Linux".
        if (!home.isDirectory) return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, HOME_ID)
            add(Root.COLUMN_TITLE, "Linux")
            add(Root.COLUMN_SUMMARY, "home folder")
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_AVAILABLE_BYTES, home.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { addRow(it, documentId, fileOf(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val dir = fileOf(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        dir.listFiles()
            ?.filterNot { isProotLink(it.name) }
            ?.forEach { child -> runCatching { addRow(cursor, childId(parentDocumentId, child.name), child) } }
        cursor.setNotificationUri(context!!.contentResolver, childrenUri(parentDocumentId))
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileOf(documentId)
        if (file.isDirectory) throw FileNotFoundException("$documentId is a folder")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val dir = fileOf(parentDocumentId)
        val name = freeName(dir, safeName(displayName))
        val file = File(dir, name)
        val made = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!made) throw FileNotFoundException("can't create $name")
        changed(parentDocumentId)
        return childId(parentDocumentId, name)
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == HOME_ID) throw FileNotFoundException("home can't be deleted")
        val file = fileOf(documentId)
        if (!file.deleteRecursively()) throw FileNotFoundException("can't delete $documentId")
        changed(parentOf(documentId))
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String {
        if (documentId == HOME_ID) throw FileNotFoundException("home can't be renamed")
        val file = fileOf(documentId)
        val parent = parentOf(documentId)
        val name = freeName(file.parentFile!!, safeName(displayName))
        if (!file.renameTo(File(file.parentFile, name))) throw FileNotFoundException("can't rename $documentId")
        changed(parent)
        return childId(parent, name)
    }

    override fun getDocumentType(documentId: String): String = mimeOf(fileOf(documentId))

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean = documentId.startsWith("$parentDocumentId/")

    /** The file for an id, refusing anything that resolves outside home. */
    private fun fileOf(documentId: String): File {
        val file = File(home, relativePath(documentId))
        val real = file.canonicalFile
        val base = home.canonicalFile
        if (real != base && !real.path.startsWith(base.path + File.separator)) throw FileNotFoundException("$documentId is outside home")
        if (!file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun addRow(
        cursor: MatrixCursor,
        documentId: String,
        file: File,
    ) {
        val dir = file.isDirectory
        var flags = 0
        if (dir) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (!dir && file.canWrite()) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (documentId != HOME_ID) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (documentId == HOME_ID) "home" else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            add(Document.COLUMN_SIZE, if (dir) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun changed(parentDocumentId: String) = context!!.contentResolver.notifyChange(childrenUri(parentDocumentId), null)

    private fun childrenUri(parentDocumentId: String) = DocumentsContract.buildChildDocumentsUri(context!!.packageName + AUTHORITY_SUFFIX, parentDocumentId)

    companion object {
        /** The authority is the app id plus this, as the manifest declares it. */
        const val AUTHORITY_SUFFIX = ".linux"
        private const val ROOT_ID = "home"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** Home's id. Not "": Android puts ids in content URIs as a path segment. */
        internal const val HOME_ID = "home"

        internal fun childId(
            parent: String,
            name: String,
        ) = "$parent/$name"

        internal fun parentOf(documentId: String) = documentId.substringBeforeLast('/', HOME_ID)

        /** The path under home an id names. */
        internal fun relativePath(documentId: String): String =
            when {
                documentId == HOME_ID -> ""
                documentId.startsWith("$HOME_ID/") -> documentId.removePrefix("$HOME_ID/")
                else -> throw FileNotFoundException(documentId)
            }

        /**
         * proot's stand-ins for hard links (link2symlink): the real file behind a hard-linked name. Hidden, as in
         * Linux, but also from dotfile listings: deleting one through the picker breaks every name linked to it.
         */
        internal fun isProotLink(name: String) = name.startsWith(".l2s.")

        /** A name the picker may pass, made safe as one path segment. */
        internal fun safeName(name: String): String {
            val clean = name.replace('/', '_').replace('\u0000', '_').trim()
            return if (clean.isEmpty() || clean == "." || clean == "..") "untitled" else clean
        }

        /** [name], or "name (1).ext" and so on when it's taken. */
        internal fun freeName(
            dir: File,
            name: String,
        ): String {
            if (!File(dir, name).exists()) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val (base, ext) = name.substring(0, dot) to name.substring(dot)
            return generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }.first { !File(dir, it).exists() }
        }

        internal fun mimeOf(file: File): String {
            if (file.isDirectory) return Document.MIME_TYPE_DIR
            val ext = file.name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
