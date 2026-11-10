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

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Percent-encodes characters that would break a file:// URL. */
    private fun urlPath(s: String): String = buildString {
        for (ch in s) when {
            ch.isLetterOrDigit() || ch in "/-_.~:()" -> append(ch)
            else -> ch.toString().toByteArray(Charsets.UTF_8).forEach { append("%%%02X".format(it.toInt() and 0xFF)) }
        }
    }

    /** One clip as it will be written: start/end may be moved to a crossfade's centre. */
    private class Item(val clip: Clip, var start: Long, var end: Long, var fadeIn: Boolean, var fadeOut: Boolean)

    /** A centred crossfade between two overlapping clips in the same lane. */
    private class Xfade(val start: Long, val end: Long, val curve: FadeCurve)

    internal fun buildXml(t: Timeline, rate: FrameRate, mediaFolder: String): String {
        val rateXml = "<rate><timebase>${rate.timebase}</timebase><ntsc>${if (rate.ntsc) "TRUE" else "FALSE"}</ntsc></rate>"
        val total = rate.toFrames(t.clips.maxOf { it.end })
        // "D:\Shoots\Day 1" or "/Users/me/Shoots" -> "D:/Shoots/Day 1/" or "Users/me/Shoots/"
        val prefix = mediaFolder.trim().replace('\\', '/').trimStart('/')
            .let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
        val width = t.width
        val height = t.height

        val defined = HashSet<Long>()
        var clipNum = 1

        fun fileXml(sb: StringBuilder, src: AudioSource, sourceId: Long) {
            val fileName = src.name
            val srcDuration = rate.toFrames(src.frames)
            if (!defined.add(sourceId)) {
                sb.append("<file id=\"file-$sourceId\"/>\n")
                return
            }
            sb.append("<file id=\"file-$sourceId\">\n<name>${esc(fileName)}</name>\n")
            sb.append("<pathurl>${esc("file://localhost/" + urlPath(prefix + fileName))}</pathurl>\n$rateXml\n")
            sb.append("<duration>$srcDuration</duration>\n")
            // The recorder's own timecode, so Resolve sees the same start time as when it reads the file.
            src.startSeconds?.let { s ->
                val f = Math.floor(s * rate.fps + 1e-6).toLong()
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
            val items = laneClips.sortedBy { it.start }.map { Item(it, it.start, it.end, it.fadeIn.frames > 0, it.fadeOut.frames > 0) }
            val xfades = HashMap<Int, Xfade>()   // crossfade after item i
            if (!isVideo) {
                for (i in TimelineOps.crossfadePairs(items.map { it.clip })) {
                    val a = items[i]; val b = items[i + 1]
                    val cut = (b.clip.start + a.clip.end) / 2
                    a.end = cut; a.fadeOut = false
                    b.start = cut; b.fadeIn = false
                    xfades[i] = Xfade(b.clip.start, a.clip.end, a.clip.fadeOut.curve)
                }
            }
            for ((i, it) in items.withIndex()) {
                val c = it.clip
                val src = t.sources[c.sourceId] ?: continue
                val start = rate.toFrames(it.start)
                val end = rate.toFrames(it.end)
                val len = end - start
                if (len <= 0) continue
                // The source position comes from the clip's (constant) offset between source and timeline, so pieces
                // of one recording cut apart stay frame-continuous: each piece starts where the last one ended.
                val srcDur = rate.toFrames(src.frames)
                val inF = (start + rate.toFrames(c.sourceIn - c.start)).coerceAtMost(srcDur - len).coerceAtLeast(0)
                val fileName = src.name

                if (it.fadeIn) {
                    val f = maxOf(1L, rate.toFrames(c.fadeIn.frames)).coerceAtMost(len)
                    appendTransition(sb, rateXml, start, start + f, "start-black", c.fadeIn.curve)
                }
                sb.append("<clipitem id=\"clipitem-${clipNum++}\">\n<name>${esc(fileName)}</name>\n<enabled>TRUE</enabled>\n")
                sb.append("<duration>${rate.toFrames(src.frames)}</duration>\n$rateXml\n")
                sb.append("<start>$start</start><end>$end</end><in>$inF</in><out>${inF + len}</out>\n")
                fileXml(sb, src, c.sourceId)
                if (!isVideo) {
                    sb.append("<sourcetrack><mediatype>audio</mediatype><trackindex>1</trackindex></sourcetrack>\n")
                }
                sb.append("</clipitem>\n")
                if (it.fadeOut) {
                    val f = maxOf(1L, rate.toFrames(c.fadeOut.frames)).coerceAtMost(len)
                    appendTransition(sb, rateXml, end - f, end, "end-black", c.fadeOut.curve)
                }
                xfades[i]?.let { x ->
                    appendTransition(sb, rateXml, rate.toFrames(x.start), rate.toFrames(x.end), "center", x.curve)
                }
            }
            sb.append("<enabled>${if (enabled) "TRUE" else "FALSE"}</enabled><locked>${if (locked) "TRUE" else "FALSE"}</locked>\n</track>\n")
        }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE xmeml>\n<xmeml version=\"5\">\n")
        sb.append("<sequence id=\"sequence-1\">\n<name>${esc(t.name)}</name>\n<duration>$total</duration>\n$rateXml\n")
        sb.append("<timecode>$rateXml<string>00:00:00:00</string><frame>0</frame><displayformat>NDF</displayformat></timecode>\n")
        sb.append("<media>\n")
        sb.append("<video>\n<format><samplecharacteristics>$rateXml<width>$width</width><height>$height</height>")
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
}
