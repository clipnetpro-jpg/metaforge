package com.metaforge.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaforge.data.MediaAccess
import com.metaforge.ui.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Every tag in the file, grouped the way ExifTool groups them, with an editor
 * behind each row.
 *
 * Edits are applied to a private working copy immediately so the user can see
 * the real result of the write, and only pushed back over the original when
 * they choose to save. Nothing is written to the gallery behind their back.
 */
@Composable
fun InspectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = remember { Engine.media(context) }

    var staged by remember { mutableStateOf<MediaAccess.Staged?>(null) }
    var tags by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }

    suspend fun reload() {
        val s = staged ?: return
        tags = withContext(Dispatchers.IO) { readTags(context, s) }
    }

    fun load(uri: Uri) {
        busy = true
        scope.launch {
            runCatching {
                val s = withContext(Dispatchers.IO) { media.stage(uri) }
                staged = s
                dirty = false
                reload()
            }.onFailure { message = it.message ?: "could not open that file" }
            busy = false
        }
    }

    val pick = rememberFilePicker(IMAGE_AND_VIDEO) { load(it) }

    fun write(tag: String, value: String?) {
        val s = staged ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val et = Engine.exifTool(context) ?: return@withContext "engine unavailable"
                val arg = if (value == null) "-$tag=" else "-$tag=$value"
                val r = et.execute(arg, "-m", "-overwrite_original", s.workingCopy.absolutePath)
                if (r.stderr.isNotBlank() && r.stdout.contains("0 image files updated")) r.stderr
                else null
            }
            message = result ?: if (value == null) "$tag deleted" else "$tag updated"
            dirty = dirty || result == null
            reload()
            busy = false
        }
    }

    fun save() {
        val s = staged ?: return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.commit(s) }
            message = if (r.isSuccess) "saved back to the original file"
                      else "could not save: ${r.exceptionOrNull()?.message}"
            if (r.isSuccess) dirty = false
            busy = false
        }
    }

    val filtered = remember(tags, query) {
        if (query.isBlank()) tags
        else tags.filter { (k, v) ->
            k.contains(query, true) || v.contains(query, true)
        }
    }

    ScreenScaffold(
        title = "Inspect and edit",
        subtitle = staged?.displayName ?: "no file chosen",
        onBack = onBack,
        actions = {
            if (dirty) {
                IconButton(onClick = { save() }, enabled = !busy) {
                    Icon(Icons.Rounded.Save, contentDescription = "Save", tint = Accent)
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            FileSlot(
                label = "File",
                fileName = staged?.displayName,
                detail = staged?.let { "${humanSize(it.sizeBytes)}  ${tags.size} tags" },
                enabled = !busy,
                onPick = pick,
            )

            if (busy) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.08f),
                )
            }

            message?.let {
                Spacer(Modifier.height(10.dp))
                InfoCard("Engine", it, if (it.contains("could not")) Bad else Good)
            }

            if (staged != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search tags") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Accent,
                        unfocusedLabelColor = Muted,
                        cursorColor = Accent,
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.first }) { (key, value) ->
                    TagRow(
                        tag = key,
                        value = value,
                        onEdit = { editing = key to value },
                        onDelete = { write(key, null) },
                    )
                }
                if (staged != null && filtered.isEmpty() && !busy) {
                    item {
                        Spacer(Modifier.height(24.dp))
                        InfoCard(
                            "Nothing to show",
                            if (tags.isEmpty()) "This file carries no metadata at all."
                            else "No tag matches \"$query\".",
                        )
                    }
                }
            }
        }
    }

    editing?.let { (key, value) ->
        EditDialog(
            tag = key,
            initial = value,
            onDismiss = { editing = null },
            onSave = { newValue ->
                editing = null
                write(key, newValue)
            },
        )
    }
}

@Composable
private fun TagRow(tag: String, value: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onEdit,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tag,
                    fontSize = 11.sp,
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    value,
                    fontSize = 14.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    maxLines = 4,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete tag", tint = Muted)
            }
        }
    }
}

@Composable
private fun EditDialog(
    tag: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(tag) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(tag, fontSize = 15.sp, color = Accent, fontFamily = FontFamily.Monospace) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Accent,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Write", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        },
    )
}

private fun readTags(
    context: android.content.Context,
    staged: MediaAccess.Staged,
): List<Pair<String, String>> {
    val et = Engine.exifTool(context) ?: return emptyList()
    val res = et.execute(
        "-json", "-a", "-u", "-G1", "-s", "-struct", "-charset", "utf8",
        staged.workingCopy.absolutePath,
    )
    if (!res.ok || res.stdout.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(res.stdout)
        if (arr.length() == 0) return emptyList()
        val obj = arr.getJSONObject(0)
        buildList {
            obj.keys().forEach { k ->
                if (k != "SourceFile") add(k to obj.get(k).toString())
            }
        }.sortedBy { it.first }
    }.getOrDefault(emptyList())
}
