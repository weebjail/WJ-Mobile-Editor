@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.audioeditor

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.JoinFull
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Dialog { NONE, SAVE, LOAD, RESOLVE, SETTINGS, CONFIRM_NEW, NEW_PROJECT }

/** Quick resolution choices (landscape; the Vertical box swaps width and height). */
private val ResolutionPresets = listOf(
    "1080p" to (1920 to 1080),
    "4K" to (3840 to 2160),
)

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf(Dialog.NONE) }

    var exportName by remember { mutableStateOf("") }
    var exportOtio by remember { mutableStateOf(false) }   // Resolve export as OpenTimelineIO instead of XML

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.importFiles(it)
    }
    val wavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        it?.let(vm::exportWav)
    }
    val xmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        uri?.let { vm.exportResolve(it, exportName.ifBlank { vm.projectName }, otio = false) }
    }
    // .otio has no standard MIME type; a generic one keeps the file name exactly as given.
    val otioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.exportResolve(it, exportName.ifBlank { vm.projectName }, otio = true) }
    }
    // .otio and .cube files have no standard MIME type, so any file can be picked.
    val timelineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::importTimeline)
    }
    val timelineMediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.otioMediaPicked(it)
    }
    val lutLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::importLut)
    }
    var showGrade by remember { mutableStateOf(false) }

    LaunchedEffect(vm.isPlaying) {
        while (vm.isPlaying) {
            withFrameNanos { }
            vm.tickPlayback()
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(vm.playhead, vm.isPlaying, vm.clips, vm.sources, vm.luts) }.collect { vm.updatePreview() }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            // TOP BAR: media, project, tracks, sync, export, settings
            ToolRow(scrollable = true) {
                IconBtn(Icons.Default.LibraryAdd, "Import audio or video") {
                    importLauncher.launch(arrayOf("audio/*", "video/*"))
                }
                MenuBtn(Icons.Default.Folder, "Project") { close ->
                    DropdownMenuItem(text = { Text("New project") }, onClick = {
                        close(); dialog = if (vm.dirty) Dialog.CONFIRM_NEW else Dialog.NEW_PROJECT
                    })
                    DropdownMenuItem(text = { Text("Save project…") }, onClick = { close(); dialog = Dialog.SAVE })
                    DropdownMenuItem(text = { Text("Open project…") }, onClick = { close(); dialog = Dialog.LOAD })
                    DropdownMenuItem(text = { Text("Import DaVinci Resolve timeline (OTIO)…") }, onClick = {
                        close(); timelineLauncher.launch(arrayOf("*/*"))
                    })
                }
                MenuBtn(Icons.AutoMirrored.Default.PlaylistAdd, "Tracks") { close ->
                    DropdownMenuItem(text = { Text("Add audio track") }, onClick = { close(); vm.addAudioTrack() })
                    DropdownMenuItem(text = { Text("Add video track") }, onClick = { close(); vm.addVideoTrack() })
                }
                IconBtn(
                    if (vm.snapEnabled) Icons.Default.GridOn else Icons.Default.GridOff,
                    if (vm.snapEnabled) "Snapping on" else "Snapping off",
                ) { vm.snapEnabled = !vm.snapEnabled }
                IconBtn(Icons.Default.Sync, "Sync video to recorder audio", onClick = vm::syncToRecorder)
                ToggleBtn(Icons.Default.Palette, if (showGrade) "Colour controls: on" else "Colour controls: off", showGrade) {
                    showGrade = !showGrade
                    if (showGrade && vm.gradeTarget == null) vm.message = "Tap a video clip to change its colour"
                }
                MenuBtn(Icons.Default.IosShare, "Export") { close ->
                    DropdownMenuItem(text = { Text("Export audio (WAV)") }, onClick = {
                        close(); wavLauncher.launch("${ProjectStore.safeName(vm.projectName)}.wav")
                    })
                    DropdownMenuItem(text = { Text("Export DaVinci Resolve timeline (XML)") }, onClick = {
                        close()
                        exportName = vm.projectName
                        exportOtio = false
                        dialog = Dialog.RESOLVE
                    })
                    DropdownMenuItem(text = { Text("Export DaVinci Resolve timeline (OTIO)") }, onClick = {
                        close()
                        exportName = vm.projectName
                        exportOtio = true
                        dialog = Dialog.RESOLVE
                    })
                }
                IconBtn(Icons.Default.Settings, "Settings") { dialog = Dialog.SETTINGS }
            }

            if (vm.hasVideo) VideoPane(vm, Modifier.fillMaxWidth().fillMaxHeight(0.3f))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                TimelineView(vm, Modifier.fillMaxSize())
                // Corner above the track headers: ripple across all tracks on/off.
                RippleAllToggle(vm, Modifier.size(TimelineHeaderWidth, TimelineRulerHeight))
            }

            val gradeClip = if (showGrade) vm.gradeTarget else null
            if (gradeClip != null) GradePanel(vm, gradeClip) { lutLauncher.launch(arrayOf("*/*")) }
            else vm.fadeTarget?.let { FadePanel(vm, it) }

            // BOTTOM ROW 1: transport and history
            ToolRow {
                IconBtn(Icons.AutoMirrored.Default.Undo, "Undo", enabled = vm.canUndo, onClick = vm::undo)
                IconBtn(Icons.AutoMirrored.Default.Redo, "Redo", enabled = vm.canRedo, onClick = vm::redo)
                FilledIconButton(onClick = vm::togglePlay) {
                    Icon(
                        if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (vm.isPlaying) "Pause" else "Play",
                    )
                }
                Text(
                    formatTime(vm.playhead),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                IconBtn(Icons.Default.ZoomOut, "Zoom out") { vm.zoom(0.67f, vm.viewWidthPx / 2) }
                IconBtn(Icons.Default.ZoomIn, "Zoom in") { vm.zoom(1.5f, vm.viewWidthPx / 2) }
            }
            // BOTTOM ROW 2: editing
            ToolRow {
                IconBtn(Icons.Default.DeleteSweep, "Ripple delete clip") { vm.deleteSelected(true) }
                IconBtn(Icons.Default.Delete, "Delete clip") { vm.deleteSelected(false) }
                IconBtn(Icons.Default.FirstPage, "Ripple trim start to playhead", onClick = vm::rippleTrimStart)
                IconBtn(Icons.Default.LastPage, "Ripple trim end to playhead", onClick = vm::rippleTrimEnd)
                IconBtn(Icons.Default.ContentCut, "Cut at playhead", onClick = vm::cut)
                IconBtn(Icons.Default.JoinFull, "Crossfade", onClick = vm::crossfade)
            }
        }

        vm.busy?.let { BusyOverlay(it) }
    }

    vm.pendingImport?.let { PlacementDialog(vm, it) }
    vm.otioMedia?.let { req ->
        AlertDialog(
            onDismissRequest = vm::cancelOtio,
            title = { Text("Find the media") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val n = req.missing.size
                    Text(
                        "“${req.otio.name}” uses ${if (n == 1) "a file that isn't" else "$n files that aren't"} in this project yet. " +
                            "Select ${if (n == 1) "it" else "them"} on this phone (you can select several at once); " +
                            "they're matched by file name."
                    )
                    Text(req.missing.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { timelineMediaLauncher.launch(arrayOf("audio/*", "video/*")) }) { Text("Select files") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = vm::cancelOtio) { Text("Cancel") }
                    TextButton(onClick = vm::otioSkipMissing) { Text("Leave out") }
                }
            },
        )
    }

    when (dialog) {
        Dialog.NONE -> {}
        Dialog.SAVE -> SaveDialog(vm) { dialog = Dialog.NONE }
        Dialog.LOAD -> LoadDialog(vm) { dialog = Dialog.NONE }
        Dialog.SETTINGS -> SettingsDialog(vm) { dialog = Dialog.NONE }
        Dialog.NEW_PROJECT -> NewProjectDialog(vm) { dialog = Dialog.NONE }
        Dialog.CONFIRM_NEW -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = { Text("Start a new project?") },
            text = { Text("“${vm.projectName}” has unsaved changes. They will be lost.") },
            confirmButton = { TextButton(onClick = { dialog = Dialog.NEW_PROJECT }) { Text("Discard changes") } },
            dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Cancel") } },
        )
        Dialog.RESOLVE -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = { Text(if (exportOtio) "Export for DaVinci Resolve (OTIO)" else "Export for DaVinci Resolve (XML)") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        (if (exportOtio) "Saves the timeline as an OpenTimelineIO (.otio) file, for Resolve 18.5 and later. "
                        else "Saves the timeline as an .xml file, for any Resolve version. ") +
                            "Your media isn't copied: Resolve uses your original files. Copy them and the timeline file " +
                            "to your computer."
                    )
                    OutlinedTextField(exportName, { exportName = it }, label = { Text("Timeline name") }, singleLine = true)
                    Text(
                        "Timeline: ${vm.frameRate.label} fps, ${vm.timelineWidth}×${vm.timelineHeight}. " +
                            "You can change these in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        vm.resolveMediaFolder, vm::updateResolveMediaFolder,
                        label = { Text("Media folder on your computer") },
                        placeholder = { Text("e.g. D:/Shoots/Day 1") },
                        singleLine = true,
                    )
                    Text(
                        "With a folder: in Resolve use File > Import > Timeline and the clips link to the files in it.\n\n" +
                            (if (exportOtio) "Without one: each file is named relative to the .otio, so keep them in the same folder. "
                            else "Without one: each file is named without a folder. ") +
                            "If Resolve doesn't find them, drag the media into the Media Pool first, then File > Import > Timeline " +
                            "with “Automatically import source clips into media pool” unticked. Resolve matches clips by file name.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val used = vm.usedMediaNames()
                    if (used.isNotEmpty()) Text("Files used: " + used.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                val fileName = ProjectStore.safeName(exportName.ifBlank { vm.projectName })
                TextButton(onClick = {
                    dialog = Dialog.NONE
                    if (exportOtio) otioLauncher.launch("$fileName.otio") else xmlLauncher.launch("$fileName.xml")
                }) { Text(if (exportOtio) "Save .otio" else "Save .xml") }
            },
            dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Cancel") } },
        )
    }
}

/**
 * Symbol button: stacked layers = ripple edits affect every (unlocked) track; highlighted when on.
 * Long-press shows its name, like the other buttons.
 */
@Composable
private fun RippleAllToggle(vm: EditorViewModel, modifier: Modifier) {
    val on = vm.rippleAllTracks
    val label = if (on) "Ripple all tracks: on" else "Ripple all tracks: off"
    Labeled(label) {
        Box(
            modifier
                .background(if (on) MaterialTheme.colorScheme.primary else Color(0xFF2A2C32))
                .clickable { vm.updateRippleAllTracks(!on) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (on) Icons.Default.Layers else Icons.Default.LayersClear,
                contentDescription = label,
                tint = if (on) MaterialTheme.colorScheme.onPrimary else Color(0xFFB8BCC6),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun VideoPane(vm: EditorViewModel, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = vm.preview.player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (!vm.preview.hasFrame) Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

@Composable
private fun SaveDialog(vm: EditorViewModel, close: () -> Unit) {
    var name by remember { mutableStateOf(vm.projectName) }
    val overwrite = vm.wouldOverwrite(name)
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Save project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
                if (overwrite) {
                    Text(
                        "A different project called “${ProjectStore.safeName(name)}” already exists and will be replaced.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { close(); vm.saveProject(name) }) { Text(if (overwrite) "Replace" else "Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun LoadDialog(vm: EditorViewModel, close: () -> Unit) {
    // Looking up media sizes touches every file, so the list is read in the background.
    var projects by remember { mutableStateOf<List<ProjectStore.Info>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { projects = withContext(Dispatchers.IO) { vm.projectInfo() } }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmOpen by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “$p”?") },
            text = { Text("The project file is deleted permanently. Your media files are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(p); refresh++; confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
        return
    }
    confirmOpen?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmOpen = null },
            title = { Text("Open “$p”?") },
            text = { Text("“${vm.projectName}” has unsaved changes. They will be lost.") },
            confirmButton = { TextButton(onClick = { close(); vm.loadProject(p) }) { Text("Discard and open") } },
            dismissButton = { TextButton(onClick = { confirmOpen = null }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Open project") },
        text = {
            val list = projects
            when {
                list == null -> Text("Loading…")
                list.isEmpty() -> Text("No saved projects yet.")
                else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    list.forEach { info ->
                        val p = info.name
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { if (vm.dirty) confirmOpen = p else { close(); vm.loadProject(p) } },
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(p, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        projectDetails(context, info),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { confirmDelete = p }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete project $p")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

/** Two lines, e.g. "Opened 10 Nov 2026, 14:23" and "1.2 GB of media · 1 file missing", in the phone's own format. */
private fun projectDetails(context: android.content.Context, info: ProjectStore.Info): String {
    val date = android.text.format.DateUtils.formatDateTime(
        context, info.lastOpened,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_YEAR or
            android.text.format.DateUtils.FORMAT_ABBREV_MONTH or android.text.format.DateUtils.FORMAT_SHOW_TIME,
    )
    val size = android.text.format.Formatter.formatShortFileSize(context, info.mediaBytes)
    val missing = when (info.missingMedia) {
        0 -> ""
        1 -> " · 1 file missing"
        else -> " · ${info.missingMedia} files missing"
    }
    return "Opened $date\n$size of media$missing"
}

/** Editable timeline format, kept as text while typing. Valid only when both sides are in range. */
private class FormatState(rate: FrameRate, width: Int, height: Int) {
    var rate by mutableStateOf(rate)
    var width by mutableStateOf(width.toString())
    var height by mutableStateOf(height.toString())
    val w: Int? get() = width.toIntOrNull()?.takeIf { it in MIN_RESOLUTION..MAX_RESOLUTION }
    val h: Int? get() = height.toIntOrNull()?.takeIf { it in MIN_RESOLUTION..MAX_RESOLUTION }
    val valid: Boolean get() = w != null && h != null
    var vertical by mutableStateOf(height > width); private set

    fun changeVertical(on: Boolean) {
        if (on == vertical) return
        vertical = on
        // Turn the picture on its side: swap width and height.
        val oldW = width
        width = height
        height = oldW
    }

    fun pick(preset: Pair<Int, Int>) {
        val (long, short) = preset
        width = (if (vertical) short else long).toString()
        height = (if (vertical) long else short).toString()
    }

    fun isPreset(preset: Pair<Int, Int>): Boolean =
        if (vertical) w == preset.second && h == preset.first else w == preset.first && h == preset.second
}

@Composable
private fun TimelineFormatFields(s: FormatState) {
    Text("Timeline frame rate", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrameRate.entries.forEach { r ->
            FilterChip(selected = s.rate == r, onClick = { s.rate = r }, label = { Text(r.label) })
        }
    }
    Text("Timeline resolution", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ResolutionPresets.forEach { (label, size) ->
            FilterChip(selected = s.isPreset(size), onClick = { s.pick(size) }, label = { Text(label) })
        }
        Row(
            Modifier.clickable { s.changeVertical(!s.vertical) }.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = s.vertical, onCheckedChange = { s.changeVertical(it) })
            Text("Vertical")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
        OutlinedTextField(
            s.width, { s.width = it.filter(Char::isDigit).take(4) }, label = { Text("Width") },
            singleLine = true, keyboardOptions = numbers, isError = s.w == null, modifier = Modifier.weight(1f),
        )
        Text("×")
        OutlinedTextField(
            s.height, { s.height = it.filter(Char::isDigit).take(4) }, label = { Text("Height") },
            singleLine = true, keyboardOptions = numbers, isError = s.h == null, modifier = Modifier.weight(1f),
        )
    }
    if (!s.valid) {
        Text(
            "Width and height must be between $MIN_RESOLUTION and $MAX_RESOLUTION.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NewProjectDialog(vm: EditorViewModel, close: () -> Unit) {
    var name by remember { mutableStateOf("Untitled") }
    val format = remember { FormatState(DEFAULT_FRAME_RATE, DEFAULT_WIDTH, DEFAULT_HEIGHT) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("New project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
                TimelineFormatFields(format)
                Text(
                    "Match your camera's frame rate. Both can be changed later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = format.valid,
                onClick = { close(); vm.newProject(name, format.rate, format.w!!, format.h!!) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsDialog(vm: EditorViewModel, close: () -> Unit) {
    val format = remember { FormatState(vm.frameRate, vm.timelineWidth, vm.timelineHeight) }
    // While width or height is invalid the dialog stays open (Done is disabled), so no change is silently lost.
    val done = {
        if (format.valid) {
            vm.setTimelineFormat(format.rate, format.w!!, format.h!!)
            close()
        }
    }
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This project", style = MaterialTheme.typography.titleMedium)
                TimelineFormatFields(format)
                Text("Editing", style = MaterialTheme.typography.titleMedium)
                Text("Default fade curve", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = vm.defaultCurve == FadeCurve.CURVED,
                        onClick = { vm.updateDefaultCurve(FadeCurve.CURVED) },
                        label = { Text("Exponential (+3 dB)") },
                    )
                    FilterChip(
                        selected = vm.defaultCurve == FadeCurve.LINEAR,
                        onClick = { vm.updateDefaultCurve(FadeCurve.LINEAR) },
                        label = { Text("Linear (0 dB)") },
                    )
                }
                Text(
                    "Applies to all fades and crossfades in the project. Exponential matches Resolve's default “Cross Fade +3 dB”, " +
                        "which keeps the volume steady through a crossfade.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Crossfade length: ${"%.2f".format(vm.crossfadeSeconds)} s",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = vm.crossfadeSeconds,
                    onValueChange = { vm.updateCrossfadeSeconds((it * 4).toInt() / 4f) },
                    valueRange = 0.25f..4f,
                    steps = 14,
                )
            }
        },
        confirmButton = { TextButton(onClick = done, enabled = format.valid) { Text("Done") } },
    )
}

@Composable
private fun FadePanel(vm: EditorViewModel, clip: Clip) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FadeControl("In", clip.fadeIn, clip.length - clip.fadeOut.frames, Modifier.weight(1f), vm::beginFadeEdit) {
            vm.setFade(clip.id, fadeIn = it)
        }
        FadeControl("Out", clip.fadeOut, clip.length - clip.fadeIn.frames, Modifier.weight(1f), vm::beginFadeEdit) {
            vm.setFade(clip.id, fadeOut = it)
        }
    }
}

@Composable
private fun FadeControl(
    label: String,
    fade: Fade,
    available: Long,
    modifier: Modifier,
    onStart: () -> Unit,
    onChange: (Fade) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val maxFrames = minOf(available, 10L * SAMPLE_RATE).coerceAtLeast(1L)
    // The slider is exponential: halfway is 2.5s, the far end 10s, so short fades get most of the travel.
    val maxPos = fadeSliderPos(maxFrames)
    Column(modifier) {
        Text(
            "Fade $label ${"%.2f".format(fade.frames / SAMPLE_RATE.toFloat())}s",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = fadeSliderPos(fade.frames).coerceAtMost(maxPos),
            onValueChange = {
                if (!editing) { onStart(); editing = true }
                onChange(fade.copy(frames = fadeSliderFrames(it).coerceAtMost(maxFrames)))
            },
            onValueChangeFinished = { editing = false },
            valueRange = 0f..maxPos,
        )
    }
}

// Fade slider curve: seconds = 1.25 * (9^pos - 1), so pos 0 -> 0s, 0.5 -> 2.5s, 1 -> 10s.
private fun fadeSliderFrames(pos: Float): Long =
    (1.25 * (Math.pow(9.0, pos.toDouble()) - 1.0) * SAMPLE_RATE).roundToLong().coerceAtLeast(0L)

private fun fadeSliderPos(frames: Long): Float =
    (ln(frames.toDouble() / SAMPLE_RATE / 1.25 + 1.0) / ln(9.0)).toFloat()

/** Asked when importing onto a timeline that already has clips: where the new media (or timeline) should go. */
@Composable
private fun PlacementDialog(vm: EditorViewModel, req: EditorViewModel.PendingImport) {
    val timeline = req is EditorViewModel.PendingImport.Timeline
    val options = listOf(
        Triple(Placement.PLAYHEAD, Icons.Default.MyLocation, "At the playhead" to
            "Replaces whatever is there, like dropping a clip in Resolve"),
        Triple(Placement.END, Icons.AutoMirrored.Default.KeyboardTab, "At the end of the timeline" to
            "After the last clip"),
        Triple(Placement.NEW_TRACK, Icons.AutoMirrored.Default.PlaylistAdd, (if (timeline) "On new tracks" else "On a new track") to
            "Added below the existing tracks, starting at the playhead"),
    )
    AlertDialog(
        onDismissRequest = { vm.choosePlacement(null) },
        title = {
            Text(
                when (req) {
                    is EditorViewModel.PendingImport.Timeline -> "Where should “${req.otio.name}” go?"
                    is EditorViewModel.PendingImport.Media -> if (req.uris.size == 1) "Where should the file go?" else "Where should the files go?"
                }
            )
        },
        text = {
            Column {
                options.forEach { (p, icon, text) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.choosePlacement(p) }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null)
                        Column {
                            Text(text.first, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text.second, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.choosePlacement(null) }) { Text("Cancel") } },
    )
}

/**
 * Colour for the selected video clip, in Resolve's order: a LUT (e.g. GP-Log or F-Log to Rec.709), then exposure,
 * highlights and shadows. Shown in place of the fade sliders while the colour button is on.
 */
@Composable
private fun GradePanel(vm: EditorViewModel, clip: Clip, loadLut: () -> Unit) {
    val g = clip.grade
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = g.lut == null, onClick = { vm.changeGrade(clip.id, g.copy(lut = null)) }, label = { Text("No LUT") })
                // A LUT the project uses but that isn't loaded on this phone is still listed, marked as missing.
                val names = (vm.luts.keys + listOfNotNull(g.lut)).distinct().sortedBy { it.lowercase() }
                names.forEach { name ->
                    FilterChip(
                        selected = g.lut == name,
                        onClick = { vm.changeGrade(clip.id, g.copy(lut = name)) },
                        label = { Text(if (name in vm.luts) name else "$name (missing)") },
                    )
                }
            }
            IconBtn(Icons.Default.Add, "Load a LUT (.cube file)", onClick = loadLut)
            IconBtn(Icons.Default.RestartAlt, "Reset colour", enabled = !g.isNeutral) { vm.changeGrade(clip.id, Grade()) }
            MenuBtn(Icons.Default.MoreVert, "More colour options") { close ->
                DropdownMenuItem(text = { Text("Copy to all clips from this file") }, onClick = { close(); vm.copyGrade(clip, all = false) })
                DropdownMenuItem(text = { Text("Copy to all video clips") }, onClick = { close(); vm.copyGrade(clip, all = true) })
                g.lut?.takeIf { it in vm.luts }?.let { name ->
                    DropdownMenuItem(text = { Text("Remove LUT “$name” from the app") }, onClick = { close(); vm.deleteLut(name) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GradeSlider("Exposure", g.exposure, -2f..2f, "%+.1f".format(g.exposure), Modifier.weight(1f), vm::beginGradeEdit) {
                vm.setGrade(clip.id, g.copy(exposure = (it * 10).toInt() / 10f))
            }
            GradeSlider("Highlights", g.highlights, -1f..1f, "%+d".format((g.highlights * 100).toInt()), Modifier.weight(1f), vm::beginGradeEdit) {
                vm.setGrade(clip.id, g.copy(highlights = (it * 100).toInt() / 100f))
            }
            GradeSlider("Shadows", g.shadows, -1f..1f, "%+d".format((g.shadows * 100).toInt()), Modifier.weight(1f), vm::beginGradeEdit) {
                vm.setGrade(clip.id, g.copy(shadows = (it * 100).toInt() / 100f))
            }
        }
    }
}

@Composable
private fun GradeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    shown: String,
    modifier: Modifier,
    onStart: () -> Unit,
    onChange: (Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    Column(modifier) {
        Text("$label $shown", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = {
                if (!editing) { onStart(); editing = true }
                onChange(it)
            },
            onValueChangeFinished = { editing = false },
            valueRange = range,
        )
    }
}

/** A symbol button that stays highlighted while switched on. */
@Composable
private fun ToggleBtn(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Labeled(label) {
        if (on) FilledIconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
        else IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

/** A row of controls. Fixed rows spread their buttons evenly; the top row may scroll on very narrow screens. */
@Composable
private fun ToolRow(scrollable: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (scrollable) it.horizontalScroll(rememberScrollState()) else it }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = if (scrollable) Arrangement.spacedBy(2.dp) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Long-pressing any toolbar icon shows what it does. */
@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) { content() }
}

@Composable
private fun IconBtn(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Labeled(label) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun MenuBtn(icon: ImageVector, label: String, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(icon, label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
private fun BusyOverlay(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Row(
                Modifier.padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(text)
            }
        }
    }
}
