package com.example.audioeditor

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val pcmDir = File(app.cacheDir, "pcm").apply { deleteRecursively(); mkdirs() }
    private val player = Player()
    private var nextId = 1L
    private fun newId() = nextId++

    // ------------------------------------------------------------ State the screen reads

    var sources by mutableStateOf<Map<Long, AudioSource>>(emptyMap()); private set
    var clips by mutableStateOf<List<Clip>>(emptyList()); private set
    var trackCount by mutableIntStateOf(2); private set
    var overrideSelectedId by mutableStateOf<Long?>(null); private set
    var playhead by mutableLongStateOf(0L); private set
    var isPlaying by mutableStateOf(false); private set
    var snapEnabled by mutableStateOf(true)
    var samplesPerPx by mutableDoubleStateOf(SAMPLE_RATE / 100.0); private set   // zoom
    var scrollFrames by mutableDoubleStateOf(0.0); private set                   // horizontal scroll
    var viewWidthPx = 1000f
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var dragPreview by mutableStateOf<Clip?>(null); private set
    var snapLine by mutableStateOf<Long?>(null); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var projectName by mutableStateOf("My Mix")

    /** Automatically selects clip under playhead unless user explicitly tapped a clip to override. */
    val selectedClip: Clip?
        get() {
            val override = overrideSelectedId?.let { oid -> clips.find { it.id == oid } }
            if (override != null) return override
            return clips.filter { playhead >= it.start && playhead < it.end }.minByOrNull { it.track }
        }

    val selectedId: Long? get() = selectedClip?.id
    val totalFrames: Long get() = clips.maxOfOrNull { it.end } ?: 0L

    // ------------------------------------------------------------ Undo / redo

    private data class Snapshot(val clips: List<Clip>, val trackCount: Int)
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private fun refreshHistory() { canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }

    private fun pushUndo() {
        undoStack.addLast(Snapshot(clips, trackCount))
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        refreshHistory()
    }

    private fun applyClips(new: List<Clip>) {
        clips = new
        if (overrideSelectedId != null && new.none { it.id == overrideSelectedId }) overrideSelectedId = null
        if (isPlaying) player.updateMixer(Mixer(sources, new))
    }

    private fun commit(new: List<Clip>) { pushUndo(); applyClips(new) }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(clips, trackCount))
        trackCount = s.trackCount
        applyClips(s.clips)
        refreshHistory()
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snapshot(clips, trackCount))
        trackCount = s.trackCount
        applyClips(s.clips)
        refreshHistory()
    }

    // ------------------------------------------------------------ Import

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch {
            stop()
            val track = selectedClip?.track ?: 0
            var newClips = clips
            val failures = mutableListOf<String>()
            for ((i, uri) in uris.withIndex()) {
                busy = "Importing ${i + 1} of ${uris.size}…"
                try {
                    try {
                        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                    }
                    val id = newId()
                    val src = withContext(Dispatchers.IO) { AudioDecoder.decode(ctx, uri, id, pcmDir) }
                    sources = sources + (id to src)
                    val start = newClips.filter { it.track == track }.maxOfOrNull { it.end } ?: 0L
                    newClips = newClips + Clip(newId(), id, track, start, 0L, src.frames)
                } catch (e: Exception) {
                    failures += (e.message ?: "unknown error")
                }
            }
            if (newClips !== clips) commit(newClips)
            busy = null
            if (failures.isNotEmpty()) message = "Couldn't import: " + failures.joinToString("; ")
        }
    }

    // ------------------------------------------------------------ Playback

    fun togglePlay() {
        if (isPlaying) stop() else play()
    }

    private fun play() {
        if (clips.isEmpty()) { message = "Import some audio first"; return }
        if (playhead >= totalFrames) playhead = 0
        player.play(playhead, Mixer(sources, clips))
        isPlaying = true
    }

    fun stop() {
        if (!isPlaying) return
        playhead = player.position()
        player.stop()
        isPlaying = false
    }

    /** Called every screen frame while playing. */
    fun tickPlayback() {
        if (!isPlaying) return
        val p = player.position()
        playhead = p
        if (p >= totalFrames) {
            player.stop(); isPlaying = false; playhead = totalFrames
        }
        val x = (playhead - scrollFrames) / samplesPerPx
        if (x > viewWidthPx * 0.85 || x < 0) {
            scrollFrames = (playhead - viewWidthPx * 0.15 * samplesPerPx).coerceAtLeast(0.0)
        }
    }

    fun seek(frame: Long) {
        if (isPlaying) { player.stop(); isPlaying = false }
        playhead = frame.coerceAtLeast(0L)
    }

    // ------------------------------------------------------------ Editing

    fun select(id: Long?) { overrideSelectedId = id }

    /** The selected clip if the playhead is over it, otherwise the clip under the playhead. */
    private fun targetClip(): Clip? = selectedClip

    fun cut() {
        stop()
        val sel = selectedClip
        val targets = if (sel != null && playhead > sel.start && playhead < sel.end) listOf(sel)
        else clips.filter { playhead > it.start && playhead < it.end }
        if (targets.isEmpty()) { message = "Move the playhead over a clip to cut it"; return }
        var new = clips
        for (t in targets) new = TimelineOps.split(new, t, playhead, ::newId)
        commit(new)
    }

    fun rippleTrimStart() {
        stop()
        val t = targetClip() ?: run { message = "Move the playhead over a clip first"; return }
        commit(TimelineOps.rippleTrimStart(clips, t, playhead))
        playhead = t.start
        overrideSelectedId = t.id
    }

    fun rippleTrimEnd() {
        stop()
        val t = targetClip() ?: run { message = "Move the playhead over a clip first"; return }
        commit(TimelineOps.rippleTrimEnd(clips, t, playhead))
        overrideSelectedId = t.id
    }

    fun deleteSelected(ripple: Boolean) {
        val sel = selectedClip ?: run { message = "Tap a clip to select it first"; return }
        stop()
        commit(if (ripple) TimelineOps.rippleDelete(clips, sel) else TimelineOps.delete(clips, sel))
    }

    fun crossfade() {
        stop()
        val t = targetClip() ?: run { message = "Select or move playhead over a clip to crossfade"; return }
        commit(TimelineOps.crossfade(clips, t))
    }

    fun addTrack() {
        if (trackCount >= 8) { message = "8 tracks is the maximum for now"; return }
        pushUndo()
        trackCount++
    }

    /** Call once when a fade slider drag starts, so the whole drag is one undo step. */
    fun beginFadeEdit() = pushUndo()

    fun setFade(clipId: Long, fadeIn: Fade? = null, fadeOut: Fade? = null) {
        applyClips(clips.map {
            if (it.id != clipId) it else {
                var c = it
                if (fadeIn != null) c = c.copy(fadeIn = fadeIn)
                if (fadeOut != null) c = c.copy(fadeOut = fadeOut)
                c.withValidFades()
            }
        })
    }

    // ------------------------------------------------------------ Dragging clips

    private var dragOrigin: Clip? = null

    fun beginDrag(id: Long) {
        stop()
        val c = clips.find { it.id == id } ?: return
        dragOrigin = c
        dragPreview = c
        overrideSelectedId = id
    }

    fun dragTo(totalDxPx: Float, track: Int, snapThresholdPx: Float) {
        val o = dragOrigin ?: return
        var start = (o.start + totalDxPx * samplesPerPx).toLong().coerceAtLeast(0L)
        var line: Long? = null
        if (snapEnabled) {
            val edges = ArrayList<Long>()
            edges += 0L
            edges += playhead
            for (c in clips) if (c.id != o.id) { edges += c.start; edges += c.end }
            val (s, l) = TimelineOps.snap(start, o.length, edges, (snapThresholdPx * samplesPerPx).toLong())
            start = s.coerceAtLeast(0L)
            line = l
        }
        dragPreview = o.copy(start = start, track = track.coerceIn(0, trackCount - 1))
        snapLine = line
    }

    fun endDrag() {
        val p = dragPreview ?: return
        val o = dragOrigin
        cancelDrag()
        if (p == o) return
        commit(TimelineOps.place(clips.filter { it.id != p.id }, p, ::newId))
    }

    fun cancelDrag() { dragPreview = null; dragOrigin = null; snapLine = null }

    // ------------------------------------------------------------ Zoom / scroll

    fun zoom(factor: Float, focusPx: Float) {
        val focus = scrollFrames + focusPx * samplesPerPx
        samplesPerPx = (samplesPerPx / factor).coerceIn(1.0, SAMPLE_RATE * 2.0)
        scrollFrames = (focus - focusPx * samplesPerPx).coerceAtLeast(0.0)
    }

    fun scrollByPx(dx: Float) {
        scrollFrames = (scrollFrames - dx * samplesPerPx).coerceAtLeast(0.0)
    }

    // ------------------------------------------------------------ Project Management

    fun saveProject(name: String = projectName) {
        val projName = name.ifBlank { "My Mix" }
        projectName = projName
        val savedSources = sources.values.map { SavedSource(it.id, it.uri.toString(), it.name) }
        val savedClips = clips.map {
            SavedClip(
                it.id, it.sourceId, it.track, it.start, it.sourceIn, it.length,
                it.fadeIn.frames, it.fadeIn.curve.name, it.fadeOut.frames, it.fadeOut.curve.name,
            )
        }
        ProjectStore.save(ctx, ProjectData(projName, trackCount, playhead, savedSources, savedClips))
        message = "Project '$projName' saved"
    }

    fun loadProject(name: String) {
        stop()
        viewModelScope.launch {
            busy = "Loading project '$name'…"
            try {
                val data = withContext(Dispatchers.IO) { ProjectStore.load(ctx, name) }
                    ?: throw IOException("Project not found")
                projectName = data.name
                trackCount = data.trackCount
                playhead = data.playhead

                val loadedSources = mutableMapOf<Long, AudioSource>()
                for (s in data.sources) {
                    val uri = Uri.parse(s.uriString)
                    try {
                        val src = withContext(Dispatchers.IO) { AudioDecoder.decode(ctx, uri, s.id, pcmDir) }
                        loadedSources[s.id] = src
                    } catch (_: Exception) {
                    }
                }
                sources = loadedSources

                val loadedClips = data.clips.map { c ->
                    Clip(
                        id = c.id,
                        sourceId = c.sourceId,
                        track = c.track,
                        start = c.start,
                        sourceIn = c.sourceIn,
                        length = c.length,
                        fadeIn = Fade(c.fadeInFrames, try { FadeCurve.valueOf(c.fadeInCurve) } catch (_: Exception) { FadeCurve.LINEAR }),
                        fadeOut = Fade(c.fadeOutFrames, try { FadeCurve.valueOf(c.fadeOutCurve) } catch (_: Exception) { FadeCurve.LINEAR }),
                    )
                }
                undoStack.clear()
                redoStack.clear()
                refreshHistory()
                applyClips(loadedClips)
                message = "Loaded '$name'"
            } catch (e: Exception) {
                message = "Failed to load project: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun newProject() {
        stop()
        projectName = "Untitled Mix"
        undoStack.clear()
        redoStack.clear()
        refreshHistory()
        sources = emptyMap()
        applyClips(emptyList())
        overrideSelectedId = null
        playhead = 0L
        message = "New project started"
    }

    fun listProjects(): List<String> = ProjectStore.listProjects(ctx)

    // ------------------------------------------------------------ Export

    fun exportWav(uri: Uri) {
        if (clips.isEmpty()) { message = "Nothing to export yet"; return }
        stop()
        val mixer = Mixer(sources, clips)
        viewModelScope.launch {
            busy = "Exporting audio…"
            try {
                withContext(Dispatchers.IO) {
                    val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Couldn't open the file")
                    out.use { Exporters.writeWav(it, mixer) }
                }
                message = "Audio exported"
            } catch (e: Exception) {
                message = "Export failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun exportResolveXml(tree: Uri, name: String, rate: FrameRate, pathPrefix: String) {
        if (clips.isEmpty()) { message = "Nothing to export yet"; return }
        stop()
        val snapshotClips = clips
        val snapshotSources = sources
        val tracks = trackCount
        viewModelScope.launch {
            busy = "Saving Resolve timeline…"
            try {
                withContext(Dispatchers.IO) {
                    Exporters.writeResolvePackage(ctx, tree, name, snapshotClips, snapshotSources, tracks, rate, pathPrefix)
                }
                message = "Saved. In Resolve: File > Import > Timeline, then pick $name.xml"
            } catch (e: Exception) {
                message = "Export failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    override fun onCleared() {
        player.stop()
    }
}
