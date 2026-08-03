package com.metaforge.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest

/**
 * Bridges Android's Storage Access Framework to the plain file paths the
 * ExifTool engine needs.
 *
 * Scoped storage means the engine cannot touch a gallery Uri directly, so a
 * working copy is staged in the app cache, modified there, then written back
 * through the original Uri. The original is never touched until a write
 * succeeds, and a pre-edit backup is always kept.
 */
class MediaAccess(private val context: Context) {

    data class Staged(
        val uri: Uri,
        val workingCopy: File,
        val displayName: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    private val workDir: File by lazy {
        File(context.cacheDir, "work").apply { mkdirs() }
    }

    private val backupDir: File by lazy {
        File(context.filesDir, "backups").apply { mkdirs() }
    }

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Copies the content behind [uri] into a private working file. */
    fun stage(uri: Uri, onBytes: ((Long, Long) -> Unit)? = null): Staged {
        val name = displayName(uri)
        val target = File(workDir, "${System.nanoTime()}_$name")
        val total = sizeOf(uri)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L

        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    copied += n
                    onBytes?.invoke(copied, total)
                }
            }
        }
        return Staged(uri, target, name, copied, digest.digest().toHex())
    }

    /** Keeps an untouched copy of the original before the first write. */
    fun backup(staged: Staged): File {
        val dest = File(backupDir, "${staged.sha256.take(16)}_${staged.displayName}")
        if (!dest.exists()) staged.workingCopy.copyTo(dest, overwrite = false)
        return dest
    }

    /** Writes the modified working copy back over the original Uri. */
    fun commit(staged: Staged): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(staged.uri, "wt")!!.use { out ->
            staged.workingCopy.inputStream().use { it.copyTo(out) }
        }
    }

    /** Saves the result next to the original instead of overwriting it. */
    fun saveAsCopy(staged: Staged, treeUri: Uri, suffix: String = "_metaforge"): Result<Uri> =
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("cannot open destination folder")
            val base = staged.displayName.substringBeforeLast('.', staged.displayName)
            val ext = staged.displayName.substringAfterLast('.', "")
            val newName = if (ext.isEmpty()) "$base$suffix" else "$base$suffix.$ext"
            val doc = tree.createFile(mimeOf(staged.displayName), newName)
                ?: error("cannot create $newName")
            context.contentResolver.openOutputStream(doc.uri)!!.use { out ->
                staged.workingCopy.inputStream().use { it.copyTo(out) }
            }
            doc.uri
        }

    /** Writes the working copy to any destination the user picked. */
    fun exportTo(staged: Staged, destination: Uri): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(destination, "wt")!!.use { out ->
            staged.workingCopy.inputStream().use { it.copyTo(out) }
        }
    }

    /** Puts the untouched original back over the file, if a backup was kept. */
    fun restore(staged: Staged): Result<Unit> = runCatching {
        val backup = File(backupDir, "${staged.sha256.take(16)}_${staged.displayName}")
        require(backup.exists()) { "no untouched copy was kept for this file" }
        context.contentResolver.openOutputStream(staged.uri, "wt")!!.use { out ->
            backup.inputStream().use { it.copyTo(out) }
        }
        backup.copyTo(staged.workingCopy, overwrite = true)
    }

    fun hasBackup(staged: Staged): Boolean =
        File(backupDir, "${staged.sha256.take(16)}_${staged.displayName}").exists()

    fun cleanup(staged: Staged) {
        staged.workingCopy.delete()
    }

    fun sizeOf(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return -1
    }

    fun persistPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun isDocumentUri(uri: Uri) = DocumentsContract.isDocumentUri(context, uri)

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        "avif" -> "image/avif"
        "tif", "tiff" -> "image/tiff"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
