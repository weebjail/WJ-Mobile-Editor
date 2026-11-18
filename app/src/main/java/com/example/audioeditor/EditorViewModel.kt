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
import kotlin.math.abs

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
        viewModelScope.launch(Dispatchers.IO) { player.prepare() }   // so the first play starts promptly
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

    /**
     * When on, moving the playhead during playback pauses it. Off (the default): playback carries on from
     * wherever the playhead is put, as in Resolve.
     */
    var pauseOnSeek by mutableStateOf(prefs.getBoolean(KEY_PAUSE_ON_SEEK, false)); private set

    fun updatePauseOnSeek(on: Boolean) {
        pauseOnSeek = on
        prefs.edit().putBoolean(KEY_PAUSE_ON_SEEK, on).apply()
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
    var projectName by mutableStateOf(tr("Untitled", "無題")); private set

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
            return clipUnderPlayhead { playhead >= it.start && playhead < it.end }
        }

    /** The editable clip under the playhead that [under] accepts: audio before video, lowest track first. */
    private fun clipUnderPlayhead(under: (Clip) -> Boolean): Clip? =
        // Inside a crossfade both clips are under the playhead; each owns its half, as on screen.
        clips.filter { under(it) && isEditable(it) }
            .minWithOrNull(
                compareBy<Clip> { it.video }.thenBy { it.track }
                    .thenByDescending { minOf(playhead - it.start, it.end - playhead) }
            )

    /** A clip together with the clips linked to it (a video and its camera audio). */
    private fun group(c: Clip, list: List<Clip> = clips): List<Clip> =
        if (c.linkId == null) listOf(c) else list.filter { it.linkId == c.linkId }

    val selectedIds: Set<Long> get() = selectedClip?.let { s -> group(s).map { it.id }.toSet() } ?: emptySet()

    /** The audio clip that fades and crossfades apply to: the selection, or a selected video's own audio. */
    val fadeTarget: Clip?
        get() = selectedClip?.let { s -> if (!s.video) s else group(s).firstOrNull { !it.video } }

    val totalFrames: Long get() = clips.maxOfOrNull { it.end } ?: 0L

    // ------------------------------------------------------------ Undo / redo

    /**
     * One undo step. [playhead] is where the playhead was when the edit was made: undoing or redoing that edit
     * takes the playhead (and the viewer) back there, scrolling the timeline if it's off screen.
     */
    private data class Snapshot(
        val clips: List<Clip>, val trackCount: Int, val videoTrackCount: Int, val markers: List<Marker>, val playhead: Long,
    )
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private fun snapshot() = Snapshot(clips, trackCount, videoTrackCount, markers, playhead)

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
        stop()
        trackCount = s.trackCount
        videoTrackCount = s.videoTrackCount
        markers = s.markers
        applyClips(withCurve(s.clips))
        playhead = s.playhead
        refreshHistory()
        dirty = true
        revealPlayhead()
    }

    // The step moved to the other stack keeps the edit's playhead, so undo and redo both go to where it was made.
    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot().copy(playhead = s.playhead))
        restore(s)
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot().copy(playhead = s.playhead))
        restore(s)
    }

    // ------------------------------------------------------------ Import

    /** Decodes files two at a time (cached files load almost instantly). Results keep the input order. */
    private suspend fun decodeAll(items: List<Pair<Uri, Long>>, label: String): List<Result<AudioSource>> {
        val done = AtomicInteger()
        busy = tr("$label 0 of ${items.size}…", "$label 0 / ${items.size}…")
        val io = Dispatchers.IO.limitedParallelism(2)
        return coroutineScope {
            items.map { (uri, id) ->
                async(io) {
                    val r = runCatching { AudioDecoder.decode(ctx, uri, id) }
                    val n = done.incrementAndGet()
                    busy = tr("$label $n of ${items.size}…", "$label $n / ${items.size}…")
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
            val results = decodeAll(uris.map { it to newId() }, tr("Importing", "読み込み中"))
            val failures = mutableListOf<String>()
            val decoded = results.mapIndexedNotNull { i, r ->
                r.getOrElse {
                    failures += "${AudioDecoder.displayName(ctx, uris[i])}: ${it.message ?: tr("unknown error", "不明なエラー")}"
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
                    message = tr(
                        "There's no room for new tracks (at most $MAX_VIDEO_TRACKS video and $MAX_AUDIO_TRACKS " +
                            "audio tracks). Import again and put the files at the playhead or the end instead.",
                        "新しいトラックを追加できません（ビデオは最大$MAX_VIDEO_TRACKS、オーディオは最大${MAX_AUDIO_TRACKS}トラック）。" +
                            "もう一度読み込んで、再生ヘッドの位置か末尾に配置してください。",
                    )
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
                failures.takeIf { it.isNotEmpty() }?.let { tr("Couldn't import: ", "読み込めませんでした: ") + it.joinToString("; ") },
                mismatched.takeIf { it.isNotEmpty() }?.let { r ->
                    val rates = r.joinToString(" / ") { it.label }
                    tr(
                        "Video is $rates fps but the timeline is ${frameRate.label} fps. You can change the timeline in Settings.",
                        "ビデオは $rates fps ですが、タイムラインは ${frameRate.label} fps です。タイムラインは設定で変更できます。",
                    )
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
            busy = tr("Reading timeline…", "タイムラインを読み込み中…")
            try {
                val otio = withContext(Dispatchers.IO) {
                    val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IOException(tr("Couldn't open the file", "ファイルを開けませんでした"))
                    OtioImport.parse(text)
                }
                if (otio.clipCount == 0) {
                    message = tr("“${otio.name}” has no clips that can be imported", "「${otio.name}」には読み込めるクリップがありません")
                    return@launch
                }
                val known = sources.values.map { it.name.lowercase() }.toSet()
                val missing = otio.mediaNames.filter { it.lowercase() !in known }
                if (missing.isEmpty()) timelineReady(otio, emptyMap()) else otioMedia = OtioMedia(otio, missing, emptyMap())
            } catch (e: Exception) {
                message = tr("Couldn't read the timeline: ", "タイムラインを読み込めませんでした: ") + e.message
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
                if (unused > 0) message = tr(
                    "$unused of the chosen files ${if (unused == 1) "isn't" else "aren't"} used by this timeline",
                    "選択したファイルのうち${unused}個はこのタイムラインで使われていません",
                )
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
            val results = decodeAll(entries.map { it.value to newId() }, tr("Importing media", "メディアを読み込み中"))
            val failures = mutableListOf<String>()
            val newSources = LinkedHashMap<Long, AudioSource>()
            for ((i, r) in results.withIndex()) {
                r.onSuccess { newSources[it.id] = it }
                    .onFailure { failures += "${entries[i].key}: ${it.message ?: tr("unknown error", "不明なエラー")}" }
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
            val failed = failures.takeIf { it.isNotEmpty() }
                ?.let { tr("\nCouldn't open: ", "\n開けませんでした: ") + it.joinToString("; ") } ?: ""
            when {
                r.clips.isEmpty() -> {
                    message = tr(
                        "None of the clips in “${otio.name}” could be imported. Check the media files.",
                        "「${otio.name}」のクリップを1つも読み込めませんでした。メディアファイルを確認してください。",
                    ) + failed
                    return@launch
                }
                needV > MAX_VIDEO_TRACKS || needA > MAX_AUDIO_TRACKS -> {
                    message = tr(
                        "That needs $needV video and $needA audio tracks, but the most is $MAX_VIDEO_TRACKS video " +
                            "and $MAX_AUDIO_TRACKS audio." + if (newTracks) " Try putting it at the playhead or the end instead." else "",
                        "ビデオ${needV}トラックとオーディオ${needA}トラックが必要ですが、最大はビデオ$MAX_VIDEO_TRACKS、" +
                            "オーディオ${MAX_AUDIO_TRACKS}トラックです。" +
                            if (newTracks) "再生ヘッドの位置か末尾に配置してみてください。" else "",
                    )
                    return@launch
                }
                !newTracks && r.clips.any { isLocked(it.video, it.track) } -> {
                    message = tr(
                        "Some of the tracks the timeline goes on are locked. Unlock them, or put it on new tracks.",
                        "配置先のトラックの一部がロックされています。ロックを解除するか、新しいトラックに配置してください。",
                    )
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
                if (savedName == null && projectName in UNTITLED_NAMES) projectName = ProjectStore.safeName(otio.name)
            }
            applyClips(overwrite(clips, r.clips))
            loadThumbnails(newSources.values)
            revealPlayhead()
            val left = otio.skipped + r.skipped
            message = buildString {
                append(tr(
                    "Imported “${otio.name}”: ${r.clips.size} clip${if (r.clips.size == 1) "" else "s"}",
                    "「${otio.name}」を読み込みました: クリップ${r.clips.size}個",
                ))
                if (left > 0) append(tr(
                    ". $left item${if (left == 1) "" else "s"} left out (missing media, titles, effects or compound clips)",
                    "。${left}個の項目は除外されました（見つからないメディア、タイトル、エフェクト、複合クリップ）",
                ))
                if (!wasEmpty && otioRate != null && otioRate != frameRate) {
                    append(tr(
                        ". It was ${otioRate.label} fps; this project is ${frameRate.label} fps",
                        "。元は ${otioRate.label} fps、このプロジェクトは ${frameRate.label} fps です",
                    ))
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
                message = tr("LUT “$name” loaded", "LUT「$name」を読み込みました")
            } catch (e: Exception) {
                message = tr("Couldn't load the LUT: ", "LUTを読み込めませんでした: ") + e.message
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
            message = if (all) tr("There are no other video clips", "他にビデオクリップはありません")
            else tr("There are no other clips from this file", "このファイルの他のクリップはありません")
            return
        }
        pushUndo()
        val ids = targets.map { it.id }.toSet()
        applyClips(clips.map { if (it.id in ids) it.copy(grade = from.grade) else it })
        message = tr(
            "Grade copied to ${targets.size} clip${if (targets.size == 1) "" else "s"}",
            "カラーを${targets.size}個のクリップにコピーしました",
        )
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
        if (clips.isEmpty()) { message = tr("Import some media first", "先にメディアを読み込んでください"); return }
        if (playhead >= totalFrames) playhead = 0
        player.play(playhead, mixer())
        isPlaying = true
    }

    fun stop() {
        if (!isPlaying) return
        if (!scrubHeld) playhead = player.position()
        player.stop()
        isPlaying = false
        scrubHeld = false
    }

    /**
     * True while the playhead is being dragged during playback: the sound holds, and playback carries on from
     * wherever the playhead is let go (see [endScrub]).
     */
    private var scrubHeld by mutableStateOf(false)

    /** Playing and actually moving (not held while the playhead is dragged). */
    val playbackRunning: Boolean get() = isPlaying && !scrubHeld

    /** Called every screen frame while playing. */
    fun tickPlayback() {
        if (!isPlaying || scrubHeld) return
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

    fun updatePreview() = preview.update(clips, sources, playhead, playbackRunning, luts)

    /** Moves the playhead. During playback this pauses (if [pauseOnSeek]) or holds until [endScrub]. */
    fun seek(frame: Long) {
        if (isPlaying) {
            if (pauseOnSeek) stop()
            else if (!scrubHeld) { player.stop(); scrubHeld = true }
        }
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
            for (m in markers) edges += m.frame
            val (s, l) = TimelineOps.snap(f, 0L, edges, (snapThresholdPx * samplesPerPx).toLong())
            f = s
            line = l
        }
        seek(f)
        snapLine = line
    }

    /** Call when the finger lifts after [scrubTo]. Playback that was held carries on from the new spot. */
    fun endScrub() {
        snapLine = null
        if (!scrubHeld) return
        scrubHeld = false
        if (playhead >= totalFrames) { isPlaying = false; return }
        player.play(playhead, mixer())
    }

    // ------------------------------------------------------------ Editing

    fun select(id: Long?) { overrideSelectedId = id }

    private fun inside(c: Clip) = playhead > c.start && playhead < c.end

    fun cut() {
        stop()
        val sel = selectedClip
        val targets = if (sel != null && inside(sel)) group(sel).filter(::inside)
        else clips.filter { inside(it) && isEditable(it) }
        if (targets.isEmpty()) { message = tr("Move the playhead over a clip to cut it", "カットするには再生ヘッドをクリップの上に移動してください"); return }
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
        // The clip at the playhead. The selected clip only counts if the playhead is inside it
        // (that's how to pick between clips stacked on different tracks).
        val t = selectedClip?.takeIf { s -> group(s).any(::inside) } ?: clipUnderPlayhead(::inside)
            ?: run { message = tr("Move the playhead inside a clip to trim it", "トリムするには再生ヘッドをクリップの内側に移動してください"); return }
        val members = group(t).filter(::inside)
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
        val sel = selectedClip ?: run { message = tr("Tap a clip to select it first", "先にクリップをタップして選択してください"); return }
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

    // ------------------------------------------------------------ Cut / copy / paste

    /** Copied clips (a clip and anything linked to it). Cleared when another project is opened. */
    private var clipboard by mutableStateOf<List<Clip>?>(null)
    val canPaste: Boolean get() = clipboard != null

    /**
     * Paste destinations, like Resolve's destination track controls: pasted audio goes on [targetAudio] and
     * video on [targetVideo] (tracks further down for clips copied from several tracks). Set from the track headers.
     */
    var targetAudio by mutableIntStateOf(0); private set
    var targetVideo by mutableIntStateOf(0); private set

    fun setTarget(video: Boolean, track: Int) {
        if (video) targetVideo = track else targetAudio = track
    }

    private fun resetClipboard() { clipboard = null; targetAudio = 0; targetVideo = 0 }

    fun copySelected() {
        val sel = selectedClip ?: run { message = tr("Tap a clip to select it first", "先にクリップをタップして選択してください"); return }
        clipboard = group(sel)
        message = tr("Copied", "コピーしました")
    }

    /** Copies the selected clip, then removes it, leaving a gap (Resolve's Cut). */
    fun cutSelected() {
        val sel = selectedClip ?: run { message = tr("Tap a clip to select it first", "先にクリップをタップして選択してください"); return }
        stop()
        val members = group(sel)
        clipboard = members
        var new = clips
        for (c in members) new = TimelineOps.delete(new, c)
        commit(new)
    }

    /**
     * Pastes at the playhead onto the destination tracks, overwriting whatever is there, then moves the playhead
     * to the end of the pasted clips (as Resolve does), so pasting again puts the next copy straight after.
     */
    fun paste() {
        val copied = clipboard ?: run { message = tr("Copy or cut a clip first", "先にクリップをコピーまたはカットしてください"); return }
        stop()
        val first = copied.minOf { it.start }
        val minA = copied.filter { !it.video }.minOfOrNull { it.track } ?: 0
        val minV = copied.filter { it.video }.minOfOrNull { it.track } ?: 0
        val placed = copied.map { c ->
            val track = if (c.video) targetVideo + c.track - minV else targetAudio + c.track - minA
            c.copy(id = 0L, start = playhead + c.start - first, track = track)
        }
        val needA = placed.filter { !it.video }.maxOfOrNull { it.track + 1 } ?: 0
        val needV = placed.filter { it.video }.maxOfOrNull { it.track + 1 } ?: 0
        if (needA > MAX_AUDIO_TRACKS || needV > MAX_VIDEO_TRACKS) {
            message = tr("There aren't enough tracks below the paste track", "貼り付け先のトラックの下に十分なトラックがありません")
            return
        }
        if (placed.any { isLocked(it.video, it.track) }) {
            message = tr("The paste track is locked. Unlock it or pick another one.", "貼り付け先のトラックがロックされています。ロックを解除するか別のトラックを選んでください。")
            return
        }
        // Each copy gets its own clip IDs and its own link, so it doesn't move together with the original.
        val links = copied.mapNotNull { it.linkId }.distinct().associateWith { newId() }
        val fresh = placed.map { it.copy(id = newId(), linkId = it.linkId?.let(links::get)) }
        pushUndo()
        trackCount = maxOf(trackCount, needA)
        videoTrackCount = maxOf(videoTrackCount, needV)
        applyClips(overwrite(clips, fresh))
        val before = playhead
        playhead = fresh.maxOf { it.end }
        overrideSelectedId = null
        followPlayhead(before)
    }

    fun crossfade() {
        stop()
        val t = fadeTarget ?: run { message = tr(
            "Select or move the playhead over an audio clip to crossfade",
            "クロスフェードするには、オーディオクリップを選択するか再生ヘッドをその上に移動してください",
        ); return }
        val duration = (crossfadeSeconds * SAMPLE_RATE).toLong()
        val result = TimelineOps.crossfade(clips, t, playhead, duration, defaultCurve) { id ->
            sources[id]?.let { it.pcm?.frames ?: it.frames } ?: 0L
        }
        when (result) {
            is TimelineOps.CrossfadeResult.Done -> commit(result.clips)
            TimelineOps.CrossfadeResult.NoHandles -> message =
                tr(
                    "There's no extra audio beyond this cut to blend. Trim the end of the first clip or the " +
                        "start of the second a little, then try again.",
                    "このカットの前後に重ねるための余分な音声がありません。1つ目のクリップの終わりか" +
                        "2つ目のクリップの始まりを少しトリムしてから、もう一度試してください。",
                )
        }
    }

    fun addAudioTrack() {
        if (trackCount >= MAX_AUDIO_TRACKS) { message = tr("$MAX_AUDIO_TRACKS audio tracks is the maximum", "オーディオトラックは最大${MAX_AUDIO_TRACKS}本です"); return }
        pushUndo()
        trackCount++
    }

    fun addVideoTrack() {
        if (videoTrackCount >= MAX_VIDEO_TRACKS) { message = tr("$MAX_VIDEO_TRACKS video tracks is the maximum", "ビデオトラックは最大${MAX_VIDEO_TRACKS}本です"); return }
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

    // ------------------------------------------------------------ Crossfade length

    /** Two overlapping clips on one track: [out] fades out while [into] fades in. */
    data class CrossfadePair(val out: Clip, val into: Clip) {
        val frames: Long get() = out.end - into.start
    }

    /** The clips before the current crossfade-slider drag, so every step is worked out from the same start. */
    private var crossfadeBase: List<Clip>? = null
    private var crossfadeIds by mutableStateOf<Pair<Long, Long>?>(null)

    /**
     * The crossfade the playhead is over, on the fade target's track (it then gets one crossfade slider instead
     * of separate fade in/out sliders). While its slider is being dragged it stays, even if it shrinks away.
     */
    val crossfadeAtPlayhead: CrossfadePair?
        get() {
            crossfadeIds?.let { (a, b) ->
                val out = clips.find { it.id == a }
                val into = clips.find { it.id == b }
                if (out != null && into != null) return CrossfadePair(out, into)
            }
            val t = fadeTarget ?: return null
            val lane = clips.filter { it.sameLane(t) }.sortedBy { it.start }
            for (i in TimelineOps.crossfadePairs(lane)) {
                val a = lane[i]
                val b = lane[i + 1]
                if ((t.id == a.id || t.id == b.id) && playhead >= b.start && playhead < a.end) return CrossfadePair(a, b)
            }
            return null
        }

    private fun sourceLength(id: Long): Long = sources[id]?.let { it.pcm?.frames ?: it.frames } ?: 0L

    /** Rebuilds the crossfade between [p]'s clips at [frames] long (0 = a plain cut), centred on the same cut. */
    private fun crossfadeOf(base: List<Clip>, p: CrossfadePair, frames: Long): List<Clip>? {
        val into = base.find { it.id == p.into.id } ?: return null
        val r = TimelineOps.crossfade(base, into, into.start, frames, defaultCurve, ::sourceLength)
        return (r as? TimelineOps.CrossfadeResult.Done)?.clips
    }

    /** The longest crossfade (up to [MAX_FADE_SECONDS]) the media either side of the cut allows. */
    fun maxCrossfadeFrames(p: CrossfadePair): Long {
        val new = crossfadeOf(crossfadeBase ?: clips, p, MAX_FADE_SECONDS * SAMPLE_RATE) ?: return p.frames
        val a = new.find { it.id == p.out.id } ?: return p.frames
        val b = new.find { it.id == p.into.id } ?: return p.frames
        return maxOf(a.end - b.start, p.frames)
    }

    /** Call once when a crossfade slider drag starts, so the whole drag is one undo step. */
    fun beginCrossfadeEdit(p: CrossfadePair) {
        pushUndo()
        crossfadeBase = clips
        crossfadeIds = p.out.id to p.into.id
    }

    fun setCrossfadeLength(p: CrossfadePair, frames: Long) {
        crossfadeOf(crossfadeBase ?: clips, p, frames)?.let(::applyClips)
    }

    fun endCrossfadeEdit() {
        crossfadeBase = null
        crossfadeIds = null
    }

    // ------------------------------------------------------------ Markers (like Resolve's timeline markers)

    var markers by mutableStateOf<List<Marker>>(emptyList()); private set

    /** Colour for new markers, chosen with the button beside play (remembered between sessions). */
    var markerColor by mutableStateOf(
        MarkerColor.entries.find { it.name == prefs.getString(KEY_MARKER_COLOR, null) } ?: MarkerColor.BLUE
    ); private set

    fun updateMarkerColor(c: MarkerColor) {
        markerColor = c
        prefs.edit().putString(KEY_MARKER_COLOR, c.name).apply()
    }

    /** The marker being edited in the marker dialog. */
    var editingMarker by mutableStateOf<Marker?>(null); private set

    /** A marker at the playhead (within a few pixels), if there is one. */
    private fun markerAtPlayhead(): Marker? {
        val tolerance = (samplesPerPx * 3).toLong()
        return markers.filter { abs(it.frame - playhead) <= tolerance }.minByOrNull { abs(it.frame - playhead) }
    }

    /**
     * The marker button, as in Resolve: adds a marker at the playhead in the current colour. Pressed again on a
     * marker (while stopped) it opens that marker so it can be named, noted, recoloured or deleted.
     * During playback it just keeps adding markers.
     */
    fun markerButton() {
        if (!isPlaying) markerAtPlayhead()?.let { editingMarker = it; return }
        val frame = if (playbackRunning) player.position() else playhead
        pushUndo()
        markers = (markers + Marker(newId(), frame, markerColor)).sortedBy { it.frame }
    }

    fun updateMarker(m: Marker) {
        editingMarker = null
        val old = markers.find { it.id == m.id } ?: return
        if (old == m) return
        pushUndo()
        markers = markers.map { if (it.id == m.id) m else it }
    }

    fun deleteMarker(id: Long) {
        editingMarker = null
        if (markers.none { it.id == id }) return
        pushUndo()
        markers = markers.filter { it.id != id }
    }

    fun closeMarker() { editingMarker = null }

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
            for (m in markers) edges += m.frame
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
            for (m in markers) edges += m.frame
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
        if (allGroups.isEmpty()) { message = tr("Import a video file with sound first", "先に音声付きのビデオファイルを読み込んでください"); return }
        if (groups.isEmpty()) {
            message = tr("Those videos are on locked tracks. Unlock them to sync.", "それらのビデオはロックされたトラックにあります。同期するにはロックを解除してください。")
            return
        }
        if (recorders.isEmpty()) { message = tr("Import the recorder's audio file first", "先にレコーダーの音声ファイルを読み込んでください"); return }

        viewModelScope.launch {
            busy = tr("Matching waveforms…", "波形を照合中…")
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
                val name = srcs[cam.sourceId]?.name ?: tr("clip", "クリップ")
                if (best == null) { problems += name + tr(" (no match found)", "（一致なし）"); continue }
                val (rec, m) = best
                val g = new.filter { it.linkId == link }
                val ids = g.map { it.id }.toSet()
                val delta = rec.start + m.lagFrames - cam.start
                if (g.any { it.start + delta < 0 }) { problems += name + tr(" (would start before 0:00)", "（0:00より前に始まってしまう）"); continue }
                val placed = g.map { it.copy(start = it.start + delta) }.map { c ->
                    val count = if (c.video) vTracks else aTracks
                    var track = (listOf(c.track) + (0 until count)).firstOrNull {
                        !isLocked(c.video, it) && TimelineOps.laneFree(new, c.video, it, c.start, c.end, ids)
                    }
                    if (track == null && c.video && vTracks < MAX_VIDEO_TRACKS) track = vTracks++
                    if (track == null && !c.video && aTracks < MAX_AUDIO_TRACKS) track = aTracks++
                    track?.let { c.copy(track = it) }
                }
                if (placed.any { it == null }) { problems += name + tr(" (no free track)", "（空きトラックなし）"); continue }
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
                append(tr(
                    "Synced $synced of ${matches.size} video clip${if (matches.size == 1) "" else "s"}",
                    "ビデオクリップ${matches.size}個中${synced}個を同期しました",
                ))
                if (problems.isNotEmpty()) append(tr(". Not synced: ", "。同期できなかったもの: ")).append(problems.joinToString("; "))
                if (synced > 0) append(tr(
                    ". Mute the camera audio track if you only want the recorder.",
                    "。レコーダーの音声だけを使う場合は、カメラ音声のトラックをミュートしてください。",
                ))
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
        markers = markers,
    )

    /** True if saving under [name] would replace a different, existing project. */
    fun wouldOverwrite(name: String): Boolean {
        val safe = ProjectStore.safeName(name)
        return safe != savedName && ProjectStore.exists(ctx, safe)
    }

    /** True once the project has a file of its own, so Save can write straight to it. */
    val hasProjectFile: Boolean get() = savedName != null

    /**
     * Save: writes the current project to its own file. Returns false (nothing saved) when it has never been
     * saved, so the screen asks for a name instead, as Save As.
     */
    fun save(): Boolean {
        val name = savedName ?: return false
        saveProject(name)
        return true
    }

    /** Save As: writes the project under [name], which then becomes the current project. */
    fun saveProject(name: String = projectName) {
        val safe = ProjectStore.safeName(name)
        try {
            ProjectStore.save(ctx, projectData(safe))
            ProjectStore.markOpened(ctx, safe)
            projectName = safe
            savedName = safe
            dirty = false
            rememberView()
            message = tr("Project '$safe' saved", "プロジェクト「$safe」を保存しました")
        } catch (e: Exception) {
            message = tr("Couldn't save: ", "保存できませんでした: ") + e.message
        }
    }

    /** Remembers where the playhead, scroll and zoom are, so opening the project again goes back there. */
    private fun rememberView(name: String? = savedName) {
        name ?: return
        ProjectStore.saveView(ctx, name, ViewState(if (isPlaying) player.position() else playhead, scrollFrames, samplesPerPx))
    }

    /**
     * Called when the app goes to the background. Saves unsaved changes to the project's own file, or to
     * "Autosave" if it has never been saved, so it never overwrites a different project.
     */
    fun autoSave() {
        val target = savedName
        if (dirty && clips.isNotEmpty()) {
            try {
                ProjectStore.save(ctx, projectData(target ?: AUTOSAVE_NAME))
                if (target != null) dirty = false
                if (target == null) rememberView(AUTOSAVE_NAME)
            } catch (_: Exception) {
            }
        }
        rememberView()
    }

    // ------------------------------------------------------------ Project screen (shown when the app opens)

    var showProjects by mutableStateOf(true); private set

    /** True when the project screen was opened from the editor (not at start-up), so it can go back to it. */
    var canLeaveProjects by mutableStateOf(false); private set

    fun openProjectScreen() {
        stop()
        rememberView()
        showProjects = true
        canLeaveProjects = true
    }

    fun closeProjectScreen() { if (canLeaveProjects) showProjects = false }

    fun loadProject(name: String) {
        stop()
        viewModelScope.launch {
            busy = tr("Loading project '$name'…", "プロジェクト「$name」を読み込み中…")
            try {
                val data = withContext(Dispatchers.IO) { ProjectStore.load(ctx, name) }
                    ?: throw IOException(tr("Project not found", "プロジェクトが見つかりません"))
                val results = decodeAll(data.sources.map { Uri.parse(it.uriString) to it.id }, tr("Loading media", "メディアを読み込み中"))
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
                // Back to where the project was left: playhead, scroll and zoom (older projects: the saved playhead).
                val view = withContext(Dispatchers.IO) { ProjectStore.loadView(ctx, name) }
                markers = data.markers
                playhead = view?.playhead ?: data.playhead
                view?.let { samplesPerPx = it.samplesPerPx.coerceIn(1.0, SAMPLE_RATE * 2.0) }
                scrollFrames = view?.scrollFrames?.coerceAtLeast(0.0) ?: 0.0
                overrideSelectedId = null
                // New clips must never reuse an ID from the loaded project.
                nextId = maxOf(
                    nextId,
                    (loadedClips.flatMap { listOfNotNull(it.id, it.linkId) } + data.sources.map { it.id } + data.markers.map { it.id })
                        .maxOrNull()?.plus(1) ?: 1L,
                )
                clearHistory()
                resetClipboard()
                applyClips(withCurve(loadedClips))
                projectName = data.name
                savedName = ProjectStore.safeName(name)
                dirty = false
                ProjectStore.markOpened(ctx, name)
                loadThumbnails(loaded.values)
                revealPlayhead()
                showProjects = false
                message = if (missing.isEmpty()) tr("Loaded '${data.name}'", "「${data.name}」を読み込みました")
                else tr(
                    "Loaded '${data.name}', but couldn't open: ${missing.joinToString(", ")}. Those clips will be silent.",
                    "「${data.name}」を読み込みましたが、次のファイルを開けませんでした: ${missing.joinToString("、")}。" +
                        "それらのクリップは無音になります。",
                )
            } catch (e: Exception) {
                message = tr("Failed to load project: ", "プロジェクトを読み込めませんでした: ") + e.message
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
        resetClipboard()
        sources = emptyMap()
        thumbnails = emptyMap()
        trackCount = 2
        videoTrackCount = 1
        mutedTracks = emptySet()
        lockedAudio = emptySet()
        lockedVideo = emptySet()
        applyClips(emptyList())
        markers = emptyList()
        overrideSelectedId = null
        playhead = 0L
        scrollFrames = 0.0
        projectName = ProjectStore.safeName(name)
        savedName = null
        dirty = false
        showProjects = false
        message = tr("New project started", "新しいプロジェクトを開始しました") + " (${rate.label} fps, ${width}×$height)"
    }

    /** Saved projects with when they were last opened and the size of their media. Slow-ish: call off the main thread. */
    fun projectInfo(): List<ProjectStore.Info> = ProjectStore.listInfo(ctx)

    fun deleteProject(name: String) {
        ProjectStore.delete(ctx, name)
        if (savedName == ProjectStore.safeName(name)) { savedName = null; dirty = clips.isNotEmpty() }
    }

    // ------------------------------------------------------------ Export

    fun exportWav(uri: Uri) {
        if (clips.isEmpty()) { message = tr("Nothing to export yet", "書き出すものがまだありません"); return }
        stop()
        val mixer = mixer()
        val used = sources.values.filter { s -> clips.any { !it.video && it.sourceId == s.id } }
        val errorsBefore = used.associateWith { it.pcm?.readErrors ?: 0 }
        viewModelScope.launch {
            busy = tr("Exporting audio…", "オーディオを書き出し中…")
            try {
                withContext(Dispatchers.IO) {
                    val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException(tr("Couldn't open the file", "ファイルを開けませんでした"))
                    out.use { Exporters.writeWav(it, mixer) }
                }
                // Audio is read from the original files; say so if any of them couldn't be read in full.
                val unreadable = used.filter { (it.pcm?.readErrors ?: 0) > errorsBefore.getValue(it) }
                message = if (unreadable.isEmpty()) tr("Audio exported", "オーディオを書き出しました")
                else tr(
                    "Exported, but parts of ${unreadable.joinToString { it.name }} couldn't be read and are silent",
                    "書き出しましたが、${unreadable.joinToString("、") { it.name }}の一部を読み込めなかったため無音になっています",
                )
            } catch (e: Exception) {
                message = tr("Export failed: ", "書き出しに失敗しました: ") + e.message
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

    /** Writes the Resolve timeline to [uri] as OpenTimelineIO. The media stays where it is. */
    fun exportResolve(uri: Uri, name: String) {
        if (clips.isEmpty()) { message = tr("Nothing to export yet", "書き出すものがまだありません"); return }
        stop()
        val rate = frameRate
        val folder = resolveMediaFolder
        val timeline = Exporters.Timeline(
            name, clips, sources, trackCount, videoTrackCount, mutedTracks, timelineWidth, timelineHeight,
            lockedAudio, lockedVideo, markers,
        )
        viewModelScope.launch {
            busy = tr("Saving Resolve timeline…", "Resolve用タイムラインを保存中…")
            try {
                withContext(Dispatchers.IO) {
                    val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException(tr("Couldn't open the file", "ファイルを開けませんでした"))
                    out.use {
                        Exporters.writeResolveOtio(it, timeline, rate, folder)
                    }
                }
                message = tr(
                    "Timeline saved. In Resolve: File > Import > Timeline",
                    "タイムラインを保存しました。Resolveで「ファイル > 読み込み > タイムライン」を選んでください",
                )
            } catch (e: Exception) {
                message = tr("Export failed: ", "書き出しに失敗しました: ") + e.message
            } finally {
                busy = null
            }
        }
    }

    override fun onCleared() {
        player.release()
        preview.release()
    }

    private companion object {
        const val KEY_CURVE = "defaultCurve"
        const val KEY_XFADE = "crossfadeSeconds"
        const val KEY_RIPPLE_ALL = "rippleAllTracks"
        const val KEY_PAUSE_ON_SEEK = "pauseOnSeek"
        const val KEY_RESOLVE_FOLDER = "resolveMediaFolder"
        const val KEY_MARKER_COLOR = "markerColor"
        const val AUTOSAVE_NAME = "Autosave"
        val UNTITLED_NAMES = setOf("Untitled", "無題")

        /** Older projects called the curved fade "EXPONENTIAL". */
        fun parseCurve(s: String) = if (s == FadeCurve.LINEAR.name) FadeCurve.LINEAR else FadeCurve.CURVED
    }
}
