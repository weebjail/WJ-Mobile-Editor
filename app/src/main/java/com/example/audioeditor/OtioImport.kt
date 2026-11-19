package com.example.audioeditor

import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Reads OpenTimelineIO (.otio) timelines, such as those DaVinci Resolve writes with File > Export > Timeline.
 *
 * Reading happens in two steps: [parse] turns the file into tracks of clips with times in seconds and the media
 * named by file name; once those files are imported, [toClips] turns them into timeline clips. Fades and
 * crossfades come across; titles, generators, compound clips and video transitions are left out.
 */
object OtioImport {

    /** A clip read from the file. Times are in seconds; [sourceIn] is measured from the start of the media file. */
    data class OClip(
        val media: String,
        val start: Double,
        val duration: Double,
        val sourceIn: Double,
        val fadeIn: Double = 0.0,
        val fadeOut: Double = 0.0,
        val volume: Float = 1f,   // clip volume as a multiplier (Resolve's Fairlight clip volume)
    )

    data class OTrack(val video: Boolean, val enabled: Boolean, val clips: List<OClip>)

    /** [skipped] counts items that can't be brought across (titles, generators, compound clips, disabled clips…). */
    data class OTimeline(val name: String, val rate: Double?, val tracks: List<OTrack>, val skipped: Int) {
        /** File names of the media the timeline uses, in order of first use. */
        val mediaNames: List<String> get() = tracks.flatMap { t -> t.clips.map { it.media } }.distinctBy { it.lowercase() }
        val clipCount: Int get() = tracks.sumOf { it.clips.size }
    }

    // ---------------------------------------------------------------- Reading the file

    fun parse(text: String): OTimeline {
        val root = MiniJson.parse(text) as? Map<*, *> ?: throw IllegalArgumentException(tr("Not an OTIO file", "OTIOファイルではありません"))
        val schema = root.schema()
        // A file may hold a single timeline, or a collection of them (we take the first).
        val timeline = when {
            schema.startsWith("Timeline.") -> root
            schema.startsWith("SerializableCollection.") ->
                (root["children"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { it.schema().startsWith("Timeline.") }
            else -> null
        } ?: throw IllegalArgumentException(tr("This file doesn't contain a timeline", "このファイルにはタイムラインが含まれていません"))

        var rate: Double? = (timeline["global_start_time"] as? Map<*, *>)?.rate()
        var skipped = 0
        val tracks = ArrayList<OTrack>()
        val stack = timeline["tracks"] as? Map<*, *> ?: throw IllegalArgumentException(tr("The timeline has no tracks", "タイムラインにトラックがありません"))
        for (t in (stack["children"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
            if (!t.schema().startsWith("Track.")) { skipped++; continue }
            val video = when (t["kind"]) {
                "Video" -> true
                "Audio" -> false
                else -> { skipped++; continue }
            }
            val r = readTrack(t, video)
            skipped += r.second
            if (rate == null) rate = r.third
            tracks += OTrack(video, t["enabled"] as? Boolean ?: true, r.first)
        }
        return OTimeline(timeline["name"] as? String ?: "Timeline", rate, tracks, skipped)
    }

    /** One track's clips, the number of items skipped, and the first frame rate seen. */
    private fun readTrack(t: Map<*, *>, video: Boolean): Triple<List<OClip>, Int, Double?> {
        // Items in order: a clip, null for anything else that takes up time (a gap), or a transition.
        class Tr(val inOffset: Double, val outOffset: Double)
        val items = ArrayList<Any?>()
        val clips = ArrayList<OClip>()
        var skipped = 0
        var rate: Double? = null
        var cursor = 0.0
        for (child in (t["children"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
            val schema = child.schema()
            when {
                schema.startsWith("Transition.") -> {
                    items += Tr((child["in_offset"] as? Map<*, *>).seconds(), (child["out_offset"] as? Map<*, *>).seconds())
                }
                schema.startsWith("Clip.") -> {
                    val ref = mediaReference(child)
                    val available = ref?.get("available_range") as? Map<*, *>
                    val range = child["source_range"] as? Map<*, *> ?: available
                    val dur = (range?.get("duration") as? Map<*, *>).seconds()
                    if (rate == null) rate = (range?.get("duration") as? Map<*, *>)?.rate()
                    val url = ref?.takeIf { it.schema().startsWith("ExternalReference.") }?.get("target_url") as? String
                    val enabled = child["enabled"] as? Boolean ?: true
                    if (url == null || !enabled || dur <= 0) {
                        skipped++
                        items += null
                    } else {
                        val start = (range?.get("start_time") as? Map<*, *>).seconds()
                        val mediaStart = (available?.get("start_time") as? Map<*, *>).seconds()
                        val c = OClip(fileName(url), cursor, dur, start - mediaStart, volume = if (video) 1f else clipVolume(child))
                        items += c
                        clips += c
                    }
                    cursor += dur
                }
                else -> {
                    // A gap, or something we can't bring across (e.g. a compound clip): it still takes up time.
                    if (!schema.startsWith("Gap.")) skipped++
                    cursor += duration(child)
                    items += null
                }
            }
        }

        // Transitions: on audio tracks they become fades and crossfades. Video dissolves become plain cuts.
        if (!video) {
            for ((i, item) in items.withIndex()) {
                if (item !is Tr) continue
                val len = item.inOffset + item.outOffset
                if (len <= 0) continue
                val prev = items.getOrNull(i - 1) as? OClip
                val next = items.getOrNull(i + 1) as? OClip
                val pi = prev?.let { p -> clips.indexOfFirst { it === p } } ?: -1
                val ni = next?.let { n -> clips.indexOfFirst { it === n } } ?: -1
                when {
                    pi >= 0 && ni >= 0 -> {
                        // A crossfade: the outgoing clip runs on past the cut, the incoming one starts before it.
                        val a = clips[pi]; val b = clips[ni]
                        clips[pi] = a.copy(duration = a.duration + item.outOffset, fadeOut = len)
                        clips[ni] = b.copy(
                            start = b.start - item.inOffset, sourceIn = b.sourceIn - item.inOffset,
                            duration = b.duration + item.inOffset, fadeIn = len,
                        )
                        items[i - 1] = clips[pi]; items[i + 1] = clips[ni]
                    }
                    ni >= 0 -> { clips[ni] = clips[ni].copy(fadeIn = len); items[i + 1] = clips[ni] }
                    pi >= 0 -> { clips[pi] = clips[pi].copy(fadeOut = len); items[i - 1] = clips[pi] }
                }
            }
        }
        return Triple(clips, skipped, rate)
    }

    /** The clip's media: older files have one reference, newer ones (Clip.2) a set with an active one. */
    private fun mediaReference(clip: Map<*, *>): Map<*, *>? {
        (clip["media_reference"] as? Map<*, *>)?.let { return it }
        val refs = clip["media_references"] as? Map<*, *> ?: return null
        val key = clip["active_media_reference_key"] as? String ?: "DEFAULT_MEDIA"
        return (refs[key] ?: refs.values.firstOrNull()) as? Map<*, *>
    }

    /** How long a non-clip item lasts: its own range, or (for a nested stack or track) its longest child. */
    private fun duration(item: Map<*, *>): Double {
        (item["source_range"] as? Map<*, *>)?.let { return (it["duration"] as? Map<*, *>).seconds() }
        val children = (item["children"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { !it.schema().startsWith("Transition.") }
        return if (item.schema().startsWith("Stack.")) children.maxOfOrNull { duration(it) } ?: 0.0
        else children.sumOf { c ->
            if (c.schema().startsWith("Clip.")) {
                ((c["source_range"] ?: (mediaReference(c)?.get("available_range"))) as? Map<*, *>)
                    ?.let { (it["duration"] as? Map<*, *>).seconds() } ?: 0.0
            } else duration(c)
        }
    }

    /**
     * The clip volume Resolve stores as a "Fairlight Clip Volume and Fades" effect: its "volume" parameter, in dB
     * (absent when the volume is left at 0 dB). Keyframed volume isn't supported; the base level is used.
     */
    internal fun clipVolume(clip: Map<*, *>): Float {
        for (e in (clip["effects"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
            val r = (e["metadata"] as? Map<*, *>)?.get("Resolve_OTIO") as? Map<*, *> ?: continue
            if (r["Effect Name"] != "Fairlight Clip Volume and Fades" || r["Enabled"] == false) continue
            val p = (r["Parameters"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
                .firstOrNull { it["Parameter ID"] == "volume" } ?: continue
            val db = (p["Parameter Value"] as? Number)?.toFloat() ?: continue
            return if (db <= -100f) 0f else dbToVolume(db).coerceIn(0f, MAX_CLIP_VOLUME)
        }
        return 1f
    }

    private fun Map<*, *>.schema(): String = this["OTIO_SCHEMA"] as? String ?: ""

    private fun Map<*, *>.rate(): Double? = (this["rate"] as? Double)?.takeIf { it > 0 }

    /** A RationalTime in seconds (0 when missing). */
    private fun Map<*, *>?.seconds(): Double {
        if (this == null) return 0.0
        val v = this["value"] as? Double ?: return 0.0
        val r = rate() ?: return 0.0
        return v / r
    }

    /** "file:///D:/Shoots/Day%201/C0001.MP4", "C0001.MP4" or "D:\Shoots\C0001.MP4" -> "C0001.MP4". */
    internal fun fileName(url: String): String {
        val last = url.substringAfterLast('/').substringAfterLast('\\')
        val decoded = if ('%' in last) {
            try { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { last }
        } else last
        return decoded.ifEmpty { url }
    }

    // ---------------------------------------------------------------- Building timeline clips

    /** What [toClips] needs to know about an imported media file. */
    class Media(val sourceId: Long, val hasVideo: Boolean, val videoFrames: Long, val audioFrames: Long)

    class Result(
        val clips: List<Clip>,
        val videoTracksUsed: Int,       // highest video track used, plus one (0 = none)
        val audioTracksUsed: Int,
        val mutedAudio: Set<Int>,       // tracks switched off in the file
        val skipped: Int,               // clips left out (media missing, or not the right kind)
    )

    /**
     * Timeline clips for [t], starting at [offset] (48 kHz frames), with track numbers starting at [videoBase] and
     * [audioBase]. [media] gives each file's imported source (by lower-case file name); clips whose media is
     * missing are left out. A video and the audio taken from the same file in sync with it are linked, as in Resolve.
     */
    fun toClips(
        t: OTimeline,
        media: Map<String, Media>,
        offset: Long,
        videoBase: Int,
        audioBase: Int,
        curve: FadeCurve,
        newId: () -> Long,
    ): Result {
        val out = ArrayList<Clip>()
        var skipped = 0
        var vUsed = 0
        var aUsed = 0
        val muted = HashSet<Int>()
        fun frames(s: Double) = (s * SAMPLE_RATE).roundToLong()
        var vIndex = 0
        var aIndex = 0
        for (track in t.tracks) {
            val index = if (track.video) vIndex++ else aIndex++
            val trackNo = (if (track.video) videoBase else audioBase) + index
            for (c in track.clips) {
                val m = media[c.media.lowercase()]
                val available = if (track.video) m?.videoFrames?.takeIf { m.hasVideo } else m?.audioFrames
                if (m == null || available == null || available <= 0) { skipped++; continue }
                var start = offset + frames(c.start)
                var sourceIn = frames(c.sourceIn)
                var length = frames(c.duration)
                // Keep within the media actually in the file (a clip can't start before the file does).
                if (sourceIn < 0) { start -= sourceIn; length += sourceIn; sourceIn = 0 }
                if (start < 0) { sourceIn -= start; length += start; start = 0 }
                length = minOf(length, available - sourceIn)
                if (length <= 0) { skipped++; continue }
                out += Clip(
                    newId(), m.sourceId, trackNo, start, sourceIn, length,
                    Fade(frames(c.fadeIn), curve), Fade(frames(c.fadeOut), curve), video = track.video,
                    volume = c.volume,
                ).withValidFades()
                if (track.video) vUsed = maxOf(vUsed, index + 1) else aUsed = maxOf(aUsed, index + 1)
                if (!track.video && !track.enabled) muted += trackNo
            }
        }
        return Result(link(out, newId), vUsed, aUsed, muted, skipped)
    }

    /**
     * Links each video clip with the audio clips cut from the same file in sync with it (same offset between file
     * and timeline, overlapping in time), like a camera clip and its sound in Resolve.
     */
    private fun link(clips: List<Clip>, newId: () -> Long): List<Clip> {
        val tolerance = SAMPLE_RATE / 100L   // well under a frame
        val linkOf = HashMap<Long, Long>()
        for (v in clips.filter { it.video }.sortedBy { it.start }) {
            val partners = clips.filter { a ->
                !a.video && a.id !in linkOf && a.sourceId == v.sourceId &&
                    abs((a.start - a.sourceIn) - (v.start - v.sourceIn)) <= tolerance &&
                    a.start < v.end && a.end > v.start
            }
            if (partners.isEmpty()) continue
            val link = newId()
            linkOf[v.id] = link
            for (a in partners) linkOf[a.id] = link
        }
        return clips.map { c -> linkOf[c.id]?.let { c.copy(linkId = it) } ?: c }
    }
}

/** A small JSON reader: objects become maps, arrays lists, numbers doubles. */
internal object MiniJson {
    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) p.fail(tr("Unexpected text after the end", "末尾の後に余分なテキストがあります"))
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException(tr("Not a valid OTIO file ($msg at character $i)", "有効なOTIOファイルではありません（${i}文字目: $msg）"))

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            if (i >= s.length) fail(tr("Unexpected end", "予期しない終端"))
            return when (val ch = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                // Python's JSON writer (which OTIO uses) can write these.
                'N' -> word("NaN", Double.NaN)
                'I' -> word("Infinity", Double.POSITIVE_INFINITY)
                else -> if (ch == '-' || ch.isDigit()) num() else fail(tr("Unexpected '$ch'", "予期しない文字 '$ch'"))
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail(tr("Unexpected text", "予期しないテキスト"))
            i += w.length
            return v
        }

        fun num(): Double {
            val from = i
            if (s.startsWith("-Infinity", i)) { i += 9; return Double.NEGATIVE_INFINITY }
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(from, i).toDoubleOrNull() ?: fail(tr("Bad number", "不正な数値"))
        }

        fun str(): String {
            i++   // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail(tr("Unterminated string", "文字列が閉じられていません"))
                val ch = s[i++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail(tr("Unterminated string", "文字列が閉じられていません"))
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) fail(tr("Bad escape", "不正なエスケープ"))
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail(tr("Bad escape", "不正なエスケープ")))
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail(tr("Expected a name", "名前が必要です"))
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail(tr("Expected ':'", "':' が必要です"))
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) fail(tr("Unexpected end", "予期しない終端"))
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> { i--; fail(tr("Expected ',' or '}'", "',' または '}' が必要です")) }
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l += value()
                ws()
                if (i >= s.length) fail(tr("Unexpected end", "予期しない終端"))
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> { i--; fail(tr("Expected ',' or ']'", "',' または ']' が必要です")) }
                }
            }
        }
    }
}
