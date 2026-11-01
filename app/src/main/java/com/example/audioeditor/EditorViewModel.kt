package com.example.audioeditor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val player = Player()
    val preview = VideoPreview(app)
    private var nextId = 1L
    private fun newId() = nextId++

    init {
        viewModelScope.launch(Dispatchers.IO) { AudioDecoder.trimCache(ctx) }
    }

    // ------------------------------------------------------------ Settings

    /** Curve used for new fades and crossfades. Curved (Resolve's +3 dB) is the default. */
    var defaultCurve by mutableStateOf(
        if (prefs.getString(KEY_CURVE, null) == FadeCurve.LINEAR.name) FadeCurve.LINEAR else FadeCurve.CURVED
    ); private set

    /** Length of crossfades made with the crossfade button (Resolve's default is 1 second). */
    var crossfadeSeconds by mutableFloatStateOf(prefs.getFloat(KEY_XFADE, 1f)); private set

    fun updateDefaultCurve(c: FadeCurve) {
        defaultCurve = c
        prefs.edit().putString(KEY_CURVE, c.name).apply()
    }

    fun updateCrossfadeSeconds(s: Float) {
        crossfadeSeconds = s
        prefs.edit().putFloat(KEY_XFADE, s).apply()
    }

    // ------------------------------------------------------------ State the screen reads

    var sources by mutableStateOf<Map<Long, AudioSource>>(emptyMap()); private set
    var thumbnails by mutableStateOf<Map<Long, List<Thumb>>>(emptyMap()); private set
    var clips by mutableStateOf<List<Clip>>(emptyList()); private set
    var trackCount by mutableIntStateOf(2); private set
    var videoTrackCount by mutableIntStateOf(1); private set
    var mutedTracks by mutableStateOf<Set<Int>>(emptySet()); private set
    var overrideSelectedId by mutableStateOf<Long?>(null); private set
    var playhead by mutableLongStateOf(0L); private set
    var isPlaying by mutableStateOf(false); private set
    var snapEnabled by mutableStateOf(true)
    var samplesPerPx by mutableDoubleStateOf(SAMPLE_RATE / 100.0); private set   // zoom
    var scrollFrames by mutableDoubleStateOf(0.0); private set                   // horizontal scroll
    var viewWidthPx = 1000f
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var dragPreview by mutableStateOf<List<Clip>?>(null); private set
    var snapLine by mutableStateOf<Long?>(null); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var projectName by mutableStateOf("Untitled"); private set

    /** Name of the project file this timeline was last saved to or loaded from (null = never saved). */
    private var savedName: String? = null

    /** True when there are changes that haven't been saved. */
    var dirty by mutableStateOf(false); private set

    val hasVideo: Boolean get() = clips.any { it.video }

    // ------------------------------------------------------------ Lanes (video tracks on top, like Resolve)

    val laneCount: Int get() = videoTrackCount + trackCount

    fun laneOf(c: Clip): Int = if (c.video) videoTrackCount - 1 - c.track else videoTrackCount + c.track

    /** (isVideo, track) for a lane index. */
    fun laneKind(lane: Int): Pair<Boolean, Int> =
        if (lane < videoTrackCount) true to (videoTrackCount - 1 - lane) else false to (lane - videoTrackCount)

    // ------------------------------------------------------------ Selection

    /** The tapped clip if any; otherwise the clip under the playhead (audio before video, lowest track first). */
    val selectedClip: Clip?
        get() {
            overrideSelectedId?.let { oid -> clips.find { it.id == oid }?.let { return it } }
            return clips.filter { playhead >= it.start && playhead < it.end }
                .minWithOrNull(compareBy<Clip> { it.video }.thenBy { it.track })
        }

    /** A clip together with the clips linked to it (a video and its camera audio). */
    private fun group(c: Clip, list: List<Clip> = clips): List<Clip> =
        if (c.linkId == null) listOf(c) else list.filter { it.linkId == c.linkId }

    val selectedIds: Set<Long> get() = selectedClip?.let { s -> group(s).map { it.id }.toSet() } ?: emptySet()

    /** The audio clip that fades and crossfades apply to: the selection, or a selected video's own audio. */
    val fadeTarget: Clip?
        get() = selectedClip?.let { s -> if (!s.video) s else group(s).firstOrNull { !it.video } }

    val totalFrames: Long get() = clips.maxOfOrNull { it.end } ?: 0L

    // ------------------------------------------------------------ Undo / redo

    private data class Snapshot(val clips: List<Clip>, val trackCount: Int, val videoTrackCount: Int)
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private fun snapshot() = Snapshot(clips, trackCount, videoTrackCount)

    private fun refreshHistory() { canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }

    private fun pushUndo() {
        undoStack.addLast(snapshot())
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        refreshHistory()
        dirty = true
    }

    private fun clearHistory() { undoStack.clear(); redoStack.clear(); refreshHistory() }

    private fun applyClips(new: List<Clip>) {
        clips = new
        if (overrideSelectedId != null && new.none { it.id == overrideSelectedId }) overrideSelectedId = null
        if (isPlaying) player.updateMixer(mixer())
    }

    private fun mixer() = Mixer(sources, clips, mutedTracks)

    private fun commit(new: List<Clip>) { pushUndo(); applyClips(new) }

    private fun restore(s: Snapshot) {
        trackCount = s.trackCount
        videoTrackCount = s.videoTrackCount
        applyClips(s.clips)
        refreshHistory()
        dirty = true
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(s)
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(s)
    }

    // ------------------------------------------------------------ Import

    /** Decodes files two at a time (cached files load almost instantly). Results keep the input order. */
    private suspend fun decodeAll(items: List<Pair<Uri, Long>>, label: String): List<Result<AudioSource>> {
        val done = AtomicInteger()
        busy = "$label 0 of ${items.size}…"
        val io = Dispatchers.IO.limitedParallelism(2)
        return coroutineScope {
            items.map { (uri, id) ->
                async(io) {
                    val r = runCatching { AudioDecoder.decode(ctx, uri, id) }
                    busy = "$label ${done.incrementAndGet()} of ${items.size}…"
                    r
                }
            }.awaitAll()
        }
    }

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch {
            stop()
            for (uri in uris) {
                try {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
            }
            val results = decodeAll(uris.map { it to newId() }, "Importing")
            var working = clips
            var tracks = trackCount
            val failures = mutableListOf<String>()
            val explicitTrack = overrideSelectedId?.let { id -> clips.find { it.id == id } }
                ?.takeIf { !it.video && it.linkId == null }?.track

            /** First audio track holding only camera audio (or only recorder audio), adding one if needed. */
            fun pickTrack(camera: Boolean): Int {
                for (t in 0 until tracks) {
                    if (working.filter { !it.video && it.track == t }.all { (it.linkId != null) == camera }) return t
                }
                return if (tracks < MAX_AUDIO_TRACKS) tracks++ else 0
            }
            fun laneEnd(video: Boolean, track: Int) =
                working.filter { it.video == video && it.track == track }.maxOfOrNull { it.end } ?: 0L

            val newSources = mutableMapOf<Long, AudioSource>()
            for ((i, r) in results.withIndex()) {
                val src = r.getOrElse {
                    failures += "${AudioDecoder.displayName(ctx, uris[i])}: ${it.message ?: "unknown error"}"
                    null
                } ?: continue
                newSources[src.id] = src
                if (src.video != null) {
                    val camTrack = if (src.hasAudio) pickTrack(camera = true) else -1
                    val start = maxOf(laneEnd(true, 0), if (camTrack >= 0) laneEnd(false, camTrack) else 0L)
                    val link = if (camTrack >= 0) newId() else null
                    working = working + Clip(newId(), src.id, 0, start, 0L, src.frames, video = true, linkId = link)
                    if (camTrack >= 0) {
                        working = working + Clip(
                            newId(), src.id, camTrack, start, 0L, src.pcm!!.frames.coerceAtMost(src.frames),
                            Fade(curve = defaultCurve), Fade(curve = defaultCurve), linkId = link,
                        )
                    }
                } else {
                    val track = explicitTrack ?: pickTrack(camera = false)
                    working = working + Clip(
                        newId(), src.id, track, laneEnd(false, track), 0L, src.frames,
                        Fade(curve = defaultCurve), Fade(curve = defaultCurve),
                    )
                }
            }
            if (newSources.isNotEmpty()) {
                sources = sources + newSources
                pushUndo()
                trackCount = tracks
                applyClips(working)
                loadThumbnails(newSources.values)
            }
            busy = null
            if (failures.isNotEmpty()) message = "Couldn't import: " + failures.joinToString("; ")
        }
    }

    private fun loadThumbnails(list: Collection<AudioSource>) {
        for (src in list) {
            val info = src.video ?: continue
            viewModelScope.launch {
                val thumbs = withContext(Dispatchers.IO) { Thumbnails.generate(ctx, src.uri, src.frames, info) }
                if (sources[src.id] === src) thumbnails = thumbnails + (src.id to thumbs)
            }
        }
    }

    // ------------------------------------------------------------ Playback

    fun togglePlay() {
        if (isPlaying) stop() else play()
    }

    private fun play() {
        if (clips.isEmpty()) { message = "Import some media first"; return }
        if (playhead >= totalFrames) playhead = 0
        player.play(playhead, mixer())
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

    fun updatePreview() = preview.update(clips, sources, playhead, isPlaying)

    fun seek(frame: Long) {
        if (isPlaying) { player.stop(); isPlaying = false }
        playhead = frame.coerceAtLeast(0L)
    }

    // ------------------------------------------------------------ Editing

    fun select(id: Long?) { overrideSelectedId = id }

    private fun inside(c: Clip) = playhead > c.start && playhead < c.end

    fun cut() {
        stop()
        val sel = selectedClip
        val targets = if (sel != null && inside(sel)) group(sel).filter(::inside) else clips.filter(::inside)
        if (targets.isEmpty()) { message = "Move the playhead over a clip to cut it"; return }
        var new = clips
        for (t in targets) new = TimelineOps.split(new, t, playhead, ::newId)
        // The right-hand pieces of a linked video + audio become their own linked pair.
        for (link in targets.mapNotNull { it.linkId }.toSet()) {
            val fresh = newId()
            new = new.map { if (it.linkId == link && it.start >= playhead) it.copy(linkId = fresh) else it }
        }
        commit(new)
    }

    private fun rippleTrim(atStart: Boolean) {
        stop()
        val t = selectedClip ?: run { message = "Move the playhead over a clip first"; return }
        val members = group(t).filter(::inside)
        if (members.isEmpty()) { message = "Move the playhead inside the clip to trim it"; return }
        var new = clips
        for (m in members) {
            new = if (atStart) TimelineOps.rippleTrimStart(new, m, playhead) else TimelineOps.rippleTrimEnd(new, m, playhead)
        }
        commit(new)
        if (atStart) playhead = members.minOf { it.start }
        overrideSelectedId = t.id
    }

    fun rippleTrimStart() = rippleTrim(atStart = true)
    fun rippleTrimEnd() = rippleTrim(atStart = false)

    fun deleteSelected(ripple: Boolean) {
        val sel = selectedClip ?: run { message = "Tap a clip to select it first"; return }
        stop()
        var new = clips
        for (c in group(sel)) new = if (ripple) TimelineOps.rippleDelete(new, c) else TimelineOps.delete(new, c)
        commit(new)
    }

    fun crossfade() {
        stop()
        val t = fadeTarget ?: run { message = "Select or move the playhead over an audio clip to crossfade"; return }
        val duration = (crossfadeSeconds * SAMPLE_RATE).toLong()
        val result = TimelineOps.crossfade(clips, t, playhead, duration, defaultCurve) { id ->
            sources[id]?.let { it.pcm?.frames ?: it.frames } ?: 0L
        }
        when (result) {
            is TimelineOps.CrossfadeResult.Done -> commit(result.clips)
            TimelineOps.CrossfadeResult.NoHandles -> message =
                "There's no extra audio beyond this cut to blend. Trim the end of the first clip or the " +
                    "start of the second a little, then try again."
        }
    }

    fun addAudioTrack() {
        if (trackCount >= MAX_AUDIO_TRACKS) { message = "$MAX_AUDIO_TRACKS audio tracks is the maximum"; return }
        pushUndo()
        trackCount++
    }

    fun addVideoTrack() {
        if (videoTrackCount >= MAX_VIDEO_TRACKS) { message = "$MAX_VIDEO_TRACKS video tracks is the maximum"; return }
        pushUndo()
        videoTrackCount++
    }

    /** Mute or unmute an audio track (like Resolve's M button). Affects playback and export. */
    fun toggleMute(track: Int) {
        mutedTracks = if (track in mutedTracks) mutedTracks - track else mutedTracks + track
        dirty = true
        if (isPlaying) player.updateMixer(mixer())
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

    // ------------------------------------------------------------ Dragging clips (linked clips move together)

    private var dragOrigin: List<Clip>? = null
    private var dragLeadId = 0L

    fun beginDrag(id: Long) {
        stop()
        val c = clips.find { it.id == id } ?: return
        val g = group(c)
        dragOrigin = g
        dragLeadId = id
        dragPreview = g
        overrideSelectedId = id
    }

    fun dragTo(totalDxPx: Float, lane: Int, snapThresholdPx: Float) {
        val g = dragOrigin ?: return
        val lead = g.first { it.id == dragLeadId }
        var start = (lead.start + totalDxPx * samplesPerPx).toLong()
        var line: Long? = null
        if (snapEnabled) {
            val ids = g.map { it.id }.toSet()
            val edges = ArrayList<Long>()
            edges += 0L
            edges += playhead
            for (c in clips) if (c.id !in ids) { edges += c.start; edges += c.end }
            val (s, l) = TimelineOps.snap(start, lead.length, edges, (snapThresholdPx * samplesPerPx).toLong())
            start = s
            line = l
        }
        val delta = (start - lead.start).coerceAtLeast(-g.minOf { it.start })
        val (laneVideo, laneTrack) = laneKind(lane.coerceIn(0, laneCount - 1))
        val newTrack = if (laneVideo == lead.video) laneTrack else lead.track
        dragPreview = g.map { it.copy(start = it.start + delta, track = if (it.id == lead.id) newTrack else it.track) }
        snapLine = line
    }

    fun endDrag() {
        val p = dragPreview ?: return
        val o = dragOrigin
        cancelDrag()
        if (p == o) return
        val ids = p.map { it.id }.toSet()
        var new = clips.filter { it.id !in ids }
        for (c in p) new = TimelineOps.place(new, c, ::newId)
        commit(new)
    }

    fun cancelDrag() { dragPreview = null; dragOrigin = null; snapLine = null }

    // ------------------------------------------------------------ Waveform sync

    /**
     * Lines up each video (via its camera audio) with the recorder audio on the timeline, like Resolve's
     * "Auto Align Clips > Based on Waveform". With a video tapped, only that one is synced. A synced clip that
     * would land on top of another clip is moved to a free track (a new one is added if needed).
     */
    fun syncToRecorder() {
        stop()
        val chosen = overrideSelectedId?.let { id -> clips.find { it.id == id } }?.linkId
        val groups = clips.filter { it.linkId != null }.groupBy { it.linkId!! }
            .filter { (link, g) -> (chosen == null || link == chosen) && g.any { it.video } && g.any { !it.video } }
        val recorders = clips.filter { !it.video && it.linkId == null && sources[it.sourceId]?.pcm != null }
        if (groups.isEmpty()) { message = "Import a video file with sound first"; return }
        if (recorders.isEmpty()) { message = "Import the recorder's audio file first"; return }

        viewModelScope.launch {
            busy = "Matching waveforms…"
            val srcs = sources
            val matches = withContext(Dispatchers.Default) {
                val recEnv = recorders.associate { r -> r.id to AudioSync.envelope(srcs[r.sourceId]!!.pcm!!, r.sourceIn, r.length) }
                groups.map { (link, g) ->
                    val cam = g.first { !it.video }
                    val pcm = srcs[cam.sourceId]?.pcm
                    val best = pcm?.let { p ->
                        val camEnv = AudioSync.envelope(p, cam.sourceIn, cam.length)
                        recorders.mapNotNull { r -> AudioSync.findOffset(camEnv, recEnv[r.id]!!)?.let { r to it } }
                            .maxByOrNull { it.second.score }
                    }
                    Triple(link, cam, best)
                }
            }

            var new = clips
            var vTracks = videoTrackCount
            var aTracks = trackCount
            val problems = mutableListOf<String>()
            var synced = 0
            for ((link, cam, best) in matches) {
                val name = srcs[cam.sourceId]?.name ?: "clip"
                if (best == null) { problems += "$name (no match found)"; continue }
                val (rec, m) = best
                val g = new.filter { it.linkId == link }
                val ids = g.map { it.id }.toSet()
                val delta = rec.start + m.lagFrames - cam.start
                if (g.any { it.start + delta < 0 }) { problems += "$name (would start before 0:00)"; continue }
                val placed = g.map { it.copy(start = it.start + delta) }.map { c ->
                    val count = if (c.video) vTracks else aTracks
                    var track = (listOf(c.track) + (0 until count))
                        .firstOrNull { TimelineOps.laneFree(new, c.video, it, c.start, c.end, ids) }
                    if (track == null && c.video && vTracks < MAX_VIDEO_TRACKS) track = vTracks++
                    if (track == null && !c.video && aTracks < MAX_AUDIO_TRACKS) track = aTracks++
                    track?.let { c.copy(track = it) }
                }
                if (placed.any { it == null }) { problems += "$name (no free track)"; continue }
                new = new.filter { it.id !in ids } + placed.filterNotNull()
                synced++
            }
            if (synced > 0) {
                pushUndo()
                videoTrackCount = vTracks
                trackCount = aTracks
                applyClips(new)
            }
            busy = null
            message = buildString {
                append("Synced $synced of ${matches.size} video clip${if (matches.size == 1) "" else "s"}")
                if (problems.isNotEmpty()) append(". Not synced: ").append(problems.joinToString("; "))
                if (synced > 0) append(". Mute the camera audio track if you only want the recorder.")
            }
        }
    }

    // ------------------------------------------------------------ Zoom / scroll

    fun zoom(factor: Float, focusPx: Float) {
        val focus = scrollFrames + focusPx * samplesPerPx
        samplesPerPx = (samplesPerPx / factor).coerceIn(1.0, SAMPLE_RATE * 2.0)
        scrollFrames = (focus - focusPx * samplesPerPx).coerceAtLeast(0.0)
    }

    fun scrollByPx(dx: Float) {
        scrollFrames = (scrollFrames - dx * samplesPerPx).coerceAtLeast(0.0)
    }

    // ------------------------------------------------------------ Project management

    private fun projectData(name: String) = ProjectData(
        name, trackCount, playhead,
        sources.values.map { SavedSource(it.id, it.uri.toString(), it.name) },
        clips.map {
            SavedClip(
                it.id, it.sourceId, it.track, it.start, it.sourceIn, it.length,
                it.fadeIn.frames, it.fadeIn.curve.name, it.fadeOut.frames, it.fadeOut.curve.name,
                it.video, it.linkId,
            )
        },
        videoTrackCount, mutedTracks,
    )

    /** True if saving under [name] would replace a different, existing project. */
    fun wouldOverwrite(name: String): Boolean {
        val safe = ProjectStore.safeName(name)
        return safe != savedName && ProjectStore.exists(ctx, safe)
    }

    fun saveProject(name: String = projectName) {
        val safe = ProjectStore.safeName(name)
        try {
            ProjectStore.save(ctx, projectData(safe))
            projectName = safe
            savedName = safe
            dirty = false
            message = "Project '$safe' saved"
        } catch (e: Exception) {
            message = "Couldn't save: ${e.message}"
        }
    }

    /**
     * Called when the app goes to the background. Saves unsaved changes to the project's own file, or to
     * "Autosave" if it has never been saved, so it never overwrites a different project.
     */
    fun autoSave() {
        if (!dirty || clips.isEmpty()) return
        val target = savedName
        try {
            ProjectStore.save(ctx, projectData(target ?: AUTOSAVE_NAME))
            if (target != null) dirty = false
        } catch (_: Exception) {
        }
    }

    fun loadProject(name: String) {
        stop()
        viewModelScope.launch {
            busy = "Loading project '$name'…"
            try {
                val data = withContext(Dispatchers.IO) { ProjectStore.load(ctx, name) }
                    ?: throw IOException("Project not found")
                val results = decodeAll(data.sources.map { Uri.parse(it.uriString) to it.id }, "Loading media")
                val loaded = results.mapNotNull { it.getOrNull() }.associateBy { it.id }
                val missing = data.sources.filter { it.id !in loaded }.map { it.name }

                val loadedClips = data.clips.map { c ->
                    Clip(
                        id = c.id,
                        sourceId = c.sourceId,
                        track = c.track.coerceIn(0, (if (c.video) data.videoTrackCount else data.trackCount) - 1),
                        start = c.start,
                        sourceIn = c.sourceIn,
                        length = c.length,
                        fadeIn = Fade(c.fadeInFrames, parseCurve(c.fadeInCurve)),
                        fadeOut = Fade(c.fadeOutFrames, parseCurve(c.fadeOutCurve)),
                        video = c.video,
                        linkId = c.linkId,
                    )
                }
                sources = loaded
                thumbnails = emptyMap()
                trackCount = data.trackCount
                videoTrackCount = data.videoTrackCount
                mutedTracks = data.mutedTracks
                playhead = data.playhead
                scrollFrames = 0.0
                overrideSelectedId = null
                // New clips must never reuse an ID from the loaded project.
                nextId = maxOf(
                    nextId,
                    (loadedClips.flatMap { listOfNotNull(it.id, it.linkId) } + data.sources.map { it.id })
                        .maxOrNull()?.plus(1) ?: 1L,
                )
                clearHistory()
                applyClips(loadedClips)
                projectName = data.name
                savedName = ProjectStore.safeName(name)
                dirty = false
                loadThumbnails(loaded.values)
                message = if (missing.isEmpty()) "Loaded '${data.name}'"
                else "Loaded '${data.name}', but couldn't open: ${missing.joinToString(", ")}. " +
                    "Those clips will be silent."
            } catch (e: Exception) {
                message = "Failed to load project: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun newProject() {
        stop()
        clearHistory()
        sources = emptyMap()
        thumbnails = emptyMap()
        trackCount = 2
        videoTrackCount = 1
        mutedTracks = emptySet()
        applyClips(emptyList())
        overrideSelectedId = null
        playhead = 0L
        scrollFrames = 0.0
        projectName = "Untitled"
        savedName = null
        dirty = false
        message = "New project started"
    }

    fun listProjects(): List<String> = ProjectStore.listProjects(ctx)

    fun deleteProject(name: String) {
        ProjectStore.delete(ctx, name)
        if (savedName == ProjectStore.safeName(name)) { savedName = null; dirty = clips.isNotEmpty() }
    }

    // ------------------------------------------------------------ Export

    /** The Resolve frame rate closest to the first video clip's own rate (24 if there's no video). */
    fun suggestedFrameRate(): FrameRate =
        clips.firstNotNullOfOrNull { c -> sources[c.sourceId]?.video?.takeIf { c.video } }
            ?.let { FrameRate.closestTo(it.fps) } ?: FrameRate.F24

    fun exportWav(uri: Uri) {
        if (clips.isEmpty()) { message = "Nothing to export yet"; return }
        stop()
        val mixer = mixer()
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
        val timeline = Exporters.Timeline(name, clips, sources, trackCount, videoTrackCount, mutedTracks)
        viewModelScope.launch {
            busy = "Saving Resolve timeline…"
            try {
                withContext(Dispatchers.IO) { Exporters.writeResolvePackage(ctx, tree, timeline, rate, pathPrefix) }
                message = "Saved. In Resolve: File > Import > Timeline, then pick ${ProjectStore.safeName(name)}.xml"
            } catch (e: Exception) {
                message = "Export failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    override fun onCleared() {
        player.stop()
        preview.release()
    }

    private companion object {
        const val KEY_CURVE = "defaultCurve"
        const val KEY_XFADE = "crossfadeSeconds"
        const val AUTOSAVE_NAME = "Autosave"

        /** Older projects called the curved fade "EXPONENTIAL". */
        fun parseCurve(s: String) = if (s == FadeCurve.LINEAR.name) FadeCurve.LINEAR else FadeCurve.CURVED
    }
}
