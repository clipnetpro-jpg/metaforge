package com.metaforge.ai

import android.content.Context
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * A second opinion from a detection service, when the user wants one.
 *
 * On-device analysis can only read what is in the file. A trained detector run
 * on someone's servers can be better at the cases where nothing declares itself,
 * so the option is here rather than left out for the sake of purity.
 *
 * Two rules hold it in place. Nothing is uploaded unless the user taps the
 * button for that specific picture, and the account used is theirs, so their
 * images are never routed through anything of ours.
 */
object OnlineCheck {

    private const val PREFS = "metaforge_online"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_USER = "user"
    private const val KEY_SECRET = "secret"
    private const val KEY_ENDPOINT = "endpoint"

    enum class Provider(val id: String, val label: String, val needsPair: Boolean) {
        SIGHTENGINE("sightengine", "Sightengine", true),
        CUSTOM("custom", "A service of your own", false),
    }

    data class Settings(
        val provider: Provider,
        val user: String,
        val secret: String,
        val endpoint: String,
    ) {
        val configured: Boolean
            get() = when (provider) {
                Provider.SIGHTENGINE -> user.isNotBlank() && secret.isNotBlank()
                Provider.CUSTOM -> endpoint.isNotBlank()
            }
    }

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            provider = Provider.entries
                .firstOrNull { it.id == p.getString(KEY_PROVIDER, Provider.SIGHTENGINE.id) }
                ?: Provider.SIGHTENGINE,
            user = p.getString(KEY_USER, "").orEmpty(),
            secret = p.getString(KEY_SECRET, "").orEmpty(),
            endpoint = p.getString(KEY_ENDPOINT, "").orEmpty(),
        )
    }

    fun save(context: Context, settings: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, settings.provider.id)
            .putString(KEY_USER, settings.user)
            .putString(KEY_SECRET, settings.secret)
            .putString(KEY_ENDPOINT, settings.endpoint)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    sealed interface Outcome {
        data class Scored(val score: Int, val detail: String, val provider: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Uploads one picture and reads back a score out of a hundred. */
    fun check(settings: Settings, file: File): Outcome {
        if (!settings.configured) return Outcome.Failed("no account is set up yet")
        return runCatching {
            when (settings.provider) {
                Provider.SIGHTENGINE -> sightengine(settings, file)
                Provider.CUSTOM -> custom(settings, file)
            }
        }.getOrElse { Outcome.Failed(it.message ?: "the service could not be reached") }
    }

    private fun sightengine(settings: Settings, file: File): Outcome {
        val url = URL("https://api.sightengine.com/1.0/check.json")
        val body = multipart(
            fields = mapOf(
                "models" to "genai",
                "api_user" to settings.user,
                "api_secret" to settings.secret,
            ),
            file = file,
        )
        val response = post(url, body.first, body.second) ?: return Outcome.Failed("no reply")
        val json = JSONObject(response)
        if (json.optString("status") != "success") {
            val message = json.optJSONObject("error")?.optString("message")
            return Outcome.Failed(message ?: "the service refused the request")
        }
        val ai = json.optJSONObject("type")?.optDouble("ai_generated", -1.0) ?: -1.0
        if (ai < 0) return Outcome.Failed("the reply did not contain a score")
        return Outcome.Scored(
            score = (ai * 100).toInt().coerceIn(0, 100),
            detail = "scored %.3f out of 1".format(ai),
            provider = "Sightengine",
        )
    }

    private fun custom(settings: Settings, file: File): Outcome {
        val url = URL(settings.endpoint)
        val fields = buildMap {
            if (settings.user.isNotBlank()) put("api_user", settings.user)
            if (settings.secret.isNotBlank()) put("api_secret", settings.secret)
        }
        val body = multipart(fields, file)
        val response = post(url, body.first, body.second) ?: return Outcome.Failed("no reply")
        val json = runCatching { JSONObject(response) }.getOrNull()
            ?: return Outcome.Failed("the reply was not readable")
        // Accept the shapes these services usually answer with.
        val score = sequenceOf(
            json.optJSONObject("type")?.optDouble("ai_generated", -1.0),
            json.optDouble("ai_generated", -1.0),
            json.optDouble("score", -1.0),
            json.optJSONObject("result")?.optDouble("score", -1.0),
        ).filterNotNull().firstOrNull { it >= 0 } ?: return Outcome.Failed(
            "the reply had no score this app recognises",
        )
        val normalised = if (score > 1.0) score / 100.0 else score
        return Outcome.Scored(
            score = (normalised * 100).toInt().coerceIn(0, 100),
            detail = "scored %.3f out of 1".format(normalised),
            provider = "your service",
        )
    }

    // ------------------------------------------------------------------- http

    private fun multipart(fields: Map<String, String>, file: File): Pair<String, ByteArray> {
        val boundary = "----metaforge" + System.nanoTime()
        val out = java.io.ByteArrayOutputStream()
        val writer = DataOutputStream(out)
        fields.forEach { (k, v) ->
            writer.writeBytes("--$boundary\r\n")
            writer.writeBytes("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
            writer.write(v.toByteArray(Charsets.UTF_8))
            writer.writeBytes("\r\n")
        }
        writer.writeBytes("--$boundary\r\n")
        writer.writeBytes("Content-Disposition: form-data; name=\"media\"; filename=\"${file.name}\"\r\n")
        writer.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
        file.inputStream().use { it.copyTo(writer) }
        writer.writeBytes("\r\n--$boundary--\r\n")
        writer.flush()
        return boundary to out.toByteArray()
    }

    private fun post(url: URL, boundary: String, body: ByteArray): String? {
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.bufferedReader()?.use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
