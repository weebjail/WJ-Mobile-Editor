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

/** Where imported media or a timeline goes when the timeline already has clips. */
enum class Placement { PLAYHEAD, END, NEW_TRACK }

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

    /**
     * The fade curve is a setting, not an edit: every fade and crossfade always uses it. Changing it switches
     * all clips over (not an undo step), and undo/redo/load re-apply it, so the project never ends up mixed.
     */
    fun updateDefaultCurve(c: FadeCurve) {
        defaultCurve = c
        prefs.edit().putString(KEY_CURVE, c.name).apply()
        val new = withCurve(clips)
        if (new.zip(clips).any { (n, o) -> n.audibleFades() != o.audibleFades() }) dirty = true
        applyClips(new)
    }

    private fun withCurve(list: List<Clip>): List<Clip> = list.map {
        if (it.fadeIn.curve == defaultCurve && it.fadeOut.curve == defaultCurve) it
        else it.copy(fadeIn = it.fadeIn.copy(curve = defaultCurve), fadeOut = it.fadeOut.copy(curve = defaultCurve))
    }

    /** Fades that actually change the sound (zero-length fades don't). */
    private fun Clip.audibleFades() = listOf(fadeIn.takeIf { it.frames > 0 }, fadeOut.takeIf { it.frames > 0 })

    /** When on, ripple delete and ripple trims remove the time from every track, not just the clip's own. */
    var rippleAllTracks by mutableStateOf(prefs.getBoolean(KEY_RIPPLE_ALL, false)); private set

    fun updateRippleAllTracks(on: Boolean) {
        rippleAllTracks = on
        prefs.edit().putBoolean(KEY_RIPPLE_ALL, on).apply()
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
    /** Locked audio / video tracks (like Resolve's lock button): their clips can't be selected or changed. */
    var lockedAudio by mutableStateOf<Set<Int>>(emptySet()); private set
    var lockedVideo by mutableStateOf<Set<Int>>(emptySet()); private set
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

    /** Timeline frame rate and resolution, chosen when the project is created (editable in Settings). */
    var frameRate by mutableStateOf(DEFAULT_FRAME_RATE); private set
    var timelineWidth by mutableIntStateOf(DEFAULT_WIDTH); private set
    var timelineHeight by mutableIntStateOf(DEFAULT_HEIGHT); private set

    fun setTimelineFormat(rate: FrameRate, width: Int, height: Int) {
        if (rate == frameRate && width == timelineWidth && height == timelineHeight) return
        frameRate = rate
        timelineWidth = width
        timelineHeight = height
        dirty = true
    }

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

    // ------------------------------------------------------------ Track locks

    fun isLocked(video: Boolean, track: Int): Boolean = track in (if (video) lockedVideo else lockedAudio)

    /** Edits that span tracks (ripple all tracks) leave clips on locked tracks alone. */
    private fun onUnlockedTrack(c: Clip): Boolean = !isLocked(c.video, c.track)

    /** A clip can be edited when neither it nor any clip linked to it is on a locked track. */
    fun isEditable(c: Clip): Boolean = group(c).none { isLocked(it.video, it.track) }

    fun toggleLock(video: Boolean, track: Int) {
        if (video) lockedVideo = if (track in lockedVideo) lockedVideo - track else lockedVideo + track
        else lockedAudio = if (track in lockedAudio) lockedAudio - track else lockedAudio + track
        dirty = true
    }

    // ------------------------------------------------------------ Selection

    /**
     * The tapped clip if any; otherwise the clip under the playhead (audio before video, lowest track first).
     * Clips on locked tracks are never selected.
     */
    val selectedClip: Clip?
        get() {
            overrideSelectedId?.let { oid -> clips.find { it.id == oid }?.takeIf(::isEditable)?.let { return it } }
            // Inside a crossfade both clips are under the playhead; each owns its half, as on screen.
            return clips.filter { playhead >= it.start && playhead < it.end && isEditable(it) }
                .minWithOrNull(
                    compareBy<Clip> { it.video }.thenBy { it.track }
                        .thenByDescending { minOf(playhead - it.start, it.end - playhead) }
                )
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
        applyClips(withCurve(s.clips))
        refreshHistory()
        dirty = true
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(s)
        revealPlayhead()
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(s)
        revealPlayhead()
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

    private fun takePermissions(uris: List<Uri>) {
        for (uri in uris) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
    }

    /** Media files or a timeline waiting for the user to choose where they go (asked when the timeline isn't empty). */
    sealed interface PendingImport {
        class Media(val uris: List<Uri>) : PendingImport
        class Timeline(val otio: OtioImport.OTimeline, val files: Map<String, Uri>) : PendingImport
    }

    var pendingImport by mutableStateOf<PendingImport?>(null); private set

    /** Imports media files. On a timeline that already has clips, first asks where they should go. */
    fun importFiles(uris: List<Uri>) {
        takePermissions(uris)
        if (clips.isEmpty()) importMedia(uris, Placement.END) else pendingImport = PendingImport.Media(uris)
    }

    /** The answer to [pendingImport]; null cancels. */
    fun choosePlacement(p: Placement?) {
        val req = pendingImport ?: return
        pendingImport = null
        if (p == null) return
        when (req) {
            is PendingImport.Media -> importMedia(req.uris, p)
            is PendingImport.Timeline -> placeTimeline(req.otio, req.files, p)
        }
    }

    /**
     * Adds [new] clips in overwrite mode, like dropping them onto the timeline in Resolve: whatever they land on in
     * their lanes is trimmed, split or removed. New clips don't trim each other (crossfading clips overlap).
     */
    private fun overwrite(existing: List<Clip>, new: List<Clip>): List<Clip> {
        var kept = existing
        for (m in new) kept = TimelineOps.place(kept, m, ::newId).filter { it.id != m.id }
        return kept + new
    }

    /**
     * Puts imported files on the timeline, starting at the playhead or the end of the timeline, or on new tracks
     * at the playhead. Videos follow one another on a video track with their sound on a camera-audio track;
     * audio files follow one another on their own track, starting at the same point (ready to sync).
     */
    private fun importMedia(uris: List<Uri>, placement: Placement) {
        viewModelScope.launch {
            stop()
            val results = decodeAll(uris.map { it to newId() }, "Importing")
            val failures = mutableListOf<String>()
            val decoded = results.mapIndexedNotNull { i, r ->
                r.getOrElse {
                    failures += "${AudioDecoder.displayName(ctx, uris[i])}: ${it.message ?: "unknown error"}"
                    null
                }
            }
            val working = clips
            val added = ArrayList<Clip>()
            var tracks = trackCount
            var vTracks = videoTrackCount
            val explicitTrack = if (placement == Placement.NEW_TRACK) null else overrideSelectedId
                ?.let { id -> clips.find { it.id == id } }
                ?.takeIf { !it.video && it.linkId == null && isEditable(it) }?.track

            /**
             * First unlocked audio track holding only camera audio (or only recorder audio), adding one if needed.
             * New clips never go onto a locked track.
             */
            fun pickTrack(camera: Boolean): Int {
                for (t in 0 until tracks) {
                    if (isLocked(false, t)) continue
                    if ((working + added).filter { !it.video && it.track == t }.all { (it.linkId != null) == camera }) return t
                }
                if (tracks < MAX_AUDIO_TRACKS) return tracks++
                return (0 until tracks).firstOrNull { !isLocked(false, it) } ?: 0
            }

            var videoTrack: Int
            var camTrack: Int? = null      // fixed tracks when going onto new tracks
            var recTrack: Int? = null
            if (placement == Placement.NEW_TRACK) {
                val needV = decoded.any { it.video != null }
                val needCam = decoded.any { it.video != null && it.hasAudio }
                val needRec = decoded.any { it.video == null }
                val newA = (if (needCam) 1 else 0) + (if (needRec) 1 else 0)
                if ((needV && vTracks >= MAX_VIDEO_TRACKS) || tracks + newA > MAX_AUDIO_TRACKS) {
                    busy = null
                    message = "There's no room for new tracks (at most $MAX_VIDEO_TRACKS video and $MAX_AUDIO_TRACKS " +
                        "audio tracks). Import again and put the files at the playhead or the end instead."
                    return@launch
                }
                videoTrack = if (needV) vTracks++ else 0
                if (needCam) camTrack = tracks++
                if (needRec) recTrack = tracks++
            } else {
                videoTrack = (0 until vTracks).firstOrNull { !isLocked(true, it) }
                    ?: if (vTracks < MAX_VIDEO_TRACKS) vTracks else 0
            }

            val base = if (placement == Placement.END) totalFrames else playhead
            var videoAt = base
            var audioAt = base
            val newSources = mutableMapOf<Long, AudioSource>()
            for (src in decoded) {
                newSources[src.id] = src
                if (src.video != null) {
                    val cam = if (src.hasAudio) camTrack ?: pickTrack(camera = true) else -1
                    if (videoTrack >= vTracks) vTracks = videoTrack + 1   // all video tracks locked: add one
                    val link = if (cam >= 0) newId() else null
                    added += Clip(newId(), src.id, videoTrack, videoAt, 0L, src.frames, video = true, linkId = link)
                    if (cam >= 0) {
                        added += Clip(
                            newId(), src.id, cam, videoAt, 0L, src.pcm!!.frames.coerceAtMost(src.frames),
                            Fade(curve = defaultCurve), Fade(curve = defaultCurve), linkId = link,
                        )
                    }
                    videoAt += src.frames
                } else {
                    val track = recTrack ?: explicitTrack ?: pickTrack(camera = false)
                    added += Clip(
                        newId(), src.id, track, audioAt, 0L, src.frames,
                        Fade(curve = defaultCurve), Fade(curve = defaultCurve),
                    )
                    audioAt += src.frames
                }
            }
            if (newSources.isNotEmpty()) {
                sources = sources + newSources
                pushUndo()
                trackCount = tracks
                videoTrackCount = vTracks
                applyClips(overwrite(working, added))
                loadThumbnails(newSources.values)
            }
            busy = null
            val mismatched = newSources.values.mapNotNull { s -> s.video?.let { FrameRate.closestTo(it.fps) } }
                .filter { it != frameRate }.distinct()
            message = listOfNotNull(
                failures.takeIf { it.isNotEmpty() }?.let { "Couldn't import: " + it.joinToString("; ") },
                mismatched.takeIf { it.isNotEmpty() }?.let { r ->
                    "Video is ${r.joinToString(" / ") { it.label }} fps but the timeline is ${frameRate.label} fps. " +
                        "You can change the timeline in Settings."
                },
            ).joinToString("\n").ifEmpty { null }
        }
    }

    // ------------------------------------------------------------ Importing a timeline (.otio from Resolve)

    /** A timeline waiting for the user to pick media files it uses that aren't in the project yet. */
    class OtioMedia(val otio: OtioImport.OTimeline, val missing: List<String>, val found: Map<String, Uri>)

    var otioMedia by mutableStateOf<OtioMedia?>(null); private set

    /** Reads an .otio file. Media already in the project is reused; the user is asked for the rest. */
    fun importTimeline(uri: Uri) {
        stop()
        viewModelScope.launch {
            busy = "Reading timeline…"
            try {
                val otio = withContext(Dispatchers.IO) {
                    val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IOException("Couldn't open the file")
                    OtioImport.parse(text)
                }
                if (otio.clipCount == 0) {
                    message = "“${otio.name}” has no clips that can be imported"
                    return@launch
                }
                val known = sources.values.map { it.name.lowercase() }.toSet()
                val missing = otio.mediaNames.filter { it.lowercase() !in known }
                if (missing.isEmpty()) timelineReady(otio, emptyMap()) else otioMedia = OtioMedia(otio, missing, emptyMap())
            } catch (e: Exception) {
                message = "Couldn't read the timeline: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    /** Media files picked for [otioMedia], matched to the timeline's clips by file name. */
    fun otioMediaPicked(uris: List<Uri>) {
        val req = otioMedia ?: return
        takePermissions(uris)
        viewModelScope.launch {
            val named = withContext(Dispatchers.IO) { uris.associateBy { AudioDecoder.displayName(ctx, it).lowercase() } }
            val matched = req.missing.mapNotNull { n -> named[n.lowercase()]?.let { n to it } }.toMap()
            val found = req.found + matched
            val still = req.missing.filter { it !in matched }
            if (still.isEmpty()) {
                otioMedia = null
                timelineReady(req.otio, found)
            } else {
                otioMedia = OtioMedia(req.otio, still, found)
                val unused = uris.size - matched.size
                if (unused > 0) message = "$unused of the chosen files ${if (unused == 1) "isn't" else "aren't"} used by this timeline"
            }
        }
    }

    /** Goes ahead without the media still missing; clips that use it are left out. */
    fun otioSkipMissing() {
        val req = otioMedia ?: return
        otioMedia = null
        timelineReady(req.otio, req.found)
    }

    fun cancelOtio() { otioMedia = null }

    private fun timelineReady(otio: OtioImport.OTimeline, files: Map<String, Uri>) {
        if (clips.isEmpty()) placeTimeline(otio, files, Placement.END) else pendingImport = PendingImport.Timeline(otio, files)
    }

    /**
     * Puts an imported timeline's clips on this one: track for track from V1 / A1 (at the playhead or the end),
     * or on new tracks above the existing ones (at the playhead). An empty project takes the timeline's frame rate.
     */
    private fun placeTimeline(otio: OtioImport.OTimeline, files: Map<String, Uri>, placement: Placement) {
        viewModelScope.launch {
            stop()
            val wasEmpty = clips.isEmpty()
            val entries = files.entries.toList()
            val results = decodeAll(entries.map { it.value to newId() }, "Importing media")
            val failures = mutableListOf<String>()
            val newSources = LinkedHashMap<Long, AudioSource>()
            for ((i, r) in results.withIndex()) {
                r.onSuccess { newSources[it.id] = it }
                    .onFailure { failures += "${entries[i].key}: ${it.message ?: "unknown error"}" }
            }
            // Media already in the project first, then the newly picked files (matched by their timeline name).
            val media = HashMap<String, OtioImport.Media>()
            fun add(name: String, s: AudioSource) {
                media.putIfAbsent(
                    name.lowercase(),
                    OtioImport.Media(s.id, s.video != null, s.frames, s.pcm?.frames?.coerceAtMost(s.frames) ?: 0L),
                )
            }
            for (s in sources.values) add(s.name, s)
            for ((i, r) in results.withIndex()) r.getOrNull()?.let { add(entries[i].key, it) }

            val newTracks = placement == Placement.NEW_TRACK
            val vBase = if (newTracks) videoTrackCount else 0
            val aBase = if (newTracks) trackCount else 0
            val offset = if (placement == Placement.END) totalFrames else playhead
            val r = OtioImport.toClips(otio, media, offset, vBase, aBase, defaultCurve, ::newId)
            busy = null
            val needV = maxOf(videoTrackCount, vBase + r.videoTracksUsed)
            val needA = maxOf(trackCount, aBase + r.audioTracksUsed)
            val failed = failures.takeIf { it.isNotEmpty() }?.let { "\nCouldn't open: " + it.joinToString("; ") } ?: ""
            when {
                r.clips.isEmpty() -> {
                    message = "None of the clips in “${otio.name}” could be imported. Check the media files.$failed"
                    return@launch
                }
                needV > MAX_VIDEO_TRACKS || needA > MAX_AUDIO_TRACKS -> {
                    message = "That needs $needV video and $needA audio tracks, but the most is $MAX_VIDEO_TRACKS video " +
                        "and $MAX_AUDIO_TRACKS audio." + if (newTracks) " Try putting it at the playhead or the end instead." else ""
                    return@launch
                }
                !newTracks && r.clips.any { isLocked(it.video, it.track) } -> {
                    message = "Some of the tracks the timeline goes on are locked. Unlock them, or put it on new tracks."
                    return@launch
                }
            }
            sources = sources + newSources
            pushUndo()
            videoTrackCount = needV
            trackCount = needA
            mutedTracks = mutedTracks + r.mutedAudio
            val otioRate = otio.rate?.let { FrameRate.closestTo(it) }
            if (wasEmpty) {
                otioRate?.let { frameRate = it }
                if (savedName == null && projectName == "Untitled") projectName = ProjectStore.safeName(otio.name)
            }
            applyClips(overwrite(clips, r.clips))
            loadThumbnails(newSources.values)
            revealPlayhead()
            val left = otio.skipped + r.skipped
            message = buildString {
                append("Imported “${otio.name}”: ${r.clips.size} clip${if (r.clips.size == 1) "" else "s"}")
                if (left > 0) append(". $left item${if (left == 1) "" else "s"} left out (missing media, titles, effects or compound clips)")
                if (!wasEmpty && otioRate != null && otioRate != frameRate) {
                    append(". It was ${otioRate.label} fps; this project is ${frameRate.label} fps")
                }
                append(failed)
            }
        }
    }

    // ------------------------------------------------------------ Colour (LUTs, exposure, highlights, shadows)

    /** LUTs loaded into the app (.cube files), by name. */
    var luts by mutableStateOf<Map<String, CubeLut>>(emptyMap()); private set

    init {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                LutLibrary.names(ctx).mapNotNull { n -> runCatching { n to LutLibrary.load(ctx, n) }.getOrNull() }.toMap()
            }
            luts = saved + luts   // keeps any LUT loaded while these were being read
        }
    }

    /** Loads a .cube LUT into the app and applies it to the selected video clip. */
    fun importLut(uri: Uri) {
        viewModelScope.launch {
            try {
                val (name, lut) = withContext(Dispatchers.IO) { LutLibrary.import(ctx, uri) }
                luts = luts + (name to lut)
                gradeTarget?.let { changeGrade(it.id, it.grade.copy(lut = name)) }
                message = "LUT “$name” loaded"
            } catch (e: Exception) {
                message = "Couldn't load the LUT: ${e.message}"
            }
        }
    }

    /** Removes a LUT from the app. Clips using it keep its name but show without it. */
    fun deleteLut(name: String) {
        LutLibrary.delete(ctx, name)
        luts = luts - name
    }

    /** The video clip the colour controls apply to: the selection, or the video linked to selected camera audio. */
    val gradeTarget: Clip?
        get() = selectedClip?.let { s -> if (s.video) s else group(s).firstOrNull { it.video } }

    /** Call once when a colour slider drag starts, so the whole drag is one undo step. */
    fun beginGradeEdit() = pushUndo()

    /** Sets a clip's grade without an undo step (used while a slider is dragged). */
    fun setGrade(clipId: Long, grade: Grade) {
        applyClips(clips.map { if (it.id == clipId) it.copy(grade = grade) else it })
    }

    /** Sets a clip's grade as one undo step. */
    fun changeGrade(clipId: Long, grade: Grade) {
        if (clips.find { it.id == clipId }?.grade == grade) return
        pushUndo()
        setGrade(clipId, grade)
    }

    /** Copies [from]'s grade to the other video clips from the same file, or to every video clip when [all]. */
    fun copyGrade(from: Clip, all: Boolean) {
        val targets = clips.filter { it.video && it.id != from.id && (all || it.sourceId == from.sourceId) && isEditable(it) }
        if (targets.isEmpty()) {
            message = if (all) "There are no other video clips" else "There are no other clips from this file"
            return
        }
        pushUndo()
        val ids = targets.map { it.id }.toSet()
        applyClips(clips.map { if (it.id in ids) it.copy(grade = from.grade) else it })
        message = "Grade copied to ${targets.size} clip${if (targets.size == 1) "" else "s"}"
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

    fun updatePreview() = preview.update(clips, sources, playhead, isPlaying, luts)

    fun seek(frame: Long) {
        if (isPlaying) { player.stop(); isPlaying = false }
        playhead = frame.coerceAtLeast(0L)
    }

    /**
     * Moves the playhead by hand. With snapping on, it locks onto the nearest clip edge or cut
     * (or the start of the timeline) within [snapThresholdPx], and the snap guide shows it.
     */
    fun scrubTo(frame: Long, snapThresholdPx: Float) {
        var f = frame.coerceAtLeast(0L)
        var line: Long? = null
        if (snapEnabled) {
            val edges = ArrayList<Long>(clips.size * 2 + 1)
            edges += 0L
            for (c in clips) { edges += c.start; edges += c.end }
            val (s, l) = TimelineOps.snap(f, 0L, edges, (snapThresholdPx * samplesPerPx).toLong())
            f = s
            line = l
        }
        seek(f)
        snapLine = line
    }

    /** Call when the finger lifts after [scrubTo]. */
    fun endScrub() { snapLine = null }

    // ------------------------------------------------------------ Editing

    fun select(id: Long?) { overrideSelectedId = id }

    private fun inside(c: Clip) = playhead > c.start && playhead < c.end

    fun cut() {
        stop()
        val sel = selectedClip
        val targets = if (sel != null && inside(sel)) group(sel).filter(::inside)
        else clips.filter { inside(it) && isEditable(it) }
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
        val before = playhead
        try {
            rippleTrimEdit(atStart)
        } finally {
            followPlayhead(before)
        }
    }

    /**
     * After an edit moves the playhead from [from], scrolls the timeline by the same amount, so the playhead and
     * the material around it stay where they were on screen. A playhead that ends up off screen is brought back.
     */
    private fun followPlayhead(from: Long) {
        if (playhead == from) return
        scrollFrames = (scrollFrames + (playhead - from)).coerceAtLeast(0.0)
        revealPlayhead()
    }

    /** Scrolls the timeline to the playhead if it's off screen (placing it near the left, as during playback). */
    private fun revealPlayhead() {
        val x = (playhead - scrollFrames) / samplesPerPx
        if (x < 0 || x > viewWidthPx) {
            scrollFrames = (playhead - viewWidthPx * 0.15 * samplesPerPx).coerceAtLeast(0.0)
        }
    }

    private fun rippleTrimEdit(atStart: Boolean) {
        val t = selectedClip ?: run { message = "Move the playhead over a clip first"; return }
        val members = group(t).filter(::inside)
        if (members.isEmpty()) { message = "Move the playhead inside the clip to trim it"; return }
        if (rippleAllTracks) {
            // Remove that stretch of time from every track.
            val from = if (atStart) members.minOf { it.start } else playhead
            val to = if (atStart) playhead else members.maxOf { it.end }
            val new = TimelineOps.rippleRemoveRange(clips, from, to, ::newId, ::onUnlockedTrack)
            commit(new)
            playhead = from
            // Keep the trimmed clip selected: the piece now starting (or ending) at the edit point.
            overrideSelectedId = new.firstOrNull { it.sameLane(t) && (if (atStart) it.start == from else it.end == from) }?.id
            return
        }
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
        val members = group(sel)
        if (ripple && rippleAllTracks) {
            // Remove the clip's stretch of time from every track, keeping them all in sync.
            commit(
                TimelineOps.rippleRemoveRange(
                    clips, members.minOf { it.start }, members.maxOf { it.end }, ::newId, ::onUnlockedTrack,
                )
            )
            return
        }
        var new = clips
        for (c in members) new = if (ripple) TimelineOps.rippleDelete(new, c) else TimelineOps.delete(new, c)
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
        val c = clips.find { it.id == id }?.takeIf(::isEditable) ?: return
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
        val newTrack = if (laneVideo == lead.video && !isLocked(laneVideo, laneTrack)) laneTrack else lead.track
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

    // ------------------------------------------------------------ Trimming by dragging a clip's edge

    private var trimAtStart = false
    private var trimRange = 0L..0L

    /** Media available in a clip's source file (its audio for audio clips). */
    private fun sourceFramesOf(c: Clip): Long =
        sources[c.sourceId]?.let { if (c.video) it.frames else it.pcm?.frames ?: it.frames } ?: (c.sourceIn + c.length)

    /** Starts dragging the start or end edge of a clip; linked clips trim with it. */
    fun beginTrim(id: Long, atStart: Boolean) {
        stop()
        val c = clips.find { it.id == id }?.takeIf(::isEditable) ?: return
        val g = group(c)
        val ids = g.map { it.id }.toSet()
        // The edge can move only as far as every linked clip allows.
        var lo = Long.MIN_VALUE
        var hi = Long.MAX_VALUE
        for (m in g) {
            val r = TimelineOps.trimRange(clips, m, atStart, sourceFramesOf(m), ids)
            lo = maxOf(lo, r.first)
            hi = minOf(hi, r.last)
        }
        trimRange = if (lo <= 0 && hi >= 0) lo..hi else 0L..0L
        trimAtStart = atStart
        dragOrigin = g
        dragLeadId = id
        dragPreview = g
        overrideSelectedId = id
    }

    fun trimTo(totalDxPx: Float, snapThresholdPx: Float) {
        val g = dragOrigin ?: return
        val lead = g.first { it.id == dragLeadId }
        val edge = if (trimAtStart) lead.start else lead.end
        var d = (totalDxPx * samplesPerPx).toLong()
        var line: Long? = null
        if (snapEnabled) {
            val ids = g.map { it.id }.toSet()
            val edges = ArrayList<Long>()
            edges += 0L
            edges += playhead
            for (c in clips) if (c.id !in ids) { edges += c.start; edges += c.end }
            val (s, l) = TimelineOps.snap(edge + d, 0L, edges, (snapThresholdPx * samplesPerPx).toLong())
            if (l != null && s - edge in trimRange) { d = s - edge; line = l }
        }
        d = d.coerceIn(trimRange)
        dragPreview = g.map { TimelineOps.trimEdge(it, trimAtStart, d) }
        snapLine = line
    }

    fun endTrim() {
        val p = dragPreview ?: return
        val o = dragOrigin
        cancelDrag()
        if (p == o) return
        val byId = p.associateBy { it.id }
        commit(clips.map { byId[it.id] ?: it })
    }

    // ------------------------------------------------------------ Waveform sync

    /**
     * Lines up each video (via its camera audio) with the recorder audio on the timeline, like Resolve's
     * "Auto Align Clips > Based on Waveform". With a video tapped, only that one is synced. A synced clip that
     * would land on top of another clip is moved to a free track (a new one is added if needed).
     */
    fun syncToRecorder() {
        stop()
        val chosen = overrideSelectedId?.let { id -> clips.find { it.id == id } }?.takeIf(::isEditable)?.linkId
        val allGroups = clips.filter { it.linkId != null }.groupBy { it.linkId!! }
            .filter { (link, g) -> (chosen == null || link == chosen) && g.any { it.video } && g.any { !it.video } }
        // Videos on locked tracks stay where they are. (Recorder audio on a locked track can still be matched.)
        val groups = allGroups.filter { (_, g) -> isEditable(g.first()) }
        val recorders = clips.filter { !it.video && it.linkId == null && sources[it.sourceId]?.pcm != null }
        if (allGroups.isEmpty()) { message = "Import a video file with sound first"; return }
        if (groups.isEmpty()) { message = "Those videos are on locked tracks. Unlock them to sync."; return }
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
                    var track = (listOf(c.track) + (0 until count)).firstOrNull {
                        !isLocked(c.video, it) && TimelineOps.laneFree(new, c.video, it, c.start, c.end, ids)
                    }
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
                it.video, it.linkId, it.grade,
            )
        },
        videoTrackCount, mutedTracks,
        lockedAudio = lockedAudio, lockedVideo = lockedVideo,
        frameRate = frameRate.name, width = timelineWidth, height = timelineHeight,
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
            ProjectStore.markOpened(ctx, safe)
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
                        grade = c.grade,
                    )
                }
                sources = loaded
                thumbnails = emptyMap()
                trackCount = data.trackCount
                videoTrackCount = data.videoTrackCount
                mutedTracks = data.mutedTracks
                lockedAudio = data.lockedAudio
                lockedVideo = data.lockedVideo
                // Projects saved before the timeline format was stored take it from their first video.
                val firstVideo = loadedClips.firstNotNullOfOrNull { c -> loaded[c.sourceId]?.video?.takeIf { c.video } }
                frameRate = FrameRate.entries.find { it.name == data.frameRate }
                    ?: firstVideo?.let { FrameRate.closestTo(it.fps) } ?: DEFAULT_FRAME_RATE
                timelineWidth = data.width ?: firstVideo?.width?.coerceIn(MIN_RESOLUTION, MAX_RESOLUTION) ?: DEFAULT_WIDTH
                timelineHeight = data.height ?: firstVideo?.height?.coerceIn(MIN_RESOLUTION, MAX_RESOLUTION) ?: DEFAULT_HEIGHT
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
                applyClips(withCurve(loadedClips))
                projectName = data.name
                savedName = ProjectStore.safeName(name)
                dirty = false
                ProjectStore.markOpened(ctx, name)
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

    fun newProject(name: String, rate: FrameRate, width: Int, height: Int) {
        stop()
        frameRate = rate
        timelineWidth = width
        timelineHeight = height
        clearHistory()
        sources = emptyMap()
        thumbnails = emptyMap()
        trackCount = 2
        videoTrackCount = 1
        mutedTracks = emptySet()
        lockedAudio = emptySet()
        lockedVideo = emptySet()
        applyClips(emptyList())
        overrideSelectedId = null
        playhead = 0L
        scrollFrames = 0.0
        projectName = ProjectStore.safeName(name)
        savedName = null
        dirty = false
        message = "New project started (${rate.label} fps, ${width}×$height)"
    }

    /** Saved projects with when they were last opened and the size of their media. Slow-ish: call off the main thread. */
    fun projectInfo(): List<ProjectStore.Info> = ProjectStore.listInfo(ctx)

    fun deleteProject(name: String) {
        ProjectStore.delete(ctx, name)
        if (savedName == ProjectStore.safeName(name)) { savedName = null; dirty = clips.isNotEmpty() }
    }

    // ------------------------------------------------------------ Export

    fun exportWav(uri: Uri) {
        if (clips.isEmpty()) { message = "Nothing to export yet"; return }
        stop()
        val mixer = mixer()
        val used = sources.values.filter { s -> clips.any { !it.video && it.sourceId == s.id } }
        val errorsBefore = used.associateWith { it.pcm?.readErrors ?: 0 }
        viewModelScope.launch {
            busy = "Exporting audio…"
            try {
                withContext(Dispatchers.IO) {
                    val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Couldn't open the file")
                    out.use { Exporters.writeWav(it, mixer) }
                }
                // Audio is read from the original files; say so if any of them couldn't be read in full.
                val unreadable = used.filter { (it.pcm?.readErrors ?: 0) > errorsBefore.getValue(it) }
                message = if (unreadable.isEmpty()) "Audio exported"
                else "Exported, but parts of ${unreadable.joinToString { it.name }} couldn't be read and are silent"
            } catch (e: Exception) {
                message = "Export failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    /** Where the media will be on the computer Resolve runs on (remembered between exports). Blank = match by name. */
    var resolveMediaFolder by mutableStateOf(prefs.getString(KEY_RESOLVE_FOLDER, "") ?: ""); private set

    fun updateResolveMediaFolder(folder: String) {
        resolveMediaFolder = folder
        prefs.edit().putString(KEY_RESOLVE_FOLDER, folder).apply()
    }

    /** Names of the media files the timeline uses, for the export dialog. */
    fun usedMediaNames(): List<String> =
        clips.map { it.sourceId }.distinct().mapNotNull { sources[it]?.name }.distinct().sorted()

    /** Writes the Resolve timeline to [uri] as FCP7 XML, or OpenTimelineIO when [otio]. The media stays where it is. */
    fun exportResolve(uri: Uri, name: String, otio: Boolean) {
        if (clips.isEmpty()) { message = "Nothing to export yet"; return }
        stop()
        val rate = frameRate
        val folder = resolveMediaFolder
        val timeline = Exporters.Timeline(
            name, clips, sources, trackCount, videoTrackCount, mutedTracks, timelineWidth, timelineHeight,
            lockedAudio, lockedVideo,
        )
        viewModelScope.launch {
            busy = "Saving Resolve timeline…"
            try {
                withContext(Dispatchers.IO) {
                    val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't open the file")
                    out.use {
                        if (otio) Exporters.writeResolveOtio(it, timeline, rate, folder)
                        else Exporters.writeResolveXml(it, timeline, rate, folder)
                    }
                }
                message = "Timeline saved. In Resolve: File > Import > Timeline"
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
        const val KEY_RIPPLE_ALL = "rippleAllTracks"
        const val KEY_RESOLVE_FOLDER = "resolveMediaFolder"
        const val AUTOSAVE_NAME = "Autosave"

        /** Older projects called the curved fade "EXPONENTIAL". */
        fun parseCurve(s: String) = if (s == FadeCurve.LINEAR.name) FadeCurve.LINEAR else FadeCurve.CURVED
    }
}
