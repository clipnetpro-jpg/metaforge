package com.metaforge.ui.screens

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Terminal
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
import com.metaforge.ui.components.HexView
import com.metaforge.ui.components.TagGroupSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The full record of a file, and the ability to change any part of it.
 *
 * Not a curated list of popular fields: every tag the container holds, grouped
 * the way the format nests them, with the stored value beside the readable one
 * and the raw bytes behind anything binary. Editing is not restricted to the
 * tags this app happens to know about, because the point of shipping the real
 * ExifTool is that the user is not limited to our imagination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = remember { Engine.media(context) }
    val profiles = remember { ProfileStore(context) }

    var staged by remember { mutableStateOf<MediaAccess.Staged?>(null) }
    var doc by remember { mutableStateOf<MetadataRepository.Document?>(null) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    var detail by remember { mutableStateOf<MetadataRepository.Tag?>(null) }
    var adding by remember { mutableStateOf(false) }
    var console by remember { mutableStateOf(false) }
    var savingProfile by remember { mutableStateOf(false) }

    fun repo(): MetadataRepository? = Engine.exifTool(context)?.let { MetadataRepository(it) }

    val writableGroups = remember {
        Engine.exifTool(context)?.let { MetadataRepository(it).writableGroups() } ?: emptyList()
    }

    suspend fun reload() {
        val s = staged ?: return
        val r = repo() ?: return
        doc = withContext(Dispatchers.IO) { r.read(s.workingCopy) }
        if (expanded.isEmpty()) {
            expanded = doc?.groups?.take(2)?.map { it.name }?.toSet() ?: emptySet()
        }
    }

    fun open(uri: Uri) {
        busy = true; busyLabel = "Reading every tag"
        scope.launch {
            runCatching {
                val s = withContext(Dispatchers.IO) { media.stage(uri) }
                staged = s
                dirty = false
                expanded = emptySet()
                reload()
            }.onFailure { toast = it.message ?: "that file could not be opened"; failed = true }
            busy = false
        }
    }

    val pick = rememberFilePicker(IMAGE_AND_VIDEO) { open(it) }

    val exportCopy = rememberExportPicker(
        suggestedName = copyName(staged?.displayName ?: "file"),
        mimeType = mimeForName(staged?.displayName ?: "file"),
    ) { destination ->
        val s = staged ?: return@rememberExportPicker
        busy = true; busyLabel = "Saving a copy"
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.exportTo(s, destination) }
            failed = r.isFailure
            toast = if (r.isSuccess) "a copy was saved where you chose"
                    else r.exceptionOrNull()?.message ?: "the copy could not be written"
            busy = false
        }
    }

    fun runWrite(label: String, block: (MetadataRepository, java.io.File) -> MetadataRepository.WriteResult) {
        val s = staged ?: return
        val r = repo() ?: run { toast = "the engine is still starting"; failed = true; return }
        busy = true; busyLabel = label
        scope.launch {
            val result = withContext(Dispatchers.IO) { block(r, s.workingCopy) }
            when (result) {
                is MetadataRepository.WriteResult.Ok -> {
                    toast = result.message; failed = false; dirty = true
                }
                is MetadataRepository.WriteResult.Failed -> {
                    toast = result.reason; failed = true
                }
            }
            reload()
            busy = false
        }
    }

    fun save() {
        val s = staged ?: return
        busy = true; busyLabel = "Writing back to your file"
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.commit(s) }
            failed = r.isFailure
            toast = if (r.isSuccess) "saved into ${s.displayName}"
                    else r.exceptionOrNull()?.message ?: "the file could not be written"
            if (r.isSuccess) dirty = false
            busy = false
        }
    }

    val visible = remember(doc, query) {
        val d = doc ?: return@remember emptyList<MetadataRepository.Group>()
        if (query.isBlank()) d.groups
        else d.groups.mapNotNull { g ->
            val hits = g.tags.filter {
                it.name.contains(query, true) ||
                    it.printValue.contains(query, true) ||
                    it.group.contains(query, true)
            }
            if (hits.isEmpty()) null else MetadataRepository.Group(g.name, hits)
        }
    }

    LaunchedEffect(query) {
        if (query.isNotBlank()) expanded = visible.map { it.name }.toSet()
    }

    ScreenScaffold(
        title = "Metadata",
        subtitle = staged?.let { "${it.displayName}  ${doc?.tagCount ?: 0} tags" } ?: "choose a file",
        onBack = onBack,
        actions = {
            if (staged != null) {
                IconButton(onClick = { console = true }, enabled = !busy) {
                    Icon(Icons.Rounded.Terminal, "Command console", tint = Muted)
                }
                IconButton(onClick = { adding = true }, enabled = !busy) {
                    Icon(Icons.Rounded.Add, "Add a tag", tint = Muted)
                }
                if (dirty) {
                    IconButton(onClick = { save() }, enabled = !busy) {
                        Icon(Icons.Rounded.Save, "Save", tint = Accent)
                    }
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
                detail = staged?.let {
                    val d = doc
                    if (d == null) humanSize(it.sizeBytes)
                    else "${humanSize(it.sizeBytes)}  ${d.groups.size} groups  read in ${d.elapsedMs} ms"
                },
                enabled = !busy,
                onPick = pick,
            )

            WorkingBar(busy, busyLabel)

            toast?.let {
                Spacer(Modifier.height(10.dp))
                InfoCard(if (failed) "Not done" else "Done", it, if (failed) Bad else Good)
            }

            if (staged != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search every tag and value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = fieldColors(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { savingProfile = true }, enabled = doc != null) {
                        Text("Save as profile", color = Accent, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            expanded = if (expanded.size == visible.size) emptySet()
                                       else visible.map { it.name }.toSet()
                        },
                    ) {
                        Text(
                            if (expanded.size == visible.size) "Collapse all" else "Expand all",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.name }) { group ->
                    TagGroupSection(
                        group = group,
                        expanded = group.name in expanded,
                        onToggle = {
                            expanded = if (group.name in expanded) expanded - group.name
                                       else expanded + group.name
                        },
                        onTagClick = { detail = it },
                    )
                }
                if (staged != null && visible.isEmpty() && !busy) {
                    item {
                        Spacer(Modifier.height(20.dp))
                        InfoCard(
                            "Nothing here",
                            if (doc?.tagCount == 0) "This file carries no metadata at all."
                            else "No tag matches \"$query\".",
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }

            if (staged != null) {
                SaveRow(
                    saveLabel = if (dirty) "Save changes" else "Saved",
                    enabled = !busy,
                    onSaveOver = { if (dirty) save() },
                    onExport = exportCopy,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    detail?.let { tag ->
        TagDetailSheet(
            tag = tag,
            loadBytes = {
                val s = staged
                val r = repo()
                if (s == null || r == null) ByteArray(0)
                else withContext(Dispatchers.IO) { r.binary(s.workingCopy, tag.qualified) }
            },
            onDismiss = { detail = null },
            onWrite = { value, raw ->
                detail = null
                runWrite("Writing ${tag.name}") { r, f -> r.write(f, tag.qualified, value, raw) }
            },
            onDelete = {
                detail = null
                runWrite("Removing ${tag.name}") { r, f -> r.delete(f, tag.qualified) }
            },
        )
    }

    if (adding) {
        AddTagSheet(
            groups = writableGroups,
            onDismiss = { adding = false },
            onAdd = { group, name, value, raw ->
                adding = false
                runWrite("Creating $group:$name") { r, f -> r.write(f, "$group:$name", value, raw) }
            },
        )
    }

    if (console) {
        ConsoleSheet(
            onDismiss = { console = false },
            onRun = { line ->
                val s = staged
                val r = repo()
                if (s == null || r == null) "no file open"
                else withContext(Dispatchers.IO) {
                    r.runRaw(s.workingCopy, line.trim().split(Regex("\\s+")).filter { it.isNotBlank() })
                }
            },
            afterRun = { scope.launch { reload(); dirty = true } },
        )
    }

    if (savingProfile) {
        SaveProfileSheet(
            document = doc,
            fileName = staged?.displayName ?: "",
            onDismiss = { savingProfile = false },
            onSave = { name, note, entries ->
                savingProfile = false
                profiles.create(name, note, staged?.displayName ?: "", entries)
                toast = "profile \"$name\" saved with ${entries.size} tags"
                failed = false
            },
        )
    }
}

// --------------------------------------------------------------------------- pieces

@Composable
fun WorkingBar(busy: Boolean, label: String) {
    val alpha by animateFloatAsState(if (busy) 1f else 0f, label = "busy")
    if (alpha > 0.01f) {
        Column(Modifier.padding(top = 10.dp)) {
            Text(label, color = Accent, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Accent,
                trackColor = Color.White.copy(alpha = 0.08f),
            )
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = Accent,
    unfocusedLabelColor = Muted,
    cursorColor = Accent,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagDetailSheet(
    tag: MetadataRepository.Tag,
    loadBytes: suspend () -> ByteArray,
    onDismiss: () -> Unit,
    onWrite: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var value by remember(tag) { mutableStateOf(tag.printValue) }
    var useRaw by remember(tag) { mutableStateOf(false) }
    var bytes by remember(tag) { mutableStateOf<ByteArray?>(null) }
    var loading by remember(tag) { mutableStateOf(false) }

    LaunchedEffect(useRaw) { if (useRaw && tag.rawValue != null) value = tag.rawValue }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Text(tag.group, color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(tag.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
            Spacer(Modifier.height(14.dp))

            ValueBlock("Readable value", tag.printValue.ifBlank { "(empty)" })
            if (tag.rawValue != null) {
                Spacer(Modifier.height(8.dp))
                ValueBlock("Stored value", tag.rawValue)
            }
            tag.structure?.let {
                Spacer(Modifier.height(8.dp))
                ValueBlock("Structure", it)
            }

            if (tag.isBinary) {
                Spacer(Modifier.height(14.dp))
                Text("Raw bytes", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                if (bytes == null) {
                    LaunchedEffect(tag) {
                        loading = true
                        bytes = loadBytes()
                        loading = false
                    }
                    if (loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                    }
                } else {
                    HexView(bytes!!, Modifier.fillMaxWidth())
                    Text(
                        "${tag.binaryBytes ?: bytes!!.size} bytes in the file, first ${bytes!!.size} shown",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Change it", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(if (useRaw) "New stored value" else "New readable value") },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            if (tag.rawValue != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Switch(
                        checked = useRaw,
                        onCheckedChange = { useRaw = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Accent),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Write the stored value directly",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                Button(
                    onClick = { onWrite(value, useRaw) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Write", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = Bad)
                    Spacer(Modifier.width(6.dp))
                    Text("Remove", color = Bad)
                }
            }
        }
    }
}

@Composable
private fun ValueBlock(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.28f))
                .padding(12.dp),
        ) {
            Text(value, color = Color(0xFFD6F7FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTagSheet(
    groups: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String, String, String, Boolean) -> Unit,
) {
    var group by remember { mutableStateOf(groups.firstOrNull() ?: "EXIF") }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("Add a tag", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Any tag the format can carry, in any group. The name is checked as it is " +
                    "written, and you are told exactly why if it will not go in.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text("Group", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                groups.forEach { g ->
                    FilterChip(
                        selected = g == group,
                        onClick = { group = g },
                        label = { Text(g, fontSize = 12.sp) },
                        modifier = Modifier.padding(end = 6.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent.copy(alpha = 0.25f),
                            selectedLabelColor = Accent,
                            labelColor = Muted,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Tag name, e.g. Artist") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Value") },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Switch(
                    checked = raw,
                    onCheckedChange = { raw = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Accent),
                )
                Spacer(Modifier.width(10.dp))
                Text("Value is already in stored form", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onAdd(group, name.trim(), value, raw) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Create tag", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleSheet(
    onDismiss: () -> Unit,
    onRun: suspend (String) -> String,
    afterRun: () -> Unit,
) {
    var line by remember { mutableStateOf("-a -G1 -s") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("Command console", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Arguments are passed straight to the engine, with the open file appended. " +
                    "Anything the desktop tool accepts works here.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = line,
                onValueChange = { line = it },
                label = { Text("Arguments") },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        output = onRun(line)
                        running = false
                        afterRun()
                    }
                },
                enabled = !running && line.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(if (running) "Running" else "Run", fontWeight = FontWeight.Bold) }

            if (output.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(12.dp),
                ) {
                    Text(
                        output,
                        color = Color(0xFF9BE8FF),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveProfileSheet(
    document: MetadataRepository.Document?,
    fileName: String,
    onDismiss: () -> Unit,
    onSave: (String, String, List<ProfileStore.Entry>) -> Unit,
) {
    val candidates = remember(document) {
        document?.flat()
            ?.filter { it.group !in setOf("File", "System", "Composite", "ExifTool") && !it.isBinary }
            ?.filter { it.printValue.isNotBlank() }
            ?: emptyList()
    }
    var chosen by remember(candidates) {
        mutableStateOf(candidates.filter { it.group in DEFAULT_PROFILE_GROUPS }.map { it.qualified }.toSet())
    }
    var name by remember { mutableStateOf(fileName.substringBeforeLast('.', fileName)) }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(Modifier.padding(horizontal = 20.dp).fillMaxHeight(0.9f)) {
            Text("Save as a profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Keep this identity and put it on any other file later, in one tap.",
                color = Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            Text("${chosen.size} of ${candidates.size} tags selected", color = Accent, fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f)) {
                items(candidates, key = { it.qualified }) { tag ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = tag.qualified in chosen,
                            onCheckedChange = {
                                chosen = if (tag.qualified in chosen) chosen - tag.qualified
                                         else chosen + tag.qualified
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = Ink),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(tag.qualified, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(tag.printValue, color = Muted, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val entries = candidates.filter { it.qualified in chosen }
                        .map { ProfileStore.Entry(it.qualified, it.rawValue ?: it.printValue, it.rawValue != null) }
                    onSave(name.ifBlank { "Profile" }, note, entries)
                },
                enabled = chosen.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Save profile", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private val DEFAULT_PROFILE_GROUPS = setOf(
    "ExifIFD", "IFD0", "GPS", "IPTC", "XMP-dc", "XMP-xmp", "XMP-photoshop", "QuickTime",
)
