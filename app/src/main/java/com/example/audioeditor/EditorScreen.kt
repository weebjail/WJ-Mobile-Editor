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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.importFiles(it)
    }
    val wavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        it?.let(vm::exportWav)
    }
    val xmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        uri?.let { vm.exportResolveXml(it, exportName.ifBlank { vm.projectName }) }
    }

    LaunchedEffect(vm.isPlaying) {
        while (vm.isPlaying) {
            withFrameNanos { }
            vm.tickPlayback()
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(vm.playhead, vm.isPlaying, vm.clips, vm.sources) }.collect { vm.updatePreview() }
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
                MenuBtn(Icons.Default.IosShare, "Export") { close ->
                    DropdownMenuItem(text = { Text("Export audio (WAV)") }, onClick = {
                        close(); wavLauncher.launch("${ProjectStore.safeName(vm.projectName)}.wav")
                    })
                    DropdownMenuItem(text = { Text("Export DaVinci Resolve timeline (XML)") }, onClick = {
                        close()
                        exportName = vm.projectName
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

            vm.fadeTarget?.let { FadePanel(vm, it) }

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
            title = { Text("Export for DaVinci Resolve") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Saves the timeline as an .xml file. Your media isn't copied: Resolve uses your original files. " +
                            "Copy them and the .xml to your computer."
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
                            "Without one: first drag the media into Resolve's Media Pool, then File > Import > Timeline " +
                            "with “Automatically import source clips into media pool” unticked. Resolve matches clips by file name.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Files used: " + vm.usedMediaNames().joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = Dialog.NONE
                    xmlLauncher.launch(ProjectStore.safeName(exportName.ifBlank { vm.projectName }) + ".xml")
                }) { Text("Save .xml") }
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
    val maxFrames = minOf(available, 10L * SAMPLE_RATE).coerceAtLeast(1L).toFloat()
    Column(modifier) {
        Text(
            "Fade $label ${"%.2f".format(fade.frames / SAMPLE_RATE.toFloat())}s",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = fade.frames.toFloat().coerceAtMost(maxFrames),
            onValueChange = {
                if (!editing) { onStart(); editing = true }
                onChange(fade.copy(frames = it.toLong()))
            },
            onValueChangeFinished = { editing = false },
            valueRange = 0f..maxFrames,
        )
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
