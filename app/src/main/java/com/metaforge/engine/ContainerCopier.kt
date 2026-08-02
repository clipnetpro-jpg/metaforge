package com.metaforge.engine

import java.io.File
import java.io.RandomAccessFile

/**
 * Copies metadata blocks that ExifTool can read but cannot write back.
 *
 * The important case is C2PA / Content Credentials, which live in JUMBF
 * containers: JPEG APP11 segments, PNG `caBX` chunks, ISO-BMFF `uuid` boxes.
 * ExifTool parses these but has no writer for them, so a plain
 * `-tagsFromFile -all:all` silently drops exactly the provenance data that
 * matters most for AI detection. This copier moves the raw bytes across.
 *
 * Everything here is byte-level and lossless: blocks are copied verbatim,
 * never re-encoded, and image/video payload is left untouched.
 */
object ContainerCopier {

    data class Block(val kind: String, val id: String, val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Block && kind == other.kind && id == other.id
        override fun hashCode() = 31 * kind.hashCode() + id.hashCode()
    }

    data class Result(val copied: List<String>, val skipped: List<String>, val error: String? = null)

    fun copy(source: File, target: File): Result = runCatching {
        when (sniff(source)) {
            Format.JPEG -> if (sniff(target) == Format.JPEG) copyJpeg(source, target)
                           else Result(emptyList(), listOf("format mismatch"))
            Format.PNG -> if (sniff(target) == Format.PNG) copyPng(source, target)
                          else Result(emptyList(), listOf("format mismatch"))
            Format.ISOBMFF -> if (sniff(target) == Format.ISOBMFF) copyIsoBmff(source, target)
                              else Result(emptyList(), listOf("format mismatch"))
            Format.UNKNOWN -> Result(emptyList(), listOf("container not supported for raw block copy"))
        }
    }.getOrElse { Result(emptyList(), emptyList(), it.message ?: "container copy failed") }

    private enum class Format { JPEG, PNG, ISOBMFF, UNKNOWN }

    private fun sniff(f: File): Format = RandomAccessFile(f, "r").use { raf ->
        val head = ByteArray(12)
        if (raf.length() < 12) return Format.UNKNOWN
        raf.readFully(head)
        when {
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> Format.JPEG
            head.copyOfRange(0, 8).contentEquals(PNG_SIG) -> Format.PNG
            String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Format.ISOBMFF
            else -> Format.UNKNOWN
        }
    }

    // ---------------------------------------------------------------- JPEG

    /** Markers we transplant when the target lacks them. APP11 carries JUMBF/C2PA. */
    private val JPEG_CARRY = setOf(0xE1, 0xE2, 0xEB, 0xED, 0xEE, 0xEF, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA)

    private fun copyJpeg(source: File, target: File): Result {
        val src = readJpegSegments(source)
        val dst = readJpegSegments(target)
        val present = dst.map { it.first to it.second }.toSet()

        val missing = src.filter { (marker, id, _) ->
            marker in JPEG_CARRY && (marker to id) !in present
        }
        if (missing.isEmpty()) return Result(emptyList(), listOf("no extra JPEG segments to carry"))

        val out = File(target.parentFile, target.name + ".mfx")
        out.outputStream().buffered().use { o ->
            o.write(0xFF); o.write(0xD8)
            // Keep marker order ascending so decoders stay happy.
            val merged = (dst + missing).sortedBy { it.first }
            merged.forEach { (marker, _, body) ->
                o.write(0xFF); o.write(marker)
                val len = body.size + 2
                o.write((len shr 8) and 0xFF); o.write(len and 0xFF)
                o.write(body)
            }
            // Everything from the first non-APP marker onward is the image itself.
            target.inputStream().use { input ->
                input.skip(jpegHeaderLength(target).toLong())
                input.copyTo(o)
            }
        }
        if (!out.renameTo(target)) {
            out.copyTo(target, overwrite = true); out.delete()
        }
        return Result(missing.map { "JPEG APP${it.first - 0xE0}:${it.second}" }, emptyList())
    }

    /** Returns (markerByte, identifier, body) for every APPn segment. */
    private fun readJpegSegments(f: File): List<Triple<Int, String, ByteArray>> {
        val out = mutableListOf<Triple<Int, String, ByteArray>>()
        f.inputStream().buffered().use { s ->
            if (s.read() != 0xFF || s.read() != 0xD8) return emptyList()
            while (true) {
                var b = s.read()
                if (b == -1) break
                if (b != 0xFF) continue
                while (b == 0xFF) b = s.read()
                if (b == -1) break
                val marker = b
                if (marker == 0xD9 || marker == 0xDA) break        // EOI or start of scan
                if (marker < 0xC0) continue
                val hi = s.read(); val lo = s.read()
                if (hi == -1 || lo == -1) break
                val len = ((hi shl 8) or lo) - 2
                if (len < 0) break
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = s.read(body, read, len - read)
                    if (n <= 0) break
                    read += n
                }
                if (marker in 0xE0..0xEF) {
                    val id = body.takeWhile { it != 0.toByte() }
                        .toByteArray().toString(Charsets.ISO_8859_1).take(32)
                    out += Triple(marker, id, body)
                }
            }
        }
        return out
    }

    private fun jpegHeaderLength(f: File): Int {
        var pos = 2
        f.inputStream().buffered().use { s ->
            s.skip(2)
            while (true) {
                var b = s.read()
                if (b == -1) return pos
                if (b != 0xFF) { pos++; continue }
                var marker = s.read()
                while (marker == 0xFF) marker = s.read()
                if (marker == -1) return pos
                if (marker !in 0xE0..0xEF) return pos
                val hi = s.read(); val lo = s.read()
                val len = (hi shl 8) or lo
                s.skip((len - 2).toLong())
                pos += 2 + len
            }
        }
    }

    // ----------------------------------------------------------------- PNG

    private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** caBX is C2PA; the text chunks hold generator prompts and parameters. */
    private val PNG_CARRY = setOf("caBX", "eXIf", "iTXt", "tEXt", "zTXt", "iCCP", "sRGB", "gAMA")

    private fun copyPng(source: File, target: File): Result {
        val src = readPngChunks(source).filter { it.first in PNG_CARRY }
        val dst = readPngChunks(target)
        val present = dst.map { it.first to it.second.size }.toSet()
        val missing = src.filter { (type, data) -> (type to data.size) !in present }
        if (missing.isEmpty()) return Result(emptyList(), listOf("no extra PNG chunks to carry"))

        val out = File(target.parentFile, target.name + ".mfx")
        out.outputStream().buffered().use { o ->
            o.write(PNG_SIG)
            var inserted = false
            dst.forEach { (type, data) ->
                if (type == "IDAT" && !inserted) {
                    missing.forEach { (t, d) -> writePngChunk(o, t, d) }
                    inserted = true
                }
                writePngChunk(o, type, data)
            }
            if (!inserted) missing.forEach { (t, d) -> writePngChunk(o, t, d) }
        }
        if (!out.renameTo(target)) { out.copyTo(target, overwrite = true); out.delete() }
        return Result(missing.map { "PNG ${it.first}" }, emptyList())
    }

    private fun readPngChunks(f: File): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        f.inputStream().buffered().use { s ->
            s.skip(8)
            while (true) {
                val lenB = ByteArray(4)
                if (s.read(lenB) != 4) break
                val len = ((lenB[0].toInt() and 0xFF) shl 24) or ((lenB[1].toInt() and 0xFF) shl 16) or
                          ((lenB[2].toInt() and 0xFF) shl 8) or (lenB[3].toInt() and 0xFF)
                val typeB = ByteArray(4)
                if (s.read(typeB) != 4) break
                val type = String(typeB, Charsets.US_ASCII)
                val data = ByteArray(len)
                var read = 0
                while (read < len) { val n = s.read(data, read, len - read); if (n <= 0) break; read += n }
                s.skip(4) // CRC
                out += type to data
                if (type == "IEND") break
            }
        }
        return out
    }

    private fun writePngChunk(o: java.io.OutputStream, type: String, data: ByteArray) {
        val len = data.size
        o.write((len ushr 24) and 0xFF); o.write((len ushr 16) and 0xFF)
        o.write((len ushr 8) and 0xFF); o.write(len and 0xFF)
        val typeB = type.toByteArray(Charsets.US_ASCII)
        o.write(typeB); o.write(data)
        val crc = java.util.zip.CRC32()
        crc.update(typeB); crc.update(data)
        val v = crc.value
        o.write(((v ushr 24) and 0xFF).toInt()); o.write(((v ushr 16) and 0xFF).toInt())
        o.write(((v ushr 8) and 0xFF).toInt()); o.write((v and 0xFF).toInt())
    }

    // ------------------------------------------------------------- ISO-BMFF

    /**
     * MP4 / MOV. Top level `uuid` boxes (where C2PA lives) are appended at the
     * end of the file, which is legal for ISO-BMFF and needs no chunk-offset
     * rewriting, so the media payload is bit-identical afterwards.
     */
    private fun copyIsoBmff(source: File, target: File): Result {
        val srcBoxes = readTopLevelBoxes(source).filter { it.first == "uuid" || it.first == "meta" }
        if (srcBoxes.isEmpty()) return Result(emptyList(), listOf("no uuid/meta boxes in source"))
        val dstTypes = readTopLevelBoxes(target).map { it.first to it.third }.toSet()
        val missing = srcBoxes.filter { (t, _, size) -> (t to size) !in dstTypes }
        if (missing.isEmpty()) return Result(emptyList(), listOf("no extra boxes to carry"))

        RandomAccessFile(source, "r").use { input ->
            java.io.FileOutputStream(target, true).use { o ->
                missing.forEach { (_, offset, size) ->
                    input.seek(offset)
                    val buf = ByteArray(minOf(size, 8 * 1024 * 1024).toInt())
                    var left = size
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                        if (n <= 0) break
                        o.write(buf, 0, n); left -= n
                    }
                }
            }
        }
        return Result(missing.map { "MP4 ${it.first} (${it.third} bytes)" }, emptyList())
    }

    /** (type, fileOffset, totalSize) for every top-level box. */
    private fun readTopLevelBoxes(f: File): List<Triple<String, Long, Long>> {
        val out = mutableListOf<Triple<String, Long, Long>>()
        RandomAccessFile(f, "r").use { raf ->
            var pos = 0L
            val len = raf.length()
            while (pos + 8 <= len) {
                raf.seek(pos)
                var size = (raf.readInt().toLong() and 0xFFFFFFFFL)
                val typeB = ByteArray(4); raf.readFully(typeB)
                val type = String(typeB, Charsets.ISO_8859_1)
                var header = 8L
                if (size == 1L) { size = raf.readLong(); header = 16L }
                else if (size == 0L) size = len - pos
                if (size < header || pos + size > len) break
                out += Triple(type, pos, size)
                pos += size
            }
        }
        return out
    }

    // ============================================================== STRIPPING

    data class StripResult(val removed: List<String>, val note: String? = null)

    /**
     * Removes metadata blocks ExifTool cannot delete, so a privacy wipe is
     * actually complete. C2PA/JUMBF is the main one: ExifTool parses it but has
     * no writer, so `-all=` leaves it sitting in the file.
     */
    fun strip(file: File): StripResult = runCatching {
        when (sniff(file)) {
            Format.JPEG -> stripJpeg(file)
            Format.PNG -> stripPng(file)
            Format.ISOBMFF -> stripIsoBmff(file)
            Format.UNKNOWN -> StripResult(emptyList(), "container not supported for raw strip")
        }
    }.getOrElse { StripResult(emptyList(), it.message ?: "raw strip failed") }

    private fun stripJpeg(file: File): StripResult {
        val segments = readJpegSegments(file)
        // APP0 is JFIF density info: no privacy content, and some decoders like it.
        val doomed = segments.filter { it.first != 0xE0 }
        if (doomed.isEmpty()) return StripResult(emptyList(), "no APP segments left")

        val keep = segments.filter { it.first == 0xE0 }
        val out = File(file.parentFile, file.name + ".mfs")
        out.outputStream().buffered().use { o ->
            o.write(0xFF); o.write(0xD8)
            keep.forEach { (marker, _, body) ->
                o.write(0xFF); o.write(marker)
                val len = body.size + 2
                o.write((len shr 8) and 0xFF); o.write(len and 0xFF)
                o.write(body)
            }
            file.inputStream().use { input ->
                input.skip(jpegHeaderLength(file).toLong())
                input.copyTo(o)
            }
        }
        if (!out.renameTo(file)) { out.copyTo(file, overwrite = true); out.delete() }
        return StripResult(doomed.map { "JPEG APP${it.first - 0xE0}:${it.second}" })
    }

    private val PNG_PRIVACY = setOf("caBX", "eXIf", "iTXt", "tEXt", "zTXt", "tIME", "pHYs")

    private fun stripPng(file: File): StripResult {
        val chunks = readPngChunks(file)
        val doomed = chunks.filter { it.first in PNG_PRIVACY }
        if (doomed.isEmpty()) return StripResult(emptyList(), "no metadata chunks present")

        val out = File(file.parentFile, file.name + ".mfs")
        out.outputStream().buffered().use { o ->
            o.write(PNG_SIG)
            chunks.filter { it.first !in PNG_PRIVACY }.forEach { (t, d) -> writePngChunk(o, t, d) }
        }
        if (!out.renameTo(file)) { out.copyTo(file, overwrite = true); out.delete() }
        return StripResult(doomed.map { "PNG ${it.first}" })
    }

    /**
     * ISO-BMFF: rather than removing a `uuid` box and shifting every chunk
     * offset in the file, the box is retyped to `free` and its payload zeroed.
     * Same byte length, so sample offsets stay valid and the media is untouched,
     * but the metadata is gone.
     */
    private fun stripIsoBmff(file: File): StripResult {
        val boxes = readTopLevelBoxes(file).filter { it.first == "uuid" }
        if (boxes.isEmpty()) return StripResult(emptyList(), "no uuid boxes present")
        RandomAccessFile(file, "rw").use { raf ->
            boxes.forEach { (_, offset, size) ->
                raf.seek(offset)
                val bigHeader = (raf.readInt().toLong() and 0xFFFFFFFFL) == 1L
                val headerLen = if (bigHeader) 16L else 8L
                raf.seek(offset + 4)
                raf.write("free".toByteArray(Charsets.ISO_8859_1))
                raf.seek(offset + headerLen)
                var left = size - headerLen
                val zeros = ByteArray(64 * 1024)
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    raf.write(zeros, 0, n); left -= n
                }
            }
        }
        return StripResult(boxes.map { "MP4 uuid (${it.third} bytes)" })
    }
}
