@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.audioeditor

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.JoinFull
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val context = LocalContext.current
    var showResolveDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }

    var saveNameText by remember { mutableStateOf(vm.projectName) }
    var exportName by remember { mutableStateOf(vm.projectName) }
    var exportRate by remember { mutableStateOf(FrameRate.F24) }
    var exportPath by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.importFiles(it)
    }
    val wavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        it?.let(vm::exportWav)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.exportResolveXml(it, exportName.ifBlank { "My Mix" }, exportRate, exportPath) }
    }

    LaunchedEffect(vm.isPlaying) {
        while (vm.isPlaying) {
            withFrameNanos { }
            vm.tickPlayback()
        }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            // TOP BAR: Project File Actions & Timeline Settings
            ToolRow {
                IconBtn(Icons.Default.AudioFile, "Import Audio") { importLauncher.launch(arrayOf("audio/*")) }
                IconBtn(Icons.Default.NoteAdd, "New Project", onClick = vm::newProject)
                IconBtn(Icons.Default.Save, "Save Project") { saveNameText = vm.projectName; showSaveDialog = true }
                IconBtn(Icons.Default.FolderOpen, "Load Project") { showLoadDialog = true }
                IconBtn(Icons.Default.Add, "Add Track", onClick = vm::addTrack)
                IconBtn(if (vm.snapEnabled) Icons.Default.GridOn else Icons.Default.GridOff, if (vm.snapEnabled) "Snapping ON" else "Snapping OFF") {
                    vm.snapEnabled = !vm.snapEnabled
                }
                IconBtn(Icons.Default.Download, "Export WAV") { wavLauncher.launch("${exportName.ifBlank { "My Mix" }}.wav") }
                IconBtn(Icons.Default.Movie, "Export Resolve XML") { showResolveDialog = true }
            }

            TimelineView(vm, Modifier.weight(1f).fillMaxWidth())

            vm.selectedClip?.let { FadePanel(vm, it) }

            // BOTTOM BAR: Playback, History & Edit Controls
            ToolRow {
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
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                IconBtn(Icons.AutoMirrored.Default.Undo, "Undo", enabled = vm.canUndo, onClick = vm::undo)
                IconBtn(Icons.AutoMirrored.Default.Redo, "Redo", enabled = vm.canRedo, onClick = vm::redo)
                IconBtn(Icons.Default.ContentCut, "Cut at Playhead", onClick = vm::cut)
                IconBtn(Icons.Default.FirstPage, "Ripple Trim Start", onClick = vm::rippleTrimStart)
                IconBtn(Icons.Default.LastPage, "Ripple Trim End", onClick = vm::rippleTrimEnd)
                IconBtn(Icons.Default.JoinFull, "Crossfade", onClick = vm::crossfade)
                IconBtn(Icons.Default.Delete, "Delete Clip") { vm.deleteSelected(false) }
                IconBtn(Icons.Default.DeleteSweep, "Ripple Delete") { vm.deleteSelected(true) }
                IconBtn(Icons.Default.ZoomOut, "Zoom Out") { vm.zoom(0.67f, vm.viewWidthPx / 2) }
                IconBtn(Icons.Default.ZoomIn, "Zoom In") { vm.zoom(1.5f, vm.viewWidthPx / 2) }
            }
        }

        vm.busy?.let { BusyOverlay(it) }
    }

    // Save Project Dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Project") },
            text = {
                OutlinedTextField(
                    saveNameText,
                    { saveNameText = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    vm.saveProject(saveNameText)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } },
        )
    }

    // Load Project Dialog
    if (showLoadDialog) {
        val projects = remember { vm.listProjects() }
        AlertDialog(
            onDismissRequest = { showLoadDialog = false },
            title = { Text("Load Project") },
            text = {
                if (projects.isEmpty()) {
                    Text("No saved projects found.")
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        projects.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = {
                                    showLoadDialog = false
                                    vm.loadProject(p)
                                }) {
                                    Text(p, style = MaterialTheme.typography.bodyLarge)
                                }
                                IconButton(onClick = {
                                    ProjectStore.delete(context, p)
                                    showLoadDialog = false
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete project")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLoadDialog = false }) { Text("Close") } },
        )
    }

    // Export Resolve XML Dialog
    if (showResolveDialog) {
        AlertDialog(
            onDismissRequest = { showResolveDialog = false },
            title = { Text("Export for DaVinci Resolve") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Choose an empty folder. The timeline (.xml) and copies of your audio files are saved there. " +
                            "Copy the whole folder to your computer, then in Resolve use File > Import > Timeline."
                    )
                    OutlinedTextField(exportName, { exportName = it }, label = { Text("Timeline name") }, singleLine = true)
                    Text("Resolve timeline frame rate")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FrameRate.entries.forEach { r ->
                            FilterChip(selected = exportRate == r, onClick = { exportRate = r }, label = { Text(r.label) })
                        }
                    }
                    OutlinedTextField(
                        exportPath, { exportPath = it },
                        label = { Text("Where the folder will be on your computer (optional)") },
                        placeholder = { Text("e.g. D:/Projects/My Mix") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showResolveDialog = false; folderLauncher.launch(null) }) { Text("Choose folder") }
            },
            dismissButton = { TextButton(onClick = { showResolveDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FadePanel(vm: EditorViewModel, clip: Clip) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FadeControl("Fade in", clip.fadeIn, clip.length, Modifier.weight(1f), vm::beginFadeEdit) {
            vm.setFade(clip.id, fadeIn = it)
        }
        FadeControl("Fade out", clip.fadeOut, clip.length, Modifier.weight(1f), vm::beginFadeEdit) {
            vm.setFade(clip.id, fadeOut = it)
        }
    }
}

@Composable
private fun FadeControl(
    label: String,
    fade: Fade,
    clipLength: Long,
    modifier: Modifier,
    onStart: () -> Unit,
    onChange: (Fade) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val maxFrames = minOf(clipLength, 10L * SAMPLE_RATE).coerceAtLeast(1L).toFloat()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$label ${"%.2f".format(fade.frames / SAMPLE_RATE.toFloat())}s",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(92.dp),
        )
        Slider(
            value = fade.frames.toFloat().coerceAtMost(maxFrames),
            onValueChange = {
                if (!editing) { onStart(); editing = true }
                onChange(fade.copy(frames = it.toLong()))
            },
            onValueChangeFinished = { editing = false },
            valueRange = 0f..maxFrames,
            modifier = Modifier.weight(1f),
        )
        FadeCurve.entries.forEach { curve ->
            FilterChip(
                selected = fade.curve == curve,
                onClick = { if (fade.curve != curve) { onStart(); onChange(fade.copy(curve = curve)) } },
                label = { Text(curve.label) },
            )
        }
    }
}

@Composable
private fun ToolRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun IconBtn(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label)
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
