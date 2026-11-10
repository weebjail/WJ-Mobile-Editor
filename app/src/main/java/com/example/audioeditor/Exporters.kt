package com.example.audioeditor

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

enum class FrameRate(val label: String, val timebase: Int, val ntsc: Boolean) {
    F23_976("23.976", 24, true),
    F24("24", 24, false),
    F25("25", 25, false),
    F29_97("29.97", 30, true),
    F30("30", 30, false),
    F50("50", 50, false),
    F59_94("59.94", 60, true),
    F60("60", 60, false);

    val fps: Double get() = if (ntsc) timebase * 1000.0 / 1001.0 else timebase.toDouble()

    /** XML timelines count in video frames, so audio positions are rounded to the nearest frame. */
    fun toFrames(samples: Long): Long = Math.round(samples * fps / SAMPLE_RATE)

    companion object {
        /** The listed rate closest to a video file's own frame rate. */
        fun closestTo(fps: Double): FrameRate = entries.minBy { kotlin.math.abs(it.fps - fps) }
    }
}

object Exporters {

    /** Writes the whole mix as a 48 kHz / 24-bit stereo WAV file. */
    fun writeWav(out: OutputStream, mixer: Mixer) {
        val total = mixer.totalFrames
        val dataSize = total * 6
        if (dataSize + 36 > 0xFFFFFFFFL) throw IOException("The timeline is too long for a WAV file")
        val o = BufferedOutputStream(out, 1 shl 16)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII)); h.putInt((36 + dataSize).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII)); h.putInt(16)
        h.putShort(1.toShort())            // PCM
        h.putShort(2.toShort())            // stereo
        h.putInt(SAMPLE_RATE)
        h.putInt(SAMPLE_RATE * 6)          // bytes per second
        h.putShort(6.toShort())            // bytes per frame
        h.putShort(24.toShort())           // bits
        h.put("data".toByteArray(Charsets.US_ASCII)); h.putInt(dataSize.toInt())
        o.write(h.array())

        val block = 8192
        val buf = FloatArray(block * 2)
        val bytes = ByteArray(block * 6)
        var pos = 0L
        while (pos < total) {
            val n = minOf(block.toLong(), total - pos).toInt()
            mixer.render(pos, n, buf)
            var j = 0
            for (i in 0 until n * 2) {
                val v = (buf[i] * 8388607f).roundToInt().coerceIn(-8388608, 8388607)
                bytes[j++] = v.toByte()
                bytes[j++] = (v shr 8).toByte()
                bytes[j++] = (v shr 16).toByte()
            }
            o.write(bytes, 0, n * 6)
            pos += n
        }
        o.flush()
    }

    class Timeline(
        val name: String,
        val clips: List<Clip>,
        val sources: Map<Long, AudioSource>,
        val audioTracks: Int,
        val videoTracks: Int,
        val mutedTracks: Set<Int>,
        val width: Int,
        val height: Int,
        val lockedAudio: Set<Int> = emptySet(),
        val lockedVideo: Set<Int> = emptySet(),
    )

    /**
     * Writes an FCP7-style XML timeline that DaVinci Resolve can import (File > Import > Timeline).
     * Media files aren't copied: the XML refers to them by name, inside [mediaFolder] (the folder they'll
     * be in on the computer, e.g. "D:/Shoots/Day 1"). With no folder, Resolve matches clips by file name
     * against media already in its Media Pool.
     */
    fun writeResolveXml(out: OutputStream, t: Timeline, rate: FrameRate, mediaFolder: String) {
        out.write(buildXml(t, rate, mediaFolder).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** "HH:MM:SS:FF" for [frames] counted at [timebase] (non-drop-frame). */
    internal fun timecode(frames: Long, timebase: Int): String {
        val ff = frames % timebase
        val totalSec = frames / timebase
        return "%02d:%02d:%02d:%02d".format(totalSec / 3600 % 24, totalSec / 60 % 60, totalSec % 60, ff)
    }


    /**
     * Writes the timeline as OpenTimelineIO (.otio), which Resolve 18.5 and later imports with
     * File > Import > Timeline. Media is referred to inside [mediaFolder] when given; otherwise by file
     * name alone (a relative reference, so it can be found next to the .otio).
     */
    fun writeResolveOtio(out: OutputStream, t: Timeline, rate: FrameRate, mediaFolder: String) {
        out.write(buildOtio(t, rate, mediaFolder).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // ---------------------------------------------------------------- Shared timeline layout

    /** A crossfade, in timeline frames. */
    private class Xfade(val start: Long, val end: Long, val curve: FadeCurve)

    /**
     * One clip as it will be written, in timeline frames. Clips that crossfade are cut at the crossfade's centre,
     * which is how editors store a centred transition. [xfade] is the crossfade into the next placed clip.
     */
    private class Placed(
        val clip: Clip, val src: AudioSource,
        val start: Long, val end: Long, val inF: Long,
        val fadeIn: Long, val fadeOut: Long,   // frames; 0 = none
        var xfade: Xfade? = null,
    ) {
        val len get() = end - start
    }

    /** "D:\Shoots\Day 1" or "/Users/me/Shoots" -> "D:/Shoots/Day 1/" or "Users/me/Shoots/" ("" stays ""). */
    private fun folderPrefix(mediaFolder: String) = mediaFolder.trim().replace('\\', '/').trimStart('/')
        .let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }

    /** The recorder's own start timecode, in timeline frames (0 when the file has none). */
    private fun startTimecodeFrames(src: AudioSource, rate: FrameRate): Long =
        src.startSeconds?.let { Math.floor(it * rate.fps + 1e-6).toLong() } ?: 0L

    private fun layoutLane(t: Timeline, laneClips: List<Clip>, isVideo: Boolean, rate: FrameRate): List<Placed> {
        val sorted = laneClips.sortedBy { it.start }
        val starts = LongArray(sorted.size) { sorted[it].start }
        val ends = LongArray(sorted.size) { sorted[it].end }
        val noFadeIn = BooleanArray(sorted.size)
        val noFadeOut = BooleanArray(sorted.size)
        val xfades = HashMap<Int, Xfade>()   // between sorted[i] and sorted[i + 1]
        if (!isVideo) {
            for (i in TimelineOps.crossfadePairs(sorted)) {
                val a = sorted[i]; val b = sorted[i + 1]
                val cut = (b.start + a.end) / 2
                ends[i] = cut; noFadeOut[i] = true
                starts[i + 1] = cut; noFadeIn[i + 1] = true
                xfades[i] = Xfade(rate.toFrames(b.start), rate.toFrames(a.end), a.fadeOut.curve)
            }
        }
        val placed = ArrayList<Placed>()
        var prevIndex = -1
        for ((i, c) in sorted.withIndex()) {
            val src = t.sources[c.sourceId] ?: continue
            val start = rate.toFrames(starts[i])
            val end = rate.toFrames(ends[i])
            val len = end - start
            if (len <= 0) continue   // shorter than a frame: can't be represented
            // The source position comes from the clip's (constant) offset between source and timeline, so pieces
            // of one recording cut apart stay frame-continuous: each piece starts where the last one ended.
            val srcDur = rate.toFrames(src.frames)
            val inF = (start + rate.toFrames(c.sourceIn - c.start)).coerceAtMost(srcDur - len).coerceAtLeast(0)
            val fadeIn = if (noFadeIn[i] || c.fadeIn.frames <= 0) 0L else maxOf(1L, rate.toFrames(c.fadeIn.frames)).coerceAtMost(len)
            val fadeOut = if (noFadeOut[i] || c.fadeOut.frames <= 0) 0L else maxOf(1L, rate.toFrames(c.fadeOut.frames)).coerceAtMost(len)
            // A crossfade is kept only when both of its clips made it in.
            if (prevIndex == i - 1) placed.lastOrNull()?.xfade = xfades[i - 1]
            placed += Placed(c, src, start, end, inF, fadeIn, fadeOut)
            prevIndex = i
        }
        return placed
    }

    // ---------------------------------------------------------------- FCP7 XML

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Percent-encodes characters that would break a file:// URL. */
    private fun urlPath(s: String): String = buildString {
        for (ch in s) when {
            ch.isLetterOrDigit() || ch in "/-_.~:()" -> append(ch)
            else -> ch.toString().toByteArray(Charsets.UTF_8).forEach { append("%%%02X".format(it.toInt() and 0xFF)) }
        }
    }

    internal fun buildXml(t: Timeline, rate: FrameRate, mediaFolder: String): String {
        val rateXml = "<rate><timebase>${rate.timebase}</timebase><ntsc>${if (rate.ntsc) "TRUE" else "FALSE"}</ntsc></rate>"
        val total = rate.toFrames(t.clips.maxOf { it.end })
        val prefix = folderPrefix(mediaFolder)
        val defined = HashSet<Long>()
        var clipNum = 1

        fun fileXml(sb: StringBuilder, src: AudioSource) {
            if (!defined.add(src.id)) {
                sb.append("<file id=\"file-${src.id}\"/>\n")
                return
            }
            sb.append("<file id=\"file-${src.id}\">\n<name>${esc(src.name)}</name>\n")
            sb.append("<pathurl>${esc("file://localhost/" + urlPath(prefix + src.name))}</pathurl>\n$rateXml\n")
            sb.append("<duration>${rate.toFrames(src.frames)}</duration>\n")
            // The recorder's own timecode, so Resolve sees the same start time as when it reads the file.
            if (src.startSeconds != null) {
                val f = startTimecodeFrames(src, rate)
                sb.append("<timecode>$rateXml<string>${timecode(f, rate.timebase)}</string><frame>$f</frame>")
                sb.append("<displayformat>NDF</displayformat><source>source</source></timecode>\n")
            }
            sb.append("<media>")
            src.video?.let {
                sb.append("<video><samplecharacteristics>$rateXml<width>${it.width}</width><height>${it.height}</height>")
                sb.append("</samplecharacteristics></video>")
            }
            if (src.hasAudio) {
                sb.append("<audio><samplecharacteristics><depth>${src.originalBitDepth}</depth>")
                sb.append("<samplerate>${src.originalSampleRate}</samplerate></samplecharacteristics>")
                sb.append("<channelcount>${src.originalChannels}</channelcount></audio>")
            }
            sb.append("</media>\n</file>\n")
        }

        fun lane(sb: StringBuilder, laneClips: List<Clip>, isVideo: Boolean, enabled: Boolean, locked: Boolean) {
            sb.append("<track>\n")
            for (p in layoutLane(t, laneClips, isVideo, rate)) {
                if (p.fadeIn > 0) appendTransition(sb, rateXml, p.start, p.start + p.fadeIn, "start-black", p.clip.fadeIn.curve)
                sb.append("<clipitem id=\"clipitem-${clipNum++}\">\n<name>${esc(p.src.name)}</name>\n<enabled>TRUE</enabled>\n")
                sb.append("<duration>${rate.toFrames(p.src.frames)}</duration>\n$rateXml\n")
                sb.append("<start>${p.start}</start><end>${p.end}</end><in>${p.inF}</in><out>${p.inF + p.len}</out>\n")
                fileXml(sb, p.src)
                if (!isVideo) sb.append("<sourcetrack><mediatype>audio</mediatype><trackindex>1</trackindex></sourcetrack>\n")
                sb.append("</clipitem>\n")
                if (p.fadeOut > 0) appendTransition(sb, rateXml, p.end - p.fadeOut, p.end, "end-black", p.clip.fadeOut.curve)
                p.xfade?.let { x -> appendTransition(sb, rateXml, x.start, x.end, "center", x.curve) }
            }
            sb.append("<enabled>${if (enabled) "TRUE" else "FALSE"}</enabled><locked>${if (locked) "TRUE" else "FALSE"}</locked>\n</track>\n")
        }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE xmeml>\n<xmeml version=\"5\">\n")
        sb.append("<sequence id=\"sequence-1\">\n<name>${esc(t.name)}</name>\n<duration>$total</duration>\n$rateXml\n")
        sb.append("<timecode>$rateXml<string>00:00:00:00</string><frame>0</frame><displayformat>NDF</displayformat></timecode>\n")
        sb.append("<media>\n")
        sb.append("<video>\n<format><samplecharacteristics>$rateXml<width>${t.width}</width><height>${t.height}</height>")
        sb.append("<pixelaspectratio>square</pixelaspectratio></samplecharacteristics></format>\n")
        for (v in 0 until t.videoTracks) lane(sb, t.clips.filter { it.video && it.track == v }, true, true, v in t.lockedVideo)
        sb.append("</video>\n")
        sb.append("<audio>\n<numOutputChannels>2</numOutputChannels>\n")
        sb.append("<format><samplecharacteristics><depth>24</depth><samplerate>$SAMPLE_RATE</samplerate></samplecharacteristics></format>\n")
        for (a in 0 until t.audioTracks) lane(sb, t.clips.filter { !it.video && it.track == a }, false, a !in t.mutedTracks, a in t.lockedAudio)
        sb.append("</audio>\n</media>\n</sequence>\n</xmeml>\n")
        return sb.toString()
    }

    /** Fades and crossfades become Resolve audio transitions with the matching curve. */
    private fun appendTransition(sb: StringBuilder, rateXml: String, start: Long, end: Long, alignment: String, curve: FadeCurve) {
        val (name, id) = if (curve == FadeCurve.LINEAR) {
            "Cross Fade (0dB)" to "KGAudioTransCrossFade0dB"
        } else {
            "Cross Fade (+3dB)" to "KGAudioTransCrossFade3dB"
        }
        sb.append("<transitionitem>\n$rateXml\n<start>$start</start><end>$end</end><alignment>$alignment</alignment>\n")
        sb.append("<effect><name>$name</name><effectid>$id</effectid><effectcategory>Dissolve</effectcategory>")
        sb.append("<effecttype>transition</effecttype><mediatype>audio</mediatype></effect>\n</transitionitem>\n")
    }

    // ---------------------------------------------------------------- OpenTimelineIO

    /** A JSON string literal. */
    private fun js(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch < ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }

    /**
     * OTIO's file format, written by hand rather than with a JSON library so numbers keep the form OTIO writes
     * (times as floats such as 1234.0) and the schemas stay the oldest, most widely readable versions.
     */
    internal fun buildOtio(t: Timeline, rate: FrameRate, mediaFolder: String): String {
        val r = rate.fps.toString()
        fun time(v: Long) = """{"OTIO_SCHEMA": "RationalTime.1", "rate": $r, "value": $v.0}"""
        fun range(start: Long, dur: Long) =
            """{"OTIO_SCHEMA": "TimeRange.1", "duration": ${time(dur)}, "start_time": ${time(start)}}"""
        val prefix = folderPrefix(mediaFolder)
        // With a folder, a full file:// URL; without one, the bare file name (relative to the .otio).
        fun url(name: String) = if (prefix.isEmpty()) name else "file:///" + urlPath(prefix + name)

        fun transition(inOffset: Long, outOffset: Long, curve: FadeCurve) =
            """{"OTIO_SCHEMA": "Transition.1", "name": ${js(if (curve == FadeCurve.LINEAR) "Cross Fade (0dB)" else "Cross Fade (+3dB)")}, """ +
                """"transition_type": "SMPTE_Dissolve", "in_offset": ${time(inOffset)}, "out_offset": ${time(outOffset)}, "metadata": {}}"""

        fun gap(dur: Long) =
            """{"OTIO_SCHEMA": "Gap.1", "name": "", "source_range": ${range(0, dur)}, "effects": [], "markers": [], "metadata": {}}"""

        fun clip(p: Placed): String {
            val tc = startTimecodeFrames(p.src, rate)
            val media = """{"OTIO_SCHEMA": "ExternalReference.1", "name": ${js(p.src.name)}, """ +
                """"target_url": ${js(url(p.src.name))}, "available_range": ${range(tc, rate.toFrames(p.src.frames))}, "metadata": {}}"""
            // Source times count from the file's own start timecode, as in Resolve's own OTIO files.
            return """{"OTIO_SCHEMA": "Clip.1", "name": ${js(p.src.name)}, "source_range": ${range(tc + p.inF, p.len)}, """ +
                """"media_reference": $media, "effects": [], "markers": [], "enabled": true, "metadata": {}}"""
        }

        fun track(name: String, kind: String, laneClips: List<Clip>, isVideo: Boolean, enabled: Boolean): String {
            val items = ArrayList<String>()
            var cursor = 0L
            for (p in layoutLane(t, laneClips, isVideo, rate)) {
                if (p.start > cursor) items += gap(p.start - cursor)
                if (p.fadeIn > 0) items += transition(0, p.fadeIn, p.clip.fadeIn.curve)
                items += clip(p)
                if (p.fadeOut > 0) items += transition(p.fadeOut, 0, p.clip.fadeOut.curve)
                // A centred crossfade: the part before the cut comes from this clip, the rest from the next.
                p.xfade?.let { x -> items += transition(p.end - x.start, x.end - p.end, x.curve) }
                cursor = maxOf(cursor, p.end)
            }
            return """{"OTIO_SCHEMA": "Track.1", "name": ${js(name)}, "kind": "$kind", "enabled": $enabled, """ +
                """"source_range": null, "effects": [], "markers": [], "metadata": {}, "children": [""" +
                items.joinToString(",\n") + "]}"
        }

        val tracks = ArrayList<String>()
        for (v in 0 until t.videoTracks) tracks += track("V${v + 1}", "Video", t.clips.filter { it.video && it.track == v }, true, true)
        for (a in 0 until t.audioTracks) {
            tracks += track("A${a + 1}", "Audio", t.clips.filter { !it.video && it.track == a }, false, a !in t.mutedTracks)
        }
        return """{"OTIO_SCHEMA": "Timeline.1", "name": ${js(t.name)}, "global_start_time": ${time(0)}, "metadata": {}, """ +
            """"tracks": {"OTIO_SCHEMA": "Stack.1", "name": "tracks", "source_range": null, "effects": [], "markers": [], """ +
            """"metadata": {}, "children": [""" + tracks.joinToString(",\n") + "]}}\n"
    }
}
