package com.metaforge.engine

import com.metaforge.core.OperationProgress
import com.metaforge.core.Stage
import com.metaforge.core.progressFlow
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Removes every trace of metadata from a file before you share it.
 *
 * ExifTool's `-all=` handles EXIF, IPTC, XMP, maker notes and friends, but it
 * cannot delete C2PA/JUMBF blocks because it has no writer for them. Left alone
 * those blocks still carry device, software and authorship information, so a
 * second byte-level pass from [ContainerCopier.strip] finishes the job.
 *
 * The result is then re-read and reported tag by tag, so the app can tell you
 * what is actually left in the file rather than just claiming it is clean.
 */
class PrivacyStripper(private val exifTool: ExifTool) {

    /** Things that are part of the file itself, not metadata about you. */
    private val structural = setOf(
        "SourceFile", "ExifToolVersion", "FileName", "Directory", "FileSize",
        "FileModifyDate", "FileAccessDate", "FileInodeChangeDate", "FilePermissions",
        "FileType", "FileTypeExtension", "MIMEType", "ImageWidth", "ImageHeight",
        "ImageSize", "Megapixels", "EncodingProcess", "BitsPerSample",
        "ColorComponents", "YCbCrSubSampling", "JFIFVersion", "ExifByteOrder",
        "Duration", "AvgBitrate", "MajorBrand", "MinorVersion", "CompatibleBrands",
        "MediaDataSize", "MediaDataOffset", "MovieDataSize", "MovieDataOffset",
        "VideoFrameRate", "ImageDataHash", "ColorType", "BitDepth", "Compression",
        "Filter", "Interlace", "SamplesPerPixel", "AudioChannels", "AudioSampleRate",
        "HandlerType", "TrackID", "SourceImageWidth", "SourceImageHeight",
    )

    data class Options(
        /** Keep EXIF:Orientation so the photo does not display sideways. */
        val keepOrientation: Boolean = true,
        /** Keep the ICC colour profile so colours do not shift. */
        val keepColorProfile: Boolean = true,
        /** Keep the capture date but drop everything else. */
        val keepCaptureDate: Boolean = false,
    )

    data class Report(
        val removedTagCount: Int,
        val remaining: List<String>,
        val rawBlocksRemoved: List<String>,
        val keptDeliberately: List<String>,
        val bytesBefore: Long,
        val bytesAfter: Long,
    ) {
        val clean: Boolean get() = remaining.isEmpty()
        val bytesSaved: Long get() = (bytesBefore - bytesAfter).coerceAtLeast(0)
    }

    private val stages = listOf(
        Stage("scan", "Scanning what the file reveals"),
        Stage("wipe", "Erasing EXIF, XMP, IPTC and maker notes"),
        Stage("blocks", "Erasing C2PA and hidden blocks"),
        Stage("verify", "Verifying nothing is left"),
    )

    @Volatile
    var lastReport: Report? = null
        private set

    fun strip(file: File, options: Options = Options()): Flow<OperationProgress> = progressFlow(
        title = "Removing metadata",
        stages = stages,
    ) {
        val before = file.length()

        val original = stage("scan") { readTags(file) }
        val sensitive = original.keys.filter { shortName(it) !in structural }
        update("scan", 1f, "${sensitive.size} identifying tags found")

        val kept = mutableListOf<String>()
        // Group prefixes whose tags were deliberately restored after the wipe.
        val keptGroups = mutableSetOf<String>()

        stage("wipe") {
            val args = mutableListOf("-all=")
            val restore = mutableListOf<String>()
            if (options.keepOrientation) restore += "-Orientation"
            if (options.keepColorProfile) {
                restore += "-ICC_Profile:all"
                // ExifTool reports a colour profile under several family-1
                // groups (ICC-header, ICC_Profile, ICC-view, ICC-meas...).
                // Matching only the literal argument meant every one of them
                // was counted as "could not be removed", so a perfectly clean
                // photo reported failure. Almost every phone photo has an ICC
                // profile, so that was nearly every run.
                keptGroups += "ICC"
            }
            if (options.keepCaptureDate) restore += "-DateTimeOriginal"
            if (restore.isNotEmpty()) {
                args += "-tagsFromFile"; args += "@"
                args += restore
                kept += restore.map { it.removePrefix("-") }
            }
            args += "-m"
            args += "-overwrite_original"
            args += file.absolutePath
            val res = exifTool.execute(*args.toTypedArray())
            if (!res.ok) error("wipe failed: ${res.stderr}")
        }

        val blocks = stage("blocks") {
            val r = ContainerCopier.strip(file)
            update("blocks", 1f, r.note ?: "${r.removed.size} raw blocks removed")
            r.removed
        }

        stage("verify") {
            val after = readTags(file)
            val keptTagNames = kept
                .map { it.substringAfterLast(':') }
                .filter { it != "all" }
                .toSet()
            val remaining = after.keys
                .filter { shortName(it) !in structural }
                .filter { shortName(it) !in keptTagNames }
                .filter { key -> keptGroups.none { g -> key.substringBefore(':').startsWith(g) } }
            lastReport = Report(
                removedTagCount = sensitive.size - remaining.size,
                remaining = remaining,
                rawBlocksRemoved = blocks,
                keptDeliberately = kept,
                bytesBefore = before,
                bytesAfter = file.length(),
            )
            update(
                "verify", 1f,
                if (remaining.isEmpty()) "clean: 0 identifying tags left"
                else "${remaining.size} tags could not be removed",
            )
        }
    }

    private fun readTags(file: File): Map<String, String> {
        val res = exifTool.execute("-json", "-a", "-u", "-G1", "-s", "-charset", "utf8", file.absolutePath)
        if (!res.ok || res.stdout.isBlank()) return emptyMap()
        return runCatching {
            val arr = org.json.JSONArray(res.stdout)
            if (arr.length() == 0) return emptyMap()
            val obj = arr.getJSONObject(0)
            buildMap { obj.keys().forEach { put(it, obj.get(it).toString()) } }
        }.getOrDefault(emptyMap())
    }

    private fun shortName(key: String) = key.substringAfterLast(':')
}
