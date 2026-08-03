package com.metaforge.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
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
import com.metaforge.engine.ContainerCopier
import com.metaforge.engine.MetadataRepository
import com.metaforge.ui.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Move metadata from one file to another, with the user in charge of every tag.
 *
 * Both files are read first and shown side by side. Nothing is written until
 * the user has seen what the target holds now, what would replace it, and has
 * approved it, tag by tag if they want. Any value can be edited on the way
 * across, so the transplant is an edit session rather than a blind copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransplantScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = remember { Engine.media(context) }
    val profiles = remember { ProfileStore(context) }

    var source by remember { mutableStateOf<MediaAccess.Staged?>(null) }
    var target by remember { mutableStateOf<MediaAccess.Staged?>(null) }
    var sourceDoc by remember { mutableStateOf<MetadataRepository.Document?>(null) }
    var targetDoc by remember { mutableStateOf<MetadataRepository.Document?>(null) }

    var rows by remember { mutableStateOf<List<DiffEntry>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var edits by remember { mutableStateOf(mapOf<String, String>()) }
    var filter by remember { mutableStateOf(Filter.CHANGES) }
    var copyBlocks by remember { mutableStateOf(true) }

    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf<Applied?>(null) }
    var editing by remember { mutableStateOf<DiffEntry?>(null) }
    var savingProfile by remember { mutableStateOf(false) }

    fun repo(): MetadataRepository? = Engine.exifTool(context)?.let { MetadataRepository(it) }

    fun rebuild() {
        val s = sourceDoc ?: return
        val t = targetDoc
        val targetMap = t?.flat()?.associateBy { it.qualified } ?: emptyMap()
        val built = s.flat()
            .filter { it.group !in SKIP_GROUPS }
            .filter { it.printValue.isNotBlank() }
            .map { tag ->
                val current = targetMap[tag.qualified]
                DiffEntry(
                    qualified = tag.qualified,
                    group = tag.group,
                    name = tag.name,
                    incoming = tag.rawValue ?: tag.printValue,
                    incomingReadable = tag.printValue,
                    existing = current?.printValue,
                    raw = tag.rawValue != null,
                    binary = tag.isBinary,
                )
            }
            .sortedWith(compareBy({ it.group }, { it.name }))
        rows = built
        selected = built.filter { it.status != Status.SAME }.map { it.qualified }.toSet()
        edits = emptyMap()
        applied = null
    }

    fun load(uri: Uri, asSource: Boolean) {
        busy = true
        busyLabel = if (asSource) "Reading the source" else "Reading the target"
        scope.launch {
            runCatching {
                val staged = withContext(Dispatchers.IO) { media.stage(uri) }
                val r = repo()
                val d = if (r == null) null else withContext(Dispatchers.IO) { r.read(staged.workingCopy) }
                if (asSource) { source = staged; sourceDoc = d } else { target = staged; targetDoc = d }
                rebuild()
            }.onFailure { toast = it.message ?: "that file could not be opened"; failed = true }
            busy = false
        }
    }

    val exportCopy = rememberExportPicker(
        suggestedName = copyName(target?.displayName ?: "file"),
        mimeType = mimeForName(target?.displayName ?: "file"),
    ) { destination ->
        val t = target ?: return@rememberExportPicker
        busy = true; busyLabel = "Saving a copy"
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.exportTo(t, destination) }
            failed = r.isFailure
            toast = if (r.isSuccess) "a copy was saved where you chose"
                    else r.exceptionOrNull()?.message ?: "the copy could not be written"
            busy = false
        }
    }

    val pickSource = rememberFilePicker(IMAGE_AND_VIDEO) { load(it, true) }
    val pickTarget = rememberFilePicker(IMAGE_AND_VIDEO) { load(it, false) }

    fun apply() {
        val s = source ?: return
        val t = target ?: return
        val r = repo() ?: run { toast = "the engine is still starting"; failed = true; return }
        val chosen = rows.filter { it.qualified in selected }
        if (chosen.isEmpty()) { toast = "nothing is selected"; failed = true; return }

        busy = true; busyLabel = "Writing ${chosen.size} tags into ${t.displayName}"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Untouched tags travel as a copy from the source so binary
                // payloads and maker notes arrive byte for byte. Edited ones are
                // written as explicit assignments.
                val copied = chosen.filter { it.qualified !in edits }
                val changed = chosen.filter { it.qualified in edits }

                val args = mutableListOf<String>()
                if (copied.isNotEmpty()) {
                    args += "-tagsFromFile"
                    args += s.workingCopy.absolutePath
                    args += "-unsafe"
                    copied.forEach { args += "-${it.qualified}" }
                }
                changed.forEach {
                    val v = edits[it.qualified] ?: ""
                    args += if (it.raw) "-${it.qualified}#=$v" else "-${it.qualified}=$v"
                }
                val write = r.apply(t.workingCopy, args, "${chosen.size} tags written")

                val blocks = if (copyBlocks) {
                    ContainerCopier.copy(s.workingCopy, t.workingCopy).copied
                } else {
                    emptyList()
                }

                val after = r.read(t.workingCopy)
                val afterMap = after.flat().associateBy { it.qualified }
                val missed = chosen.filter { row ->
                    val got = afterMap[row.qualified] ?: return@filter true
                    val want = edits[row.qualified] ?: row.incomingReadable
                    got.printValue.trim() != want.trim() && (got.rawValue?.trim() ?: "") != want.trim()
                }
                targetDoc = after
                Triple(write, blocks, missed)
            }

            val (write, blocks, missed) = result
            when (write) {
                is MetadataRepository.WriteResult.Failed -> {
                    toast = write.reason; failed = true
                }
                is MetadataRepository.WriteResult.Ok -> {
                    failed = false
                    applied = Applied(chosen.size, chosen.size - missed.size, missed.map { it.qualified }, blocks)
                    toast = null
                }
            }
            busy = false
        }
    }

    fun save() {
        val t = target ?: return
        busy = true; busyLabel = "Writing back to ${t.displayName}"
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.commit(t) }
            failed = r.isFailure
            toast = if (r.isSuccess) "saved into ${t.displayName}"
                    else r.exceptionOrNull()?.message ?: "the file could not be written"
            busy = false
        }
    }

    val shown = remember(rows, filter, selected) {
        when (filter) {
            Filter.ALL -> rows
            Filter.CHANGES -> rows.filter { it.status != Status.SAME }
            Filter.SELECTED -> rows.filter { it.qualified in selected }
        }
    }

    ScreenScaffold(
        title = "Transplant",
        subtitle = when {
            source == null || target == null -> "choose both files"
            else -> "${selected.size} of ${rows.size} tags approved"
        },
        onBack = onBack,
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            FileSlot(
                label = "Source: read from",
                fileName = source?.displayName,
                detail = sourceDoc?.let { "${it.tagCount} tags" } ?: source?.let { humanSize(it.sizeBytes) },
                enabled = !busy,
                onPick = pickSource,
            )
            Spacer(Modifier.height(8.dp))
            FileSlot(
                label = "Target: written into",
                fileName = target?.displayName,
                detail = targetDoc?.let { "${it.tagCount} tags now" } ?: target?.let { humanSize(it.sizeBytes) },
                enabled = !busy,
                onPick = pickTarget,
            )

            WorkingBar(busy, busyLabel)

            toast?.let {
                Spacer(Modifier.height(10.dp))
                InfoCard(if (failed) "Not done" else "Done", it, if (failed) Bad else Good)
            }

            applied?.let { a ->
                Spacer(Modifier.height(10.dp))
                AppliedCard(a)
                Spacer(Modifier.height(8.dp))
                SaveRow(
                    saveLabel = "Save into the file",
                    enabled = !busy,
                    onSaveOver = { save() },
                    onExport = exportCopy,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { savingProfile = true },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) { Text("Keep this as a profile", color = Accent) }
            }

            if (rows.isNotEmpty() && applied == null) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Filter.entries.forEach { f ->
                        FilterChip(
                            selected = f == filter,
                            onClick = { filter = f },
                            label = { Text(f.label, fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 6.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent.copy(alpha = 0.22f),
                                selectedLabelColor = Accent,
                                labelColor = Muted,
                            ),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = rows.map { it.qualified }.toSet() }) {
                        Text("Approve all", color = Accent, fontSize = 13.sp)
                    }
                    TextButton(onClick = { selected = emptySet() }) {
                        Text("Approve none", color = Muted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = copyBlocks,
                        onCheckedChange = { copyBlocks = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Accent),
                    )
                }
                Text(
                    "Also carry across the signed blocks the tag layer cannot reach, such as content credentials.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }

            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.qualified }) { row ->
                    DiffRow(
                        row = row,
                        edited = edits[row.qualified],
                        checked = row.qualified in selected,
                        onCheck = {
                            selected = if (row.qualified in selected) selected - row.qualified
                                       else selected + row.qualified
                        },
                        onEdit = { editing = row },
                    )
                }
                if (rows.isEmpty() && source != null && target != null && !busy) {
                    item {
                        Spacer(Modifier.height(20.dp))
                        InfoCard("Nothing to move", "The source file carries no transferable tags.")
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }

            if (rows.isNotEmpty() && applied == null) {
                RunButton(
                    text = if (busy) "Working" else "Apply ${selected.size} approved tags",
                    enabled = !busy && selected.isNotEmpty(),
                ) { apply() }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    editing?.let { row ->
        EditValueSheet(
            row = row,
            initial = edits[row.qualified] ?: row.incomingReadable,
            onDismiss = { editing = null },
            onApply = { v ->
                edits = edits + (row.qualified to v)
                selected = selected + row.qualified
                editing = null
            },
            onReset = {
                edits = edits - row.qualified
                editing = null
            },
        )
    }

    if (savingProfile) {
        var name by remember { mutableStateOf(source?.displayName?.substringBeforeLast('.') ?: "Profile") }
        AlertDialog(
            onDismissRequest = { savingProfile = false },
            containerColor = Panel,
            title = { Text("Keep this as a profile", color = Color.White) },
            text = {
                Column {
                    Text(
                        "The approved tags and any edits you made are stored together, ready to " +
                            "put on another file later.",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Name") },
                        colors = fieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val entries = rows.filter { it.qualified in selected }.map {
                        ProfileStore.Entry(
                            tag = it.qualified,
                            value = edits[it.qualified] ?: it.incoming,
                            raw = it.raw && it.qualified !in edits,
                        )
                    }
                    profiles.create(name, "from a transplant", source?.displayName ?: "", entries)
                    savingProfile = false
                    toast = "profile \"$name\" saved"
                    failed = false
                }) { Text("Save", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { savingProfile = false }) { Text("Cancel", color = Muted) }
            },
        )
    }
}

// --------------------------------------------------------------------------- model

private enum class Filter(val label: String) {
    CHANGES("Only what changes"),
    ALL("Every tag"),
    SELECTED("Approved"),
}

private enum class Status { NEW, DIFFERENT, SAME }

private data class DiffEntry(
    val qualified: String,
    val group: String,
    val name: String,
    val incoming: String,
    val incomingReadable: String,
    val existing: String?,
    val raw: Boolean,
    val binary: Boolean,
) {
    val status: Status
        get() = when {
            existing == null -> Status.NEW
            existing.trim() == incomingReadable.trim() -> Status.SAME
            else -> Status.DIFFERENT
        }
}

private data class Applied(
    val attempted: Int,
    val verified: Int,
    val missed: List<String>,
    val blocks: List<String>,
)

private val SKIP_GROUPS = setOf("File", "System", "Composite", "ExifTool")

// --------------------------------------------------------------------------- views

@Composable
private fun DiffRow(
    row: DiffEntry,
    edited: String?,
    checked: Boolean,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
) {
    val tint = when (row.status) {
        Status.NEW -> Good
        Status.DIFFERENT -> Warn
        Status.SAME -> Muted
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (checked) 0.06f else 0.025f))
            .clickable { onCheck() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onCheck() },
                colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = Ink),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    row.qualified,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    when (row.status) {
                        Status.NEW -> "not present in the target"
                        Status.DIFFERENT -> "target has a different value"
                        Status.SAME -> "already identical"
                    },
                    color = tint,
                    fontSize = 10.sp,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, "Edit before writing", tint = if (edited != null) Accent else Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (row.existing != null) {
            LabelledValue("now", row.existing, Muted)
            Spacer(Modifier.height(3.dp))
        }
        LabelledValue(
            if (edited != null) "your value" else "incoming",
            edited ?: row.incomingReadable,
            if (edited != null) Accent else tint,
        )
    }
}

@Composable
private fun LabelledValue(label: String, value: String, tint: Color) {
    Row {
        Text(
            label,
            color = tint,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(58.dp),
        )
        Text(
            value.ifBlank { "(empty)" },
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
        )
    }
}

@Composable
private fun AppliedCard(a: Applied) {
    val ok = a.missed.isEmpty()
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = (if (ok) Good else Warn).copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (ok) "All ${a.verified} tags are in place" else "${a.verified} of ${a.attempted} arrived",
                color = if (ok) Good else Warn,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
            if (a.blocks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "also carried: ${a.blocks.joinToString()}",
                    color = Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (a.missed.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("The format would not accept:", color = Warn, fontSize = 12.sp)
                a.missed.take(10).forEach {
                    Text(it, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (a.missed.size > 10) {
                    Text("and ${a.missed.size - 10} more", color = Muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Nothing has touched your original file yet.",
                color = Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditValueSheet(
    row: DiffEntry,
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
    onReset: () -> Unit,
) {
    var value by remember(row) { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(row.group, color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(row.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            row.existing?.let {
                Text("Target currently holds", color = Muted, fontSize = 11.sp)
                Text(it, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(10.dp))
            }
            Text("Source offers", color = Muted, fontSize = 11.sp)
            Text(row.incomingReadable, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Write this instead") },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(14.dp))
            Row {
                Button(
                    onClick = { onApply(value) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Use my value", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = onReset,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(48.dp),
                ) { Text("Use the source", color = Muted) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
