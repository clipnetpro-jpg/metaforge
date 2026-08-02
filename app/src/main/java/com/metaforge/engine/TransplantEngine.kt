package com.metaforge.engine

import com.metaforge.core.Stage
import com.metaforge.core.progressFlow
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Moves the metadata of one image or video onto another, losing nothing.
 *
 * Two passes, because neither alone is complete:
 *
 *  1. ExifTool `-tagsFromFile -all:all -unsafe -icc_profile` moves every
 *     writable tag: EXIF, IPTC, XMP, ICC, maker notes, QuickTime, PNG text.
 *  2. [ContainerCopier] moves the raw blocks ExifTool can read but not write,
 *     above all C2PA / Content Credentials, which is precisely where AI
 *     generators record that an image is synthetic.
 *
 * A third pass verifies the result by re-reading both files and diffing them
 * tag by tag, so the app can state exactly how many tags made it across
 * instead of just claiming success.
 */
class TransplantEngine(private val exifTool: ExifTool) {

    /** Tags that legitimately differ between two files and are not failures. */
    private val ignored = setOf(
        "SourceFile", "ExifToolVersion", "FileName", "Directory", "FileSize",
        "FileModifyDate", "FileAccessDate", "FileInodeChangeDate", "FilePermissions",
        "FileType", "FileTypeExtension", "MIMEType", "ImageWidth", "ImageHeight",
        "ImageSize", "Megapixels", "EncodingProcess", "BitsPerSample",
        "ColorComponents", "YCbCrSubSampling", "JFIFVersion", "Duration",
        "AvgBitrate", "MediaDataSize", "MediaDataOffset", "TrackDuration",
        "MediaDuration", "MovieDataSize", "MovieDataOffset", "ThumbnailImage",
        "PreviewImage", "JpgFromRaw", "OtherImage", "DataSize", "ImageDataMD5",
        "ImageDataHash", "CurrentIPTCDigest", "ThumbnailLength", "ThumbnailOffset",
    )

    data class TagDiff(val tag: String, val sourceValue: String, val targetValue: String?)

    data class Report(
        val sourceTagCount: Int,
        val copiedCount: Int,
        val missing: List<TagDiff>,
        val ignoredCount: Int,
        val rawBlocksCopied: List<String>,
        val warnings: List<String>,
    ) {
        val complete: Boolean get() = missing.isEmpty()
        val coveragePercent: Int
            get() = if (sourceTagCount == 0) 100 else (copiedCount * 100) / sourceTagCount
    }

    enum class Mode { EVERYTHING, SELECTED, FILL_GAPS_ONLY }

    private val stages = listOf(
        Stage("inventory", "Reading source metadata"),
        Stage("backup", "Backing up target"),
        Stage("copy", "Copying tags with ExifTool"),
        Stage("blocks", "Copying C2PA and raw blocks"),
        Stage("verify", "Verifying every tag arrived"),
    )

    /**
     * @param tags only used when [mode] is [Mode.SELECTED]; entries look like
     *        "EXIF:Make" or "GPS:all".
     */
    fun transplant(
        source: File,
        target: File,
        mode: Mode = Mode.EVERYTHING,
        tags: List<String> = emptyList(),
        copyRawBlocks: Boolean = true,
    ): Flow<com.metaforge.core.OperationProgress> = progressFlow(
        title = "Transplanting metadata",
        stages = stages,
    ) {
        val srcTags = stage("inventory") { readTags(source) }
        update("inventory", 1f, "${srcTags.size} tags found")

        val backup = stage("backup") {
            File(target.parentFile, target.name + ".bak").also { target.copyTo(it, overwrite = true) }
        }

        val warnings = mutableListOf<String>()

        stage("copy") {
            val args = mutableListOf("-tagsFromFile", source.absolutePath)
            when (mode) {
                Mode.EVERYTHING -> {
                    args += "-all:all"
                    args += "-unsafe"          // includes tags ExifTool skips by default
                    args += "-icc_profile"     // the colour profile is metadata too
                }
                Mode.SELECTED -> tags.forEach { args += "-$it" }
                Mode.FILL_GAPS_ONLY -> {
                    args += "-all:all"
                    args += "-unsafe"
                    args += "-wm"; args += "cg" // create groups, never overwrite existing
                }
            }
            args += "-m"                      // ignore minor warnings instead of aborting
            args += "-overwrite_original"
            args += target.absolutePath

            val res = exifTool.execute(*args.toTypedArray())
            if (res.stderr.isNotBlank()) warnings += res.stderr.lines().filter { it.isNotBlank() }
            if (!res.ok) error("ExifTool copy failed: ${res.stderr}")
        }

        val blocks = if (copyRawBlocks && mode != Mode.SELECTED) {
            stage("blocks") {
                val r = ContainerCopier.copy(source, target)
                r.error?.let { warnings += "raw blocks: $it" }
                warnings += r.skipped.map { "raw blocks: $it" }
                r.copied
            }
        } else {
            skip("blocks", "not requested"); emptyList()
        }

        val report = stage("verify") {
            val dstTags = readTags(target)
            val missing = srcTags.entries
                .filter { (k, _) -> shortName(k) !in ignored }
                .filter { (k, v) -> dstTags[k]?.trim() != v.trim() }
                .map { (k, v) -> TagDiff(k, v, dstTags[k]) }
            val considered = srcTags.count { shortName(it.key) !in ignored }
            Report(
                sourceTagCount = considered,
                copiedCount = considered - missing.size,
                missing = missing,
                ignoredCount = srcTags.size - considered,
                rawBlocksCopied = blocks,
                warnings = warnings,
            )
        }

        if (report.complete) {
            backup.delete()
            update("verify", 1f, "all ${report.copiedCount} tags verified")
        } else {
            update(
                "verify", 1f,
                "${report.copiedCount}/${report.sourceTagCount} copied, " +
                    "${report.missing.size} could not be written",
            )
        }
        lastReport = report
    }

    /** The report from the most recent [transplant] run. */
    @Volatile
    var lastReport: Report? = null
        private set

    /** Every tag in the file, keyed "Group1:TagName". */
    fun readTags(file: File): Map<String, String> {
        val res = exifTool.execute(
            "-json", "-a", "-u", "-G1", "-s", "-charset", "utf8", "-n", file.absolutePath,
        )
        if (!res.ok || res.stdout.isBlank()) return emptyMap()
        return runCatching {
            val root = JSONArray(res.stdout)
            if (root.length() == 0) return emptyMap()
            val obj: JSONObject = root.getJSONObject(0)
            buildMap {
                obj.keys().forEach { key -> put(key, obj.get(key).toString()) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun shortName(key: String) = key.substringAfterLast(':')
}
