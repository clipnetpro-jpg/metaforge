package com.metaforge.engine

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything a file contains, as the app needs to show and change it.
 *
 * Two reads, not one. ExifTool's default output is the human reading of a tag
 * ("Rotate 90 CW", "1/250"); `-n` is the number actually stored (6, 0.004).
 * A viewer that shows only the first cannot be trusted for forensics and a
 * writer that only accepts the first cannot set half the tags, so both are
 * carried side by side for every tag and either can be written.
 */
class MetadataRepository(private val exifTool: ExifTool) {

    /**
     * @param group family-1 group, e.g. IFD0, ExifIFD, GPS, XMP-dc, MakerNotes
     * @param printValue the interpreted reading
     * @param rawValue the stored value, when it differs
     * @param structure nested JSON for XMP structs and C2PA manifests
     */
    data class Tag(
        val group: String,
        val name: String,
        val printValue: String,
        val rawValue: String?,
        val structure: String? = null,
        val binaryBytes: Int? = null,
    ) {
        /** What ExifTool needs on the command line to address this tag. */
        val qualified: String get() = "$group:$name"
        val isBinary: Boolean get() = binaryBytes != null
        val differs: Boolean get() = rawValue != null && rawValue != printValue
    }

    data class Group(val name: String, val tags: List<Tag>) {
        val label: String get() = GROUP_LABELS[name] ?: name
    }

    data class Document(
        val groups: List<Group>,
        val tagCount: Int,
        val elapsedMs: Long,
        val error: String?,
    ) {
        fun find(qualified: String): Tag? =
            groups.firstNotNullOfOrNull { g -> g.tags.firstOrNull { it.qualified == qualified } }

        fun flat(): List<Tag> = groups.flatMap { it.tags }
    }

    /** Reads every tag in the file, including duplicates and unknown ones. */
    fun read(file: File): Document {
        val t0 = System.nanoTime()
        val printed = readJson(file, numeric = false)
        val numeric = readJson(file, numeric = true)
        if (printed == null) {
            return Document(
                emptyList(), 0, (System.nanoTime() - t0) / 1_000_000,
                "This file could not be read as a media container.",
            )
        }

        val tags = mutableListOf<Tag>()
        printed.keys().forEach { key ->
            if (key == "SourceFile") return@forEach
            val group = key.substringBefore(':', "Other")
            val name = key.substringAfter(':')
            val pv = printed.opt(key)
            val nv = numeric?.opt(key)

            val structure = when {
                pv is JSONObject -> pv.toString(2)
                pv is JSONArray && pv.length() > 0 && pv.opt(0) is JSONObject -> pv.toString(2)
                else -> null
            }
            val printText = flatten(pv)
            val rawText = nv?.let { flatten(it) }?.takeIf { it != printText }
            val binary = BINARY_HINT.find(printText)?.groupValues?.get(1)?.toIntOrNull()

            tags += Tag(
                group = group,
                name = name,
                printValue = printText,
                rawValue = rawText,
                structure = structure,
                binaryBytes = binary,
            )
        }

        val ordered = tags
            .groupBy { it.group }
            .map { (g, list) -> Group(g, list.sortedBy { it.name }) }
            .sortedWith(compareBy({ GROUP_ORDER.indexOf(it.name).let { i -> if (i < 0) 500 else i } }, { it.name }))

        return Document(ordered, tags.size, (System.nanoTime() - t0) / 1_000_000, null)
    }

    /**
     * The bytes behind a binary tag, for the hex view.
     *
     * Goes through the byte-level path, not the daemon. The daemon returns text
     * decoded as UTF-8, so a thumbnail or a maker-note blob came back mangled
     * and the hex view showed bytes that were never in the file.
     */
    fun binary(file: File, qualified: String, limit: Int = 4096): ByteArray {
        val bytes = exifTool.binary(qualified, file.absolutePath)
        return if (bytes.size > limit) bytes.copyOf(limit) else bytes
    }

    sealed interface WriteResult {
        data class Ok(val message: String) : WriteResult
        data class Failed(val reason: String) : WriteResult
    }

    /**
     * Writes one tag. [raw] selects `Tag#=value`, the numeric form, which is
     * what a power user needs when the printed form is ambiguous.
     */
    fun write(file: File, qualified: String, value: String, raw: Boolean = false): WriteResult {
        val arg = if (raw) "-$qualified#=$value" else "-$qualified=$value"
        return apply(file, listOf(arg), "$qualified written")
    }

    fun delete(file: File, qualified: String): WriteResult =
        apply(file, listOf("-$qualified="), "$qualified removed")

    fun deleteGroup(file: File, group: String): WriteResult =
        apply(file, listOf("-$group:all="), "$group cleared")

    /** Applies a batch of already-formed ExifTool assignments in one pass. */
    fun apply(file: File, args: List<String>, okMessage: String): WriteResult {
        if (args.isEmpty()) return WriteResult.Ok("nothing to change")
        val full = args + listOf("-m", "-overwrite_original", file.absolutePath)
        val res = exifTool.execute(*full.toTypedArray())
        val out = (res.stdout + "\n" + res.stderr).trim()
        val updated = Regex("""(\d+)\s+(?:image )?files? updated""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return when {
            !res.ok -> WriteResult.Failed(out.ifBlank { "the engine did not respond" })
            updated > 0 -> WriteResult.Ok(okMessage)
            out.contains("Error", true) || out.contains("Warning", true) ->
                WriteResult.Failed(out.lines().firstOrNull { it.contains("rror") || it.contains("arning") } ?: out)
            else -> WriteResult.Failed(out.ifBlank { "nothing was written" })
        }
    }

    /** Free-form ExifTool invocation for the command console. */
    fun runRaw(file: File, arguments: List<String>): String {
        val res = exifTool.execute(*(arguments + file.absolutePath).toTypedArray())
        return buildString {
            if (res.stdout.isNotBlank()) appendLine(res.stdout)
            if (res.stderr.isNotBlank()) appendLine(res.stderr)
            if (isEmpty()) append("(no output)")
        }.trim()
    }

    /** Every group ExifTool can write into, for the "add a tag" picker. */
    fun writableGroups(): List<String> = WRITABLE_GROUPS

    private fun readJson(file: File, numeric: Boolean): JSONObject? {
        val args = mutableListOf("-json", "-a", "-u", "-G1", "-s", "-struct", "-charset", "utf8")
        if (numeric) args += "-n"
        args += file.absolutePath
        val res = exifTool.execute(*args.toTypedArray())
        if (!res.ok || res.stdout.isBlank()) return null
        return runCatching {
            val arr = JSONArray(res.stdout)
            if (arr.length() == 0) null else arr.getJSONObject(0)
        }.getOrNull()
    }

    private fun flatten(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until value.length()).joinToString(", ") { flatten(value.opt(it)) }
        is JSONObject -> value.keys().asSequence().joinToString(", ") { "$it=${flatten(value.opt(it))}" }
        else -> value.toString()
    }

    companion object {
        private val BINARY_HINT = Regex("""use -b option to extract\)|\(Binary data (\d+) bytes""")

        /** Sensible reading order: the file, then the camera, then everything else. */
        private val GROUP_ORDER = listOf(
            "File", "System", "Composite", "ExifIFD", "IFD0", "IFD1", "GPS",
            "XMP-dc", "XMP-xmp", "XMP-photoshop", "XMP-crs", "XMP-exif", "XMP-tiff",
            "IPTC", "Photoshop", "ICC-header", "ICC_Profile", "MakerNotes",
            "Canon", "Nikon", "Sony", "Apple", "Samsung", "Panasonic", "Olympus",
            "QuickTime", "Track1", "Track2", "Matroska", "PNG", "JUMBF", "C2PA",
        )

        private val GROUP_LABELS = mapOf(
            "IFD0" to "IFD0 - main image directory",
            "IFD1" to "IFD1 - thumbnail directory",
            "ExifIFD" to "Exif - exposure record",
            "GPS" to "GPS - location",
            "IPTC" to "IPTC - press metadata",
            "MakerNotes" to "Maker notes - vendor private data",
            "ICC_Profile" to "ICC - colour profile",
            "ICC-header" to "ICC header",
            "Composite" to "Composite - values ExifTool derives",
            "File" to "File - container facts",
            "System" to "System - filesystem",
            "QuickTime" to "QuickTime - video container",
            "JUMBF" to "JUMBF - C2PA container",
            "C2PA" to "C2PA - content credentials",
            "Photoshop" to "Photoshop image resources",
        )

        private val WRITABLE_GROUPS = listOf(
            "EXIF", "ExifIFD", "IFD0", "GPS", "IPTC", "XMP", "XMP-dc", "XMP-xmp",
            "XMP-photoshop", "XMP-rights", "Photoshop", "QuickTime", "PNG", "Composite",
        )
    }
}
