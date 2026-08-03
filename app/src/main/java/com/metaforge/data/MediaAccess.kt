package com.metaforge.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest

/**
 * Bridges Android's Storage Access Framework to the plain file paths the
 * ExifTool engine needs.
 *
 * Scoped storage means the engine cannot touch a gallery Uri directly, so a
 * working copy is staged in the app cache, modified there, then written back
 * through the original Uri.
 *
 * The undo copy is taken inside [commit], not at staging time. Until the user
 * actually asks to overwrite, the file on their phone has not been touched at
 * all, so a copy taken any earlier would double the storage cost of every
 * single file they open in exchange for nothing. Taken at the last moment it
 * costs space only for files that are genuinely at risk.
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

    /**
     * Copies the content behind [uri] into a private working file.
     *
     * Stale work from earlier sessions is cleared first. Without this every
     * file the user ever opened stayed in the cache forever, so opening five
     * large videos quietly cost a gigabyte.
     */
    fun stage(uri: Uri, onBytes: ((Long, Long) -> Unit)? = null): Staged {
        sweep()
        val name = displayName(uri)
        val total = sizeOf(uri)
        requireRoom(total, workDir)

        val target = File(workDir, "${System.nanoTime()}_${safeName(name)}")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L

        try {
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
        } catch (t: Throwable) {
            // A half-written working copy is worse than none: it would be read
            // as a truncated image and reported as a corrupt file.
            target.delete()
            throw t
        }
        return Staged(uri, target, name, copied, digest.digest().toHex())
    }

    // ------------------------------------------------------------------ undo

    private fun undoFile(staged: Staged) =
        File(backupDir, "${staged.sha256.take(16)}_${safeName(staged.displayName)}")

    fun hasBackup(staged: Staged): Boolean = undoFile(staged).exists()

    fun backupSize(staged: Staged): Long = undoFile(staged).let { if (it.exists()) it.length() else 0L }

    /**
     * Writes the modified working copy back over the original Uri, keeping a
     * copy of what was there first so the change can be undone.
     *
     * If the undo copy cannot be made the write still goes ahead, because the
     * user asked for it, but [hasBackup] will report false afterwards and the
     * screen says so rather than offering an undo that would not work.
     */
    fun commit(staged: Staged): Result<Unit> = runCatching {
        val undo = undoFile(staged)
        if (!undo.exists()) {
            runCatching {
                requireRoom(staged.sizeBytes, backupDir)
                val tmp = File(backupDir, undo.name + ".part")
                context.contentResolver.openInputStream(staged.uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (!tmp.renameTo(undo)) tmp.delete()
            }.onFailure { Log.w(TAG, "no undo copy for ${staged.displayName}: ${it.message}") }
        }
        context.contentResolver.openOutputStream(staged.uri, "wt")!!.use { out ->
            staged.workingCopy.inputStream().use { it.copyTo(out) }
        }
    }

    /** Puts the untouched original back over the file. */
    fun restore(staged: Staged): Result<Unit> = runCatching {
        val undo = undoFile(staged)
        require(undo.exists()) { "no untouched copy was kept for this file" }
        context.contentResolver.openOutputStream(staged.uri, "wt")!!.use { out ->
            undo.inputStream().use { it.copyTo(out) }
        }
        undo.copyTo(staged.workingCopy, overwrite = true)
    }

    /** Drops the undo copy once the user is happy with the change. */
    fun forgetBackup(staged: Staged) {
        undoFile(staged).delete()
    }

    // ---------------------------------------------------------------- export

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

    // --------------------------------------------------------------- upkeep

    /** Deletes the working copy for one file. */
    fun cleanup(staged: Staged) {
        staged.workingCopy.delete()
    }

    /**
     * Clears anything left behind by an earlier session.
     *
     * Working copies are throwaway and go after six hours. Undo copies are the
     * user's safety net, so they live for a week, and only the oldest are
     * dropped if the folder grows past its cap.
     */
    fun sweep() {
        runCatching {
            val now = System.currentTimeMillis()
            workDir.listFiles()?.forEach {
                if (now - it.lastModified() > WORK_MAX_AGE_MS) it.delete()
            }
            val undos = backupDir.listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
            undos.forEach { if (now - it.lastModified() > UNDO_MAX_AGE_MS) it.delete() }
            var total = backupDir.listFiles()?.sumOf { it.length() } ?: 0L
            for (f in undos) {
                if (total <= UNDO_MAX_BYTES) break
                total -= f.length()
                f.delete()
            }
        }.onFailure { Log.w(TAG, "sweep failed: ${it.message}") }
    }

    /** How much of the phone MetaForge is currently holding, for the UI. */
    fun storageUsedBytes(): Long =
        (workDir.listFiles()?.sumOf { it.length() } ?: 0L) +
            (backupDir.listFiles()?.sumOf { it.length() } ?: 0L)

    fun clearAllStorage() {
        workDir.listFiles()?.forEach { it.delete() }
        backupDir.listFiles()?.forEach { it.delete() }
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

    // --------------------------------------------------------------- private

    /**
     * Refuses the operation up front when the phone has no room for it, so the
     * user gets a sentence they can act on instead of a truncated file and an
     * ENOSPC somewhere deep in a copy loop.
     */
    private fun requireRoom(bytes: Long, where: File) {
        if (bytes <= 0) return
        val free = runCatching { where.usableSpace }.getOrDefault(Long.MAX_VALUE)
        val needed = bytes + SAFETY_MARGIN_BYTES
        if (free < needed) {
            error(
                "this needs ${human(needed)} of free space and the phone has ${human(free)} left",
            )
        }
    }

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
        else -> "$b bytes"
    }

    /** Keeps a hostile display name from escaping the cache directory. */
    private fun safeName(name: String): String =
        name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").takeLast(80).ifBlank { "file" }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        "avif" -> "image/avif"
        "tif", "tiff" -> "image/tiff"
        "dng" -> "image/x-adobe-dng"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "MediaAccess"
        const val WORK_MAX_AGE_MS = 6L * 60 * 60 * 1000
        const val UNDO_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val UNDO_MAX_BYTES = 1L shl 30          // 1 GB of undo copies, no more
        const val SAFETY_MARGIN_BYTES = 64L shl 20    // never fill the last 64 MB
    }
}
