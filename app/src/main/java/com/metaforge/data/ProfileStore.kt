package com.metaforge.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved metadata profiles.
 *
 * A profile is a named set of tag assignments captured from a real file, so a
 * user who has worked out the exact identity they want can put it on the next
 * hundred files with one tap instead of retyping it. Profiles are plain JSON in
 * app storage, which means they can be exported, read, and hand-edited rather
 * than locked inside a database the user cannot see.
 */
class ProfileStore(context: Context) {

    data class Entry(val tag: String, val value: String, val raw: Boolean = false)

    data class Profile(
        val id: String,
        val name: String,
        val note: String,
        val capturedFrom: String,
        val createdAt: Long,
        val entries: List<Entry>,
    ) {
        val tagCount: Int get() = entries.size
    }

    private val dir: File = File(context.filesDir, "profiles").apply { mkdirs() }

    fun list(): List<Profile> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { parse(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun save(profile: Profile): Profile {
        File(dir, "${profile.id}.json").writeText(serialise(profile))
        return profile
    }

    fun create(name: String, note: String, capturedFrom: String, entries: List<Entry>): Profile =
        save(
            Profile(
                id = "p" + System.currentTimeMillis(),
                name = name,
                note = note,
                capturedFrom = capturedFrom,
                createdAt = System.currentTimeMillis(),
                entries = entries,
            ),
        )

    fun delete(id: String): Boolean = File(dir, "$id.json").delete()

    fun rename(profile: Profile, name: String): Profile = save(profile.copy(name = name))

    /** The exact ExifTool assignments this profile would apply. */
    fun assignments(profile: Profile): List<String> =
        profile.entries.map { if (it.raw) "-${it.tag}#=${it.value}" else "-${it.tag}=${it.value}" }

    fun exportText(profile: Profile): String = serialise(profile)

    fun importText(text: String): Profile? = runCatching { save(parse(text)) }.getOrNull()

    private fun serialise(p: Profile): String {
        val entries = JSONArray()
        p.entries.forEach {
            entries.put(
                JSONObject()
                    .put("tag", it.tag)
                    .put("value", it.value)
                    .put("raw", it.raw),
            )
        }
        return JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("note", p.note)
            .put("capturedFrom", p.capturedFrom)
            .put("createdAt", p.createdAt)
            .put("entries", entries)
            .toString(2)
    }

    private fun parse(text: String): Profile {
        val o = JSONObject(text)
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            Entry(e.getString("tag"), e.optString("value"), e.optBoolean("raw", false))
        }
        return Profile(
            id = o.optString("id", "p" + System.currentTimeMillis()),
            name = o.optString("name", "Untitled profile"),
            note = o.optString("note"),
            capturedFrom = o.optString("capturedFrom"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            entries = entries,
        )
    }
}
