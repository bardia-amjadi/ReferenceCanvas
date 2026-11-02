package app.referencecanvas.core.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

data class BackupFileInfo(val uri: Uri, val name: String, val sizeBytes: Long, val lastModified: Long)

/**
 * The user-chosen backup folder, accessed only through the Storage Access Framework. The app never writes anywhere
 * the user didn't pick, and never talks to a cloud API itself; whatever syncs that folder is the user's choice.
 */
@Singleton
class BackupDestination @Inject constructor(@ApplicationContext private val context: Context) {
    private val resolver get() = context.contentResolver

    fun persistPermission(tree: Uri) {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun releasePermission(tree: Uri) {
        try {
            resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Already gone; nothing to release.
        }
    }

    /** True if we still hold write permission and the folder still answers. */
    fun isAccessible(tree: Uri): Boolean {
        val granted = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!granted) return false
        return displayName(tree) != null
    }

    fun displayName(tree: Uri): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: FileNotFoundException) {
        null
    }

    fun createBackupFile(tree: Uri, name: String): Uri? = try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        DocumentsContract.createDocument(resolver, parent, "application/zip", name)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: FileNotFoundException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    /** Backups this app created in [tree], newest first (names carry a sortable UTC timestamp). */
    fun listBackups(tree: Uri): List<BackupFileInfo> {
        val treeId = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: IllegalArgumentException) { return emptyList() }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val found = ArrayList<BackupFileInfo>()
        try {
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!isOurBackupName(name)) continue
                    found += BackupFileInfo(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        name = name,
                        sizeBytes = if (c.isNull(2)) 0 else c.getLong(2),
                        lastModified = if (c.isNull(3)) 0 else c.getLong(3),
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: IllegalArgumentException) {
            return emptyList()
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        return found.sortedByDescending { it.name }
    }

    fun delete(document: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, document)
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: FileNotFoundException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    companion object {
        const val NAME_PREFIX = "ReferenceCanvas-backup-"
        const val NAME_SUFFIX = ".rcbackup.zip"
        fun isOurBackupName(name: String) = name.startsWith(NAME_PREFIX) && name.endsWith(NAME_SUFFIX)
    }
}
