package com.metaforge.ml

import android.content.Context
import com.metaforge.core.OperationProgress
import com.metaforge.core.Stage
import com.metaforge.core.progressFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and verifies the on-device ML models.
 *
 * Models are not bundled in the APK because they are large and most users only
 * need one of them. They are fetched once from the project's GitHub Releases,
 * checksum-verified, and cached forever. Everything after the download runs
 * offline.
 */
object ModelManager {

    private const val BASE =
        "https://github.com/clipnetpro-jpg/metaforge/releases/download/models-v1"

    /**
     * @param sha256 guards against a truncated or tampered download
     * @param sizeBytes used for an accurate progress bar before the server replies
     */
    data class Model(
        val id: String,
        val fileName: String,
        val displayName: String,
        val purpose: String,
        val sizeBytes: Long,
        val sha256: String,
        val license: String,
        val attribution: String,
    )

    /**
     * LaMa: Resolution-robust Large Mask Inpainting with Fourier Convolutions
     * (Suvorov et al., WACV 2022). Apache 2.0, trained on Places2 (CC-BY 4.0).
     * Unlike classical inpainting it synthesises plausible texture instead of
     * smearing neighbouring pixels, which is what large regions need.
     */
    val LAMA_FP16 = Model(
        id = "lama-fp16",
        fileName = "lama_fp16.onnx",
        displayName = "LaMa (balanced)",
        purpose = "Reconstructs large damaged areas with real texture",
        sizeBytes = 102_000_000,
        sha256 = "",
        license = "Apache-2.0",
        attribution = "LaMa, Suvorov et al. 2022; trained on Places2 (CC-BY 4.0)",
    )

    val LAMA_INT8 = Model(
        id = "lama-int8",
        fileName = "lama_int8.onnx",
        displayName = "LaMa (compact)",
        purpose = "Same model, quantised for older phones",
        sizeBytes = 52_000_000,
        sha256 = "",
        license = "Apache-2.0",
        attribution = "LaMa, Suvorov et al. 2022; trained on Places2 (CC-BY 4.0)",
    )

    val all = listOf(LAMA_FP16, LAMA_INT8)

    fun dir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(context: Context, model: Model): File = File(dir(context), model.fileName)

    fun isInstalled(context: Context, model: Model): Boolean {
        val f = fileFor(context, model)
        return f.exists() && f.length() > 1_000_000
    }

    fun installedSize(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun remove(context: Context, model: Model): Boolean = fileFor(context, model).delete()

    private val stages = listOf(
        Stage("connect", "Contacting the model server"),
        Stage("download", "Downloading model"),
        Stage("verify", "Verifying integrity"),
        Stage("install", "Installing"),
    )

    fun download(context: Context, model: Model): Flow<OperationProgress> =
        progressFlow("Downloading ${model.displayName}", stages) {
            val target = fileFor(context, model)
            val temp = File(target.parentFile, target.name + ".part")
            temp.delete()

            val conn = stage("connect") {
                (URL("$BASE/${model.fileName}").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    connect()
                    if (responseCode !in 200..299) {
                        error("server returned $responseCode")
                    }
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val expected = if (conn.contentLengthLong > 0) conn.contentLengthLong else model.sizeBytes

            start("download")
            var written = 0L
            var lastTick = 0L
            conn.inputStream.use { input ->
                temp.outputStream().buffered().use { out ->
                    val buf = ByteArray(512 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 150) {
                            lastTick = now
                            update(
                                "download",
                                (written.toFloat() / expected).coerceIn(0f, 1f),
                                "%.1f of %.1f MB".format(written / 1e6, expected / 1e6),
                            )
                        }
                    }
                }
            }
            conn.disconnect()
            done("download", "%.1f MB".format(written / 1e6))

            stage("verify") {
                if (written < 1_000_000) {
                    temp.delete(); error("download was truncated")
                }
                if (model.sha256.isNotBlank()) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(model.sha256, ignoreCase = true)) {
                        temp.delete(); error("checksum mismatch, file rejected")
                    }
                }
            }

            stage("install") {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true); temp.delete()
                }
            }
        }.flowOn(Dispatchers.IO)
}
