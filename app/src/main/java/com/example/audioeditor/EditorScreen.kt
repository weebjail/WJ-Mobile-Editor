@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.audioeditor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerticalSplit
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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

private enum class Dialog { NONE, SAVE, RESOLVE, SETTINGS, CONFIRM_NEW, NEW_PROJECT }

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
    // .otio has no standard MIME type; a generic one keeps the file name exactly as given.
    val otioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.exportResolve(it, exportName.ifBlank { vm.projectName }) }
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
        snapshotFlow { listOf(vm.playhead, vm.playbackRunning, vm.clips, vm.sources, vm.luts) }.collect { vm.updatePreview() }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        // The app opens on the project list; the Project menu comes back to it.
        if (vm.showProjects) ProjectScreen(vm) { dialog = if (vm.dirty) Dialog.CONFIRM_NEW else Dialog.NEW_PROJECT }
        else Column(Modifier.fillMaxSize()) {
            // TOP BAR: media, project, tracks, sync, export, settings
            ToolRow(scrollable = true) {
                IconBtn(Icons.Default.LibraryAdd, tr("Import audio or video", "オーディオ・ビデオを読み込む")) {
                    importLauncher.launch(arrayOf("audio/*", "video/*"))
                }
                MenuBtn(Icons.Default.Folder, tr("Project", "プロジェクト")) { close ->
                    DropdownMenuItem(text = { Text(tr("New project", "新規プロジェクト")) }, onClick = {
                        close(); dialog = if (vm.dirty) Dialog.CONFIRM_NEW else Dialog.NEW_PROJECT
                    })
                    DropdownMenuItem(
                        text = { Text(tr("Save", "保存")) },
                        leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                        onClick = { close(); if (!vm.save()) dialog = Dialog.SAVE },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Save as…", "名前を付けて保存…")) },
                        leadingIcon = { Icon(Icons.Default.SaveAs, contentDescription = null) },
                        onClick = { close(); dialog = Dialog.SAVE },
                    )
                    DropdownMenuItem(text = { Text(tr("Open project…", "プロジェクトを開く…")) }, onClick = { close(); vm.openProjectScreen() })
                    DropdownMenuItem(text = { Text(tr("Import DaVinci Resolve timeline (OTIO)…", "DaVinci Resolveのタイムラインを読み込む（OTIO）…")) }, onClick = {
                        close(); timelineLauncher.launch(arrayOf("*/*"))
                    })
                }
                MenuBtn(Icons.AutoMirrored.Default.PlaylistAdd, tr("Tracks", "トラック")) { close ->
                    DropdownMenuItem(text = { Text(tr("Add audio track", "オーディオトラックを追加")) }, onClick = { close(); vm.addAudioTrack() })
                    DropdownMenuItem(text = { Text(tr("Add video track", "ビデオトラックを追加")) }, onClick = { close(); vm.addVideoTrack() })
                }
                IconBtn(
                    if (vm.snapEnabled) Icons.Default.GridOn else Icons.Default.GridOff,
                    if (vm.snapEnabled) tr("Snapping on", "スナップ: オン") else tr("Snapping off", "スナップ: オフ"),
                ) { vm.snapEnabled = !vm.snapEnabled }
                IconBtn(Icons.Default.Sync, tr("Sync video to recorder audio", "ビデオをレコーダーの音声に同期"), onClick = vm::syncToRecorder)
                ToggleBtn(
                    Icons.Default.Palette,
                    if (showGrade) tr("Colour controls: on", "カラー調整: オン") else tr("Colour controls: off", "カラー調整: オフ"),
                    showGrade,
                ) {
                    showGrade = !showGrade
                    if (showGrade && vm.gradeTarget == null) {
                        vm.message = tr("Tap a video clip to change its colour", "色を変えるにはビデオクリップをタップしてください")
                    }
                }
                MenuBtn(Icons.Default.IosShare, tr("Export", "書き出し")) { close ->
                    DropdownMenuItem(text = { Text(tr("Export audio (WAV)", "オーディオを書き出す（WAV）")) }, onClick = {
                        close(); wavLauncher.launch("${ProjectStore.safeName(vm.projectName)}.wav")
                    })
                    DropdownMenuItem(text = { Text(tr("Export DaVinci Resolve timeline (OTIO)", "DaVinci Resolveのタイムラインを書き出す（OTIO）")) }, onClick = {
                        close()
                        exportName = vm.projectName
                        dialog = Dialog.RESOLVE
                    })
                }
                IconBtn(Icons.Default.Settings, tr("Settings", "設定")) { dialog = Dialog.SETTINGS }
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
                IconBtn(Icons.AutoMirrored.Default.Undo, tr("Undo", "元に戻す"), enabled = vm.canUndo, onClick = vm::undo)
                IconBtn(Icons.AutoMirrored.Default.Redo, tr("Redo", "やり直す"), enabled = vm.canRedo, onClick = vm::redo)
                MarkerColorBtn(vm)
                FilledIconButton(onClick = vm::togglePlay) {
                    Icon(
                        if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (vm.isPlaying) tr("Pause", "一時停止") else tr("Play", "再生"),
                    )
                }
                Labeled(tr("Add marker (press again on a marker to edit it)", "マーカーを追加（マーカー上でもう一度押すと編集）")) {
                    IconButton(onClick = vm::markerButton) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = tr("Marker", "マーカー"),
                            tint = Color(vm.markerColor.argb),
                        )
                    }
                }
                IconBtn(Icons.Default.ContentCut, tr("Cut clip", "クリップをカット"), onClick = vm::cutSelected)
                IconBtn(Icons.Default.ContentCopy, tr("Copy clip", "クリップをコピー"), onClick = vm::copySelected)
                IconBtn(
                    Icons.Default.ContentPaste,
                    tr("Paste at the playhead (onto the paste tracks: tap a track name to choose)", "再生ヘッドに貼り付け（トラック名をタップして貼り付け先を選択）"),
                    enabled = vm.canPaste, onClick = vm::paste,
                )
            }
            // BOTTOM ROW 2: editing
            ToolRow {
                IconBtn(Icons.Default.DeleteSweep, tr("Ripple delete clip", "クリップをリップル削除")) { vm.deleteSelected(true) }
                IconBtn(Icons.Default.Delete, tr("Delete clip", "クリップを削除")) { vm.deleteSelected(false) }
                IconBtn(Icons.Default.FirstPage, tr("Ripple trim start to playhead", "始点を再生ヘッドまでリップルトリム"), onClick = vm::rippleTrimStart)
                IconBtn(Icons.Default.LastPage, tr("Ripple trim end to playhead", "終点を再生ヘッドまでリップルトリム"), onClick = vm::rippleTrimEnd)
                IconBtn(Icons.Default.VerticalSplit, tr("Split clip at playhead", "再生ヘッドでクリップを分割"), onClick = vm::cut)
                IconBtn(Icons.Default.JoinFull, tr("Crossfade", "クロスフェード"), onClick = vm::crossfade)
            }
        }

        vm.busy?.let { BusyOverlay(it) }
    }

    vm.pendingImport?.let { PlacementDialog(vm, it) }
    vm.otioMedia?.let { req ->
        AlertDialog(
            onDismissRequest = vm::cancelOtio,
            title = { Text(tr("Find the media", "メディアを探す")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val n = req.missing.size
                    Text(
                        tr(
                            "“${req.otio.name}” uses ${if (n == 1) "a file that isn't" else "$n files that aren't"} in this project yet. " +
                                "Select ${if (n == 1) "it" else "them"} on this phone (you can select several at once); " +
                                "they're matched by file name.",
                            "「${req.otio.name}」は、このプロジェクトにまだないファイルを${n}個使っています。" +
                                "このスマートフォン上でそれらを選択してください（複数同時に選べます）。ファイル名で照合されます。",
                        )
                    )
                    Text(req.missing.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { timelineMediaLauncher.launch(arrayOf("audio/*", "video/*")) }) { Text(tr("Select files", "ファイルを選択")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = vm::cancelOtio) { Text(tr("Cancel", "キャンセル")) }
                    TextButton(onClick = vm::otioSkipMissing) { Text(tr("Leave out", "除外する")) }
                }
            },
        )
    }

    when (dialog) {
        Dialog.NONE -> vm.editingMarker?.let { MarkerDialog(vm, it) }
        Dialog.SAVE -> SaveDialog(vm) { dialog = Dialog.NONE }
        Dialog.SETTINGS -> SettingsDialog(vm) { dialog = Dialog.NONE }
        Dialog.NEW_PROJECT -> NewProjectDialog(vm) { dialog = Dialog.NONE }
        Dialog.CONFIRM_NEW -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = { Text(tr("Start a new project?", "新しいプロジェクトを始めますか？")) },
            text = { Text(unsavedWarning(vm.projectName)) },
            confirmButton = { TextButton(onClick = { dialog = Dialog.NEW_PROJECT }) { Text(tr("Discard changes", "変更を破棄")) } },
            dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text(tr("Cancel", "キャンセル")) } },
        )
        Dialog.RESOLVE -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = {
                Text(tr("Export for DaVinci Resolve (OTIO)", "DaVinci Resolve用に書き出す（OTIO）"))
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        tr(
                            "Saves the timeline, with its markers, as an OpenTimelineIO (.otio) file, for Resolve 18.5 and later. ",
                            "タイムラインをマーカーも含めてOpenTimelineIO（.otio）ファイルとして保存します（Resolve 18.5以降）。",
                        ) + tr(
                            "Your media isn't copied: Resolve uses your original files. Copy them and the timeline file " +
                                "to your computer.",
                            "メディアはコピーされません。Resolveは元のファイルを使います。それらとタイムラインファイルを" +
                                "パソコンにコピーしてください。",
                        )
                    )
                    OutlinedTextField(exportName, { exportName = it }, label = { Text(tr("Timeline name", "タイムライン名")) }, singleLine = true)
                    Text(
                        tr("Timeline: ", "タイムライン: ") + "${vm.frameRate.label} fps, ${vm.timelineWidth}×${vm.timelineHeight}. " +
                            tr("You can change these in Settings.", "これらは設定で変更できます。"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        vm.resolveMediaFolder, vm::updateResolveMediaFolder,
                        label = { Text(tr("Media folder on your computer", "パソコン上のメディアフォルダー")) },
                        placeholder = { Text(tr("e.g. D:/Shoots/Day 1", "例: D:/Shoots/Day 1")) },
                        singleLine = true,
                    )
                    Text(
                        tr(
                            "With a folder: in Resolve use File > Import > Timeline and the clips link to the files in it.\n\n" +
                                "Without one: each file is named relative to the .otio, so keep them in the same folder. " +
                                "If Resolve doesn't find them, drag the media into the Media Pool first, then File > Import > Timeline " +
                                "with “Automatically import source clips into media pool” unticked. Resolve matches clips by file name.",
                            "フォルダーを指定した場合: Resolveで「ファイル > 読み込み > タイムライン」を使うと、クリップがそのフォルダー内のファイルにリンクされます。\n\n" +
                                "指定しない場合: 各ファイルは.otioからの相対パスになるため、同じフォルダーに置いてください。" +
                                "Resolveがファイルを見つけられない場合は、先にメディアをメディアプールにドラッグしてから、" +
                                "「ソースクリップをメディアプールに自動で読み込む」のチェックを外して「ファイル > 読み込み > タイムライン」を実行してください。" +
                                "Resolveはファイル名でクリップを照合します。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val used = vm.usedMediaNames()
                    if (used.isNotEmpty()) Text(tr("Files used: ", "使用ファイル: ") + used.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                val fileName = ProjectStore.safeName(exportName.ifBlank { vm.projectName })
                TextButton(onClick = {
                    dialog = Dialog.NONE
                    otioLauncher.launch("$fileName.otio")
                }) { Text(tr("Save .otio", ".otioを保存")) }
            },
            dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text(tr("Cancel", "キャンセル")) } },
        )
    }
}

private fun unsavedWarning(project: String) =
    tr("“$project” has unsaved changes. They will be lost.", "「$project」には保存されていない変更があります。変更は失われます。")

/**
 * Symbol button: stacked layers = ripple edits affect every (unlocked) track; highlighted when on.
 * Long-press shows its name, like the other buttons.
 */
@Composable
private fun RippleAllToggle(vm: EditorViewModel, modifier: Modifier) {
    val on = vm.rippleAllTracks
    val label = if (on) tr("Ripple all tracks: on", "全トラックをリップル: オン") else tr("Ripple all tracks: off", "全トラックをリップル: オフ")
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
        title = { Text(tr("Save as", "名前を付けて保存")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Project name", "プロジェクト名")) }, singleLine = true)
                if (overwrite) {
                    val safe = ProjectStore.safeName(name)
                    Text(
                        tr(
                            "A different project called “$safe” already exists and will be replaced.",
                            "「$safe」という別のプロジェクトがすでにあり、置き換えられます。",
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { close(); vm.saveProject(name) }) { Text(if (overwrite) tr("Replace", "置き換える") else tr("Save", "保存")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Cancel", "キャンセル")) } },
    )
}

/**
 * The project list: shown when the app opens, and from Project > Open. Tap a project to open it (it opens where
 * you left it), or start a new one. Opened from the editor, the back arrow returns to it.
 */
@Composable
private fun ProjectScreen(vm: EditorViewModel, newProject: () -> Unit) {
    // Looking up media sizes touches every file, so the list is read in the background.
    var projects by remember { mutableStateOf<List<ProjectStore.Info>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { projects = withContext(Dispatchers.IO) { vm.projectInfo() } }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmOpen by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    BackHandler(enabled = vm.canLeaveProjects, onBack = vm::closeProjectScreen)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.canLeaveProjects) {
                IconBtn(Icons.AutoMirrored.Default.ArrowBack, tr("Back to the editor", "エディターに戻る"), onClick = vm::closeProjectScreen)
            }
            Text(
                tr("Projects", "プロジェクト"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            FilledIconButton(onClick = newProject) {
                Icon(Icons.Default.Add, contentDescription = tr("New project", "新規プロジェクト"))
            }
        }
        val list = projects
        when {
            list == null -> Text(tr("Loading…", "読み込み中…"), Modifier.padding(16.dp))
            list.isEmpty() -> Text(
                tr("No saved projects yet. Tap + to start one.", "保存されたプロジェクトはまだありません。＋で新しく始めましょう。"),
                Modifier.padding(16.dp),
            )
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                list.forEach { info ->
                    val p = info.name
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (vm.dirty) confirmOpen = p else vm.loadProject(p) }
                            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                projectDetails(context, info),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { confirmDelete = p }) {
                            Icon(Icons.Default.Delete, contentDescription = tr("Delete project $p", "プロジェクト「$p」を削除"))
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Delete “$p”?", "「$p」を削除しますか？")) },
            text = {
                Text(tr(
                    "The project file is deleted permanently. Your media files are not affected.",
                    "プロジェクトファイルは完全に削除されます。メディアファイルには影響しません。",
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(p); refresh++; confirmDelete = null
                }) { Text(tr("Delete", "削除")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Cancel", "キャンセル")) } },
        )
    }
    confirmOpen?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmOpen = null },
            title = { Text(tr("Open “$p”?", "「$p」を開きますか？")) },
            text = { Text(unsavedWarning(vm.projectName)) },
            confirmButton = { TextButton(onClick = { confirmOpen = null; vm.loadProject(p) }) { Text(tr("Discard and open", "破棄して開く")) } },
            dismissButton = { TextButton(onClick = { confirmOpen = null }) { Text(tr("Cancel", "キャンセル")) } },
        )
    }
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
        1 -> tr(" · 1 file missing", " · 1個のファイルが見つかりません")
        else -> tr(" · ${info.missingMedia} files missing", " · ${info.missingMedia}個のファイルが見つかりません")
    }
    return tr("Opened $date\n$size of media", "最終使用: $date\nメディア: $size") + missing
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
    Text(tr("Timeline frame rate", "タイムラインのフレームレート"), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrameRate.entries.forEach { r ->
            FilterChip(selected = s.rate == r, onClick = { s.rate = r }, label = { Text(r.label) })
        }
    }
    Text(tr("Timeline resolution", "タイムラインの解像度"), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ResolutionPresets.forEach { (label, size) ->
            FilterChip(selected = s.isPreset(size), onClick = { s.pick(size) }, label = { Text(label) })
        }
        Row(
            Modifier.clickable { s.changeVertical(!s.vertical) }.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = s.vertical, onCheckedChange = { s.changeVertical(it) })
            Text(tr("Vertical", "縦向き"))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
        OutlinedTextField(
            s.width, { s.width = it.filter(Char::isDigit).take(4) }, label = { Text(tr("Width", "幅")) },
            singleLine = true, keyboardOptions = numbers, isError = s.w == null, modifier = Modifier.weight(1f),
        )
        Text("×")
        OutlinedTextField(
            s.height, { s.height = it.filter(Char::isDigit).take(4) }, label = { Text(tr("Height", "高さ")) },
            singleLine = true, keyboardOptions = numbers, isError = s.h == null, modifier = Modifier.weight(1f),
        )
    }
    if (!s.valid) {
        Text(
            tr(
                "Width and height must be between $MIN_RESOLUTION and $MAX_RESOLUTION.",
                "幅と高さは${MIN_RESOLUTION}〜${MAX_RESOLUTION}の範囲で指定してください。",
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NewProjectDialog(vm: EditorViewModel, close: () -> Unit) {
    var name by remember { mutableStateOf(tr("Untitled", "無題")) }
    val format = remember { FormatState(DEFAULT_FRAME_RATE, DEFAULT_WIDTH, DEFAULT_HEIGHT) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("New project", "新規プロジェクト")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Project name", "プロジェクト名")) }, singleLine = true)
                TimelineFormatFields(format)
                Text(
                    tr(
                        "Match your camera's frame rate. Both can be changed later in Settings.",
                        "カメラのフレームレートに合わせてください。どちらも後から設定で変更できます。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = format.valid,
                onClick = { close(); vm.newProject(name, format.rate, format.w!!, format.h!!) },
            ) { Text(tr("Create", "作成")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Cancel", "キャンセル")) } },
    )
}

@Composable
private fun SettingsDialog(vm: EditorViewModel, close: () -> Unit) {
    val format = remember { FormatState(vm.frameRate, vm.timelineWidth, vm.timelineHeight) }
    val context = LocalContext.current
    // While width or height is invalid the dialog stays open (Done is disabled), so no change is silently lost.
    val done = {
        if (format.valid) {
            vm.setTimelineFormat(format.rate, format.w!!, format.h!!)
            close()
        }
    }
    AlertDialog(
        onDismissRequest = done,
        title = { Text(tr("Settings", "設定")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("This project", "このプロジェクト"), style = MaterialTheme.typography.titleMedium)
                TimelineFormatFields(format)
                Text(tr("Editing", "編集"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Default fade curve", "標準のフェードカーブ"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = vm.defaultCurve == FadeCurve.CURVED,
                        onClick = { vm.updateDefaultCurve(FadeCurve.CURVED) },
                        label = { Text(tr("Exponential (+3 dB)", "指数（+3 dB）")) },
                    )
                    FilterChip(
                        selected = vm.defaultCurve == FadeCurve.LINEAR,
                        onClick = { vm.updateDefaultCurve(FadeCurve.LINEAR) },
                        label = { Text(tr("Linear (0 dB)", "リニア（0 dB）")) },
                    )
                }
                Text(
                    tr(
                        "Applies to all fades and crossfades in the project. Exponential matches Resolve's default “Cross Fade +3 dB”, " +
                            "which keeps the volume steady through a crossfade.",
                        "プロジェクト内のすべてのフェードとクロスフェードに適用されます。指数はResolveの標準「クロスフェード +3 dB」と同じで、" +
                            "クロスフェード中の音量を一定に保ちます。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    tr("Crossfade length: ", "クロスフェードの長さ: ") + "${"%.2f".format(vm.crossfadeSeconds)} " + tr("s", "秒"),
                    style = MaterialTheme.typography.titleSmall,
                )
                // Same curve as the fade sliders: 0 to 5 s with 1 s in the middle (shortest 0.05 s).
                Slider(
                    value = fadeSliderPosSeconds(vm.crossfadeSeconds.toDouble()),
                    onValueChange = {
                        vm.updateCrossfadeSeconds((Math.round(fadeSliderSeconds(it) * 20) / 20f).coerceIn(0.05f, MAX_FADE_SECONDS.toFloat()))
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("Pause playback when the playhead is moved", "再生ヘッドを動かしたら再生を一時停止"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = vm.pauseOnSeek, onCheckedChange = vm::updatePauseOnSeek)
                }
                Text(
                    tr(
                        "When off, playback carries on from wherever you put the playhead.",
                        "オフのときは、再生ヘッドを置いた位置から再生が続きます。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                // Each language is written in its own script, so it can be found whichever one is showing.
                Text(tr("Language", "言語") + " / Language", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Language.entries.forEach { l ->
                        FilterChip(
                            selected = I18n.language == l,
                            onClick = { I18n.set(context, l) },
                            label = { Text(l.label) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = done, enabled = format.valid) { Text(tr("Done", "完了")) } },
    )
}

@Composable
private fun FadePanel(vm: EditorViewModel, clip: Clip) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Over a crossfade, one slider sets the whole transition (both clips' sides change together).
        val xf = vm.crossfadeAtPlayhead
        if (xf != null) {
            FadeControl(
                tr("Crossfade", "クロスフェード"), xf.frames, vm.maxCrossfadeFrames(xf), Modifier.weight(1f),
                onStart = { vm.beginCrossfadeEdit(xf) }, onEnd = vm::endCrossfadeEdit,
            ) { vm.setCrossfadeLength(xf, it) }
        } else {
            FadeControl(tr("Fade In", "フェードイン"), clip.fadeIn.frames, clip.length - clip.fadeOut.frames, Modifier.weight(1f), vm::beginFadeEdit) {
                vm.setFade(clip.id, fadeIn = clip.fadeIn.copy(frames = it))
            }
            FadeControl(tr("Fade Out", "フェードアウト"), clip.fadeOut.frames, clip.length - clip.fadeIn.frames, Modifier.weight(1f), vm::beginFadeEdit) {
                vm.setFade(clip.id, fadeOut = clip.fadeOut.copy(frames = it))
            }
        }
    }
}

@Composable
private fun FadeControl(
    label: String,
    frames: Long,
    available: Long,
    modifier: Modifier,
    onStart: () -> Unit,
    onEnd: () -> Unit = {},
    onChange: (Long) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val maxFrames = minOf(available, MAX_FADE_SECONDS * SAMPLE_RATE).coerceAtLeast(1L)
    // The slider is exponential: the middle is 1s, the far end 5s, so short fades get most of the travel.
    val maxPos = fadeSliderPos(maxFrames)
    Column(modifier) {
        Text(
            "$label ${"%.2f".format(frames / SAMPLE_RATE.toFloat())}" + tr("s", "秒"),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = fadeSliderPos(frames).coerceAtMost(maxPos),
            onValueChange = {
                if (!editing) { onStart(); editing = true }
                onChange(fadeSliderFrames(it).coerceAtMost(maxFrames))
            },
            onValueChangeFinished = { editing = false; onEnd() },
            valueRange = 0f..maxPos,
        )
    }
}

// Fade slider curve: seconds = (16^pos - 1) / 3, so pos 0 -> 0s, 0.5 -> 1s (the middle), 1 -> 5s.
private fun fadeSliderSeconds(pos: Float): Double = (Math.pow(16.0, pos.toDouble()) - 1.0) / 3.0

private fun fadeSliderFrames(pos: Float): Long = (fadeSliderSeconds(pos) * SAMPLE_RATE).roundToLong().coerceAtLeast(0L)

private fun fadeSliderPosSeconds(seconds: Double): Float = (ln(seconds * 3.0 + 1.0) / ln(16.0)).toFloat()

private fun fadeSliderPos(frames: Long): Float = fadeSliderPosSeconds(frames.toDouble() / SAMPLE_RATE)

/** Asked when importing onto a timeline that already has clips: where the new media (or timeline) should go. */
@Composable
private fun PlacementDialog(vm: EditorViewModel, req: EditorViewModel.PendingImport) {
    val timeline = req is EditorViewModel.PendingImport.Timeline
    val options = listOf(
        Triple(Placement.PLAYHEAD, Icons.Default.MyLocation, tr("At the playhead", "再生ヘッドの位置") to
            tr("Replaces whatever is there, like dropping a clip in Resolve", "Resolveでクリップをドロップしたときのように、そこにあるものを置き換えます")),
        Triple(Placement.END, Icons.AutoMirrored.Default.KeyboardTab, tr("At the end of the timeline", "タイムラインの末尾") to
            tr("After the last clip", "最後のクリップの後ろ")),
        Triple(
            Placement.NEW_TRACK, Icons.AutoMirrored.Default.PlaylistAdd,
            (if (timeline) tr("On new tracks", "新しいトラック") else tr("On a new track", "新しいトラック")) to
                tr("Added below the existing tracks, starting at the playhead", "既存のトラックの下に、再生ヘッドの位置から追加します"),
        ),
    )
    AlertDialog(
        onDismissRequest = { vm.choosePlacement(null) },
        title = {
            Text(
                when (req) {
                    is EditorViewModel.PendingImport.Timeline -> tr("Where should “${req.otio.name}” go?", "「${req.otio.name}」をどこに配置しますか？")
                    is EditorViewModel.PendingImport.Media ->
                        if (req.uris.size == 1) tr("Where should the file go?", "ファイルをどこに配置しますか？")
                        else tr("Where should the files go?", "ファイルをどこに配置しますか？")
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
        dismissButton = { TextButton(onClick = { vm.choosePlacement(null) }) { Text(tr("Cancel", "キャンセル")) } },
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
                FilterChip(selected = g.lut == null, onClick = { vm.changeGrade(clip.id, g.copy(lut = null)) }, label = { Text(tr("No LUT", "LUTなし")) })
                // A LUT the project uses but that isn't loaded on this phone is still listed, marked as missing.
                val names = (vm.luts.keys + listOfNotNull(g.lut)).distinct().sortedBy { it.lowercase() }
                names.forEach { name ->
                    FilterChip(
                        selected = g.lut == name,
                        onClick = { vm.changeGrade(clip.id, g.copy(lut = name)) },
                        label = { Text(if (name in vm.luts) name else name + tr(" (missing)", "（見つかりません）")) },
                    )
                }
            }
            IconBtn(Icons.Default.Add, tr("Load a LUT (.cube file)", "LUTを読み込む（.cubeファイル）"), onClick = loadLut)
            IconBtn(Icons.Default.RestartAlt, tr("Reset colour", "カラーをリセット"), enabled = !g.isNeutral) { vm.changeGrade(clip.id, Grade()) }
            MenuBtn(Icons.Default.MoreVert, tr("More colour options", "その他のカラー設定")) { close ->
                DropdownMenuItem(
                    text = { Text(tr("Copy to all clips from this file", "このファイルのすべてのクリップにコピー")) },
                    onClick = { close(); vm.copyGrade(clip, all = false) },
                )
                DropdownMenuItem(
                    text = { Text(tr("Copy to all video clips", "すべてのビデオクリップにコピー")) },
                    onClick = { close(); vm.copyGrade(clip, all = true) },
                )
                g.lut?.takeIf { it in vm.luts }?.let { name ->
                    DropdownMenuItem(
                        text = { Text(tr("Remove LUT “$name” from the app", "LUT「$name」をアプリから削除")) },
                        onClick = { close(); vm.deleteLut(name) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GradeSlider(tr("Exposure", "露出"), g.exposure, -2f..2f, "%+.1f".format(g.exposure), Modifier.weight(1f), vm::beginGradeEdit) {
                vm.setGrade(clip.id, g.copy(exposure = (it * 10).toInt() / 10f))
            }
            GradeSlider(tr("Highlights", "ハイライト"), g.highlights, -1f..1f, "%+d".format((g.highlights * 100).toInt()), Modifier.weight(1f), vm::beginGradeEdit) {
                vm.setGrade(clip.id, g.copy(highlights = (it * 100).toInt() / 100f))
            }
            GradeSlider(tr("Shadows", "シャドウ"), g.shadows, -1f..1f, "%+d".format((g.shadows * 100).toInt()), Modifier.weight(1f), vm::beginGradeEdit) {
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

/** A round colour swatch; ringed when [selected]. */
@Composable
private fun Swatch(color: MarkerColor, size: androidx.compose.ui.unit.Dp, selected: Boolean = false) {
    Box(
        Modifier.size(size)
            .background(Color(color.argb), CircleShape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color.Black.copy(alpha = 0.4f), CircleShape)
    )
}

/** The smaller button beside play: picks the colour for new markers, like the colour menu by Resolve's marker button. */
@Composable
private fun MarkerColorBtn(vm: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    val label = tr("Marker colour: ", "マーカーの色: ") + vm.markerColor.label
    Box {
        Labeled(label) {
            IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) { Swatch(vm.markerColor, 18.dp) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MarkerColor.entries.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label) },
                    leadingIcon = { Swatch(c, 18.dp, selected = c == vm.markerColor) },
                    onClick = { open = false; vm.updateMarkerColor(c) },
                )
            }
        }
    }
}

/** Opened by pressing the marker button on a marker, as in Resolve: name, note, colour, or delete it. */
@Composable
private fun MarkerDialog(vm: EditorViewModel, marker: Marker) {
    var name by remember(marker.id) { mutableStateOf(marker.name) }
    var note by remember(marker.id) { mutableStateOf(marker.note) }
    var color by remember(marker.id) { mutableStateOf(marker.color) }
    AlertDialog(
        onDismissRequest = vm::closeMarker,
        title = { Text(tr("Marker at ", "マーカー ") + formatTime(marker.frame)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name", "名前")) }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text(tr("Notes", "メモ")) }, minLines = 2)
                Text(tr("Colour: ", "色: ") + color.label, style = MaterialTheme.typography.titleSmall)
                // Two rows of eight, Resolve's order.
                MarkerColor.entries.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c ->
                            Box(Modifier.clickable { color = c }.padding(2.dp)) { Swatch(c, 24.dp, selected = c == color) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.updateMarker(marker.copy(name = name, note = note, color = color)) }) { Text(tr("Done", "完了")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.deleteMarker(marker.id) }) { Text(tr("Delete", "削除"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = vm::closeMarker) { Text(tr("Cancel", "キャンセル")) }
            }
        },
    )
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
    // Fixed rows use slightly smaller (44dp) touch targets so eight buttons fit across a 360dp-wide phone.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (scrollable) 48.dp else 44.dp) {
        Row(
            Modifier.fillMaxWidth()
                .let { if (scrollable) it.horizontalScroll(rememberScrollState()) else it }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = if (scrollable) Arrangement.spacedBy(2.dp) else Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
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
