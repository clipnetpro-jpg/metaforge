package com.metaforge.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaforge.data.MediaAccess
import com.metaforge.data.ProfileStore
import com.metaforge.engine.MetadataRepository
import com.metaforge.ui.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saved identities, ready to reuse.
 *
 * Applying one is still a decision, not a reflex: the sheet shows every tag it
 * will write and what the file holds right now, and each line can be dropped or
 * changed before anything happens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ProfileStore(context) }
    val media = remember { Engine.media(context) }

    var profiles by remember { mutableStateOf(store.list()) }
    var applying by remember { mutableStateOf<ProfileStore.Profile?>(null) }
    var confirmDelete by remember { mutableStateOf<ProfileStore.Profile?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }

    var pending by remember { mutableStateOf<ProfileStore.Profile?>(null) }
    var staged by remember { mutableStateOf<MediaAccess.Staged?>(null) }
    var currentDoc by remember { mutableStateOf<MetadataRepository.Document?>(null) }

    fun repo(): MetadataRepository? = Engine.exifTool(context)?.let { MetadataRepository(it) }

    val pick = rememberFilePicker(IMAGE_AND_VIDEO) { uri: Uri ->
        val profile = pending ?: return@rememberFilePicker
        busy = true; busyLabel = "Reading " + profile.name + " against your file"
        scope.launch {
            runCatching {
                val s = withContext(Dispatchers.IO) { media.stage(uri) }
                val r = repo()
                staged = s
                currentDoc = if (r == null) null else withContext(Dispatchers.IO) { r.read(s.workingCopy) }
                applying = profile
            }.onFailure { toast = it.message ?: "that file could not be opened"; failed = true }
            busy = false
        }
    }

    ScreenScaffold(
        title = "Profiles",
        subtitle = if (profiles.isEmpty()) "none saved yet" else "${profiles.size} saved",
        onBack = onBack,
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            WorkingBar(busy, busyLabel)

            toast?.let {
                Spacer(Modifier.height(10.dp))
                InfoCard(if (failed) "Not done" else "Done", it, if (failed) Bad else Good)
            }

            if (profiles.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                InfoCard(
                    "No profiles yet",
                    "Open a file in Metadata, choose the tags worth keeping and save them as a " +
                        "profile. A transplant can be kept the same way. Anything saved here can " +
                        "then be put on another file in one tap.",
                )
            }

            LazyColumn(Modifier.weight(1f)) {
                items(profiles, key = { it.id }) { p ->
                    ProfileCard(
                        profile = p,
                        onApply = { pending = p; pick() },
                        onDelete = { confirmDelete = p },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    applying?.let { profile ->
        ApplyProfileSheet(
            profile = profile,
            fileName = staged?.displayName ?: "",
            current = currentDoc,
            onDismiss = { applying = null },
            onApply = { entries ->
                applying = null
                val s = staged
                val r = repo()
                if (s == null || r == null) {
                    toast = "the engine is still starting"; failed = true
                } else {
                    busy = true; busyLabel = "Writing ${entries.size} tags"
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            val args = entries.map {
                                if (it.raw) "-${it.tag}#=${it.value}" else "-${it.tag}=${it.value}"
                            }
                            val res = r.apply(s.workingCopy, args, "${entries.size} tags written")
                            val commit = if (res is MetadataRepository.WriteResult.Ok) media.commit(s) else null
                            res to commit
                        }
                        val (res, commit) = outcome
                        when {
                            res is MetadataRepository.WriteResult.Failed -> {
                                toast = res.reason; failed = true
                            }
                            commit?.isFailure == true -> {
                                toast = "written, but saving into the file failed"; failed = true
                            }
                            else -> {
                                toast = "${entries.size} tags written into ${s.displayName}"
                                failed = false
                            }
                        }
                        busy = false
                    }
                }
            },
        )
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Panel,
            title = { Text("Delete \"${p.name}\"?", color = Color.White) },
            text = {
                Text(
                    "The ${p.tagCount} tags saved in it are removed. Files you already applied " +
                        "it to are untouched.",
                    color = Muted,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(p.id)
                    profiles = store.list()
                    confirmDelete = null
                    toast = "\"${p.name}\" deleted"
                    failed = false
                }) { Text("Delete", color = Bad) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Keep", color = Muted) }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileStore.Profile,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onApply() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    listOfNotNull(
                        "${profile.tagCount} tags",
                        profile.capturedFrom.takeIf { it.isNotBlank() }?.let { "from $it" },
                        profile.note.takeIf { it.isNotBlank() },
                    ).joinToString("  "),
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, "Delete", tint = Muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            profile.entries.take(4).joinToString("  ") { it.tag.substringAfter(':') },
            color = Accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplyProfileSheet(
    profile: ProfileStore.Profile,
    fileName: String,
    current: MetadataRepository.Document?,
    onDismiss: () -> Unit,
    onApply: (List<ProfileStore.Entry>) -> Unit,
) {
    var chosen by remember(profile) { mutableStateOf(profile.entries.map { it.tag }.toSet()) }
    var overrides by remember(profile) { mutableStateOf(mapOf<String, String>()) }
    var editing by remember { mutableStateOf<ProfileStore.Entry?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(Modifier.padding(horizontal = 20.dp).fillMaxHeight(0.92f)) {
            Text(profile.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "About to write into $fileName. Nothing happens until you approve it.",
                color = Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text("${chosen.size} of ${profile.entries.size} tags approved", color = Accent, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))

            LazyColumn(Modifier.weight(1f)) {
                items(profile.entries, key = { it.tag }) { entry ->
                    val now = current?.find(entry.tag)?.printValue
                    val value = overrides[entry.tag] ?: entry.value
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = if (entry.tag in chosen) 0.05f else 0.02f))
                            .padding(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = entry.tag in chosen,
                                onCheckedChange = {
                                    chosen = if (entry.tag in chosen) chosen - entry.tag else chosen + entry.tag
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = Ink),
                            )
                            Text(
                                entry.tag,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { editing = entry }) {
                                Text("Edit", color = if (overrides.containsKey(entry.tag)) Accent else Muted, fontSize = 12.sp)
                            }
                        }
                        if (now != null) {
                            Text("now  $now", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                        }
                        Text(
                            "write $value",
                            color = if (overrides.containsKey(entry.tag)) Accent else Good,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    onApply(
                        profile.entries.filter { it.tag in chosen }
                            .map { it.copy(value = overrides[it.tag] ?: it.value, raw = it.raw && !overrides.containsKey(it.tag)) },
                    )
                },
                enabled = chosen.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Write ${chosen.size} tags and save", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(20.dp))
        }
    }

    editing?.let { entry ->
        var text by remember(entry) { mutableStateOf(overrides[entry.tag] ?: entry.value) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Panel,
            title = { Text(entry.tag, color = Accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Value to write") },
                        colors = fieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    overrides = overrides + (entry.tag to text)
                    chosen = chosen + entry.tag
                    editing = null
                }) { Text("Use this", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel", color = Muted) }
            },
        )
    }
}
