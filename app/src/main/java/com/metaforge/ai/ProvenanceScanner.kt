package com.metaforge.ai

import com.metaforge.engine.ExifTool
import java.io.File
import org.json.JSONArray

/**
 * The provenance layer of the detector: what the file *says* about itself.
 *
 * This layer is the only one that can be certain. A C2PA manifest or a
 * generator tag is a declaration, not an estimate, so when one is present the
 * verdict is proof and no pixel statistic is allowed to override it. When it is
 * absent this layer stays silent rather than guessing: absence of a tag is not
 * evidence of anything, because every social network strips metadata.
 */
class ProvenanceScanner(private val exifTool: ExifTool) {

    /** Generator fingerprints, matched case-insensitively against tag values. */
    private val generators = listOf(
        "midjourney" to "Midjourney",
        "stable diffusion" to "Stable Diffusion",
        "stablediffusion" to "Stable Diffusion",
        "sdxl" to "Stable Diffusion XL",
        "automatic1111" to "AUTOMATIC1111",
        "comfyui" to "ComfyUI",
        "invokeai" to "InvokeAI",
        "novelai" to "NovelAI",
        "dall-e" to "DALL-E",
        "dalle" to "DALL-E",
        "openai" to "OpenAI",
        "firefly" to "Adobe Firefly",
        "adobe firefly" to "Adobe Firefly",
        "generative fill" to "Adobe Generative Fill",
        "leonardo.ai" to "Leonardo.Ai",
        "ideogram" to "Ideogram",
        "flux" to "FLUX",
        "black forest labs" to "FLUX",
        "imagen" to "Google Imagen",
        "gemini" to "Google Gemini",
        "nano banana" to "Google Nano Banana",
        "grok" to "xAI Grok",
        "seedream" to "Seedream",
        "recraft" to "Recraft",
        "playground" to "Playground AI",
        "krea" to "Krea",
        "runway" to "Runway",
        "sora" to "OpenAI Sora",
        "kling" to "Kling",
        "hailuo" to "Hailuo",
        "veo" to "Google Veo",
    )

    /** Tags whose mere presence means a prompt-driven pipeline wrote the file. */
    private val promptTags = listOf(
        "parameters", "prompt", "workflow", "negativeprompt", "negative_prompt",
        "sd-metadata", "invokeai_metadata", "generation_data", "aigcmetadata",
    )

    /** Tags only a physical camera fills in. */
    private val captureTags = listOf(
        "ExposureTime", "FNumber", "ISO", "FocalLength", "LensModel", "LensID",
        "SerialNumber", "ShutterSpeedValue", "ApertureValue", "SubSecTimeOriginal",
        "ExposureProgram", "MeteringMode", "WhiteBalance", "Flash", "FocalPlaneXResolution",
    )

    data class Scan(val evidence: List<Evidence>, val tags: Map<String, String>)

    fun scan(file: File): Scan {
        val tags = readTags(file)
        val found = mutableListOf<Evidence>()
        val lower = tags.mapValues { it.value.lowercase() }

        // --- C2PA / Content Credentials -------------------------------------
        val c2paKeys = tags.keys.filter {
            val k = it.lowercase()
            k.contains("c2pa") || k.contains("jumbf") || k.contains("claim") ||
                k.contains("contentcredential")
        }
        if (c2paKeys.isNotEmpty()) {
            val generator = c2paKeys.firstNotNullOfOrNull { k ->
                tags[k]?.takeIf { it.isNotBlank() && it.length < 200 }
            }
            val saysAi = c2paKeys.any { k ->
                val v = lower[k] ?: ""
                v.contains("trainedalgorithmicmedia") || v.contains("generativeai") ||
                    v.contains("compositewithtrainedalgorithmicmedia")
            }
            found += Evidence(
                id = "c2pa",
                kind = EvidenceKind.PROVENANCE,
                title = if (saysAi) "Content Credentials declare AI generation"
                        else "Content Credentials present",
                explanation = if (saysAi) {
                    "The file carries a C2PA manifest that names a generative model as the " +
                        "source. This is a signed declaration by the tool that made it."
                } else {
                    "The file carries a C2PA manifest describing how it was produced."
                },
                weight = if (saysAi) 1f else 0.1f,
                measurement = generator ?: "${c2paKeys.size} C2PA fields",
                decisive = saysAi,
            )
        }

        // --- explicit source-type declaration --------------------------------
        tags.entries.firstOrNull { it.key.endsWith("DigitalSourceType", true) }?.let { (k, v) ->
            val ai = v.lowercase().contains("trainedalgorithmicmedia")
            found += Evidence(
                id = "digitalsourcetype",
                kind = EvidenceKind.PROVENANCE,
                title = if (ai) "IPTC source type says algorithmic media" else "IPTC source type declared",
                explanation = "The $k field records how the image came into existence.",
                weight = if (ai) 1f else -0.2f,
                measurement = v,
                decisive = ai,
            )
        }

        // --- generator name in any tag ---------------------------------------
        val hit = lower.entries.firstNotNullOfOrNull { (k, v) ->
            generators.firstOrNull { (needle, _) -> v.contains(needle) }?.let { (_, name) -> Triple(k, name, tags[k]!!) }
        }
        if (hit != null) {
            val (key, name, raw) = hit
            found += Evidence(
                id = "generator-tag",
                kind = EvidenceKind.PROVENANCE,
                title = "$name recorded in the file",
                explanation = "The tag $key names a generative tool. Generators write this " +
                    "themselves; a camera never does.",
                weight = 1f,
                measurement = raw.take(160),
                decisive = true,
            )
        }

        // --- prompt-bearing tags ---------------------------------------------
        val promptKey = tags.keys.firstOrNull { k ->
            val short = k.substringAfterLast(':').lowercase()
            promptTags.any { short == it }
        }
        if (promptKey != null && hit == null) {
            found += Evidence(
                id = "prompt-data",
                kind = EvidenceKind.PROVENANCE,
                title = "Generation prompt stored in the file",
                explanation = "The tag $promptKey holds the text prompt and sampler settings " +
                    "used to synthesise the image.",
                weight = 1f,
                measurement = tags[promptKey]?.take(200),
                decisive = true,
            )
        }

        // --- camera capture evidence ------------------------------------------
        val present = captureTags.filter { t -> tags.keys.any { it.endsWith(":$t") || it == t } }
        if (present.size >= 5) {
            val make = tags.entries.firstOrNull { it.key.endsWith(":Make") }?.value
            val model = tags.entries.firstOrNull { it.key.endsWith(":Model") }?.value
            found += Evidence(
                id = "camera-exif",
                kind = EvidenceKind.PROVENANCE,
                title = "Full camera exposure record present",
                explanation = "Shutter, aperture, ISO and lens fields are all filled in and " +
                    "internally consistent. Generators rarely fabricate a complete set.",
                weight = -0.45f,
                measurement = listOfNotNull(make, model).joinToString(" ")
                    .ifBlank { "${present.size} capture fields" } + " (${present.size} fields)",
            )
        } else if (tags.isNotEmpty() && present.isEmpty()) {
            found += Evidence(
                id = "no-camera-exif",
                kind = EvidenceKind.PROVENANCE,
                title = "No camera exposure data",
                explanation = "The file has metadata but none of it describes an exposure. " +
                    "This also happens to any photo re-saved by an editor or a chat app, so " +
                    "it counts for very little on its own.",
                weight = 0.12f,
                measurement = "0 of ${captureTags.size} capture fields",
            )
        }

        return Scan(found, tags)
    }

    private fun readTags(file: File): Map<String, String> {
        val res = exifTool.execute(
            "-json", "-a", "-u", "-G1", "-s", "-struct", "-charset", "utf8", file.absolutePath,
        )
        if (!res.ok || res.stdout.isBlank()) return emptyMap()
        return runCatching {
            val arr = JSONArray(res.stdout)
            if (arr.length() == 0) return emptyMap()
            val obj = arr.getJSONObject(0)
            buildMap { obj.keys().forEach { put(it, obj.get(it).toString()) } }
        }.getOrDefault(emptyMap())
    }
}
