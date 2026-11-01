package com.example.audioeditor

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
    F30("30", 30, false);

    val fps: Double get() = if (ntsc) timebase * 1000.0 / 1001.0 else timebase.toDouble()

    /** XML timelines count in video frames, so audio positions are rounded to the nearest frame. */
    fun toFrames(samples: Long): Long = Math.round(samples * fps / SAMPLE_RATE)
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

    /**
     * Saves into the chosen folder: a copy of every audio file used, plus an FCP7-style XML timeline
     * that DaVinci Resolve can import (File > Import > Timeline).
     */
    fun writeResolvePackage(
        context: Context,
        tree: Uri,
        projectName: String,
        clips: List<Clip>,
        sources: Map<Long, AudioSource>,
        trackCount: Int,
        rate: FrameRate,
        pathPrefix: String,
    ) {
        val cr = context.contentResolver
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

        val fileNames = HashMap<Long, String>()
        for (id in clips.map { it.sourceId }.toSet()) {
            val src = sources[id] ?: continue
            val mime = cr.getType(src.uri) ?: "application/octet-stream"
            val doc = DocumentsContract.createDocument(cr, dir, mime, src.name)
                ?: throw IOException("Couldn't create ${src.name}")
            val input = cr.openInputStream(src.uri) ?: throw IOException("Couldn't read ${src.name}")
            input.use { ins ->
                val out = cr.openOutputStream(doc) ?: throw IOException("Couldn't write ${src.name}")
                out.use { ins.copyTo(it, 1 shl 16) }
            }
            // The folder may rename the copy (e.g. "take (1).wav"), so use the real name.
            fileNames[id] = AudioDecoder.displayName(context, doc)
        }

        val xml = buildXml(projectName, clips, sources, fileNames, trackCount, rate, pathPrefix)
        val xmlDoc = DocumentsContract.createDocument(cr, dir, "text/xml", "$projectName.xml")
            ?: throw IOException("Couldn't create the XML file")
        val out = cr.openOutputStream(xmlDoc) ?: throw IOException("Couldn't write the XML file")
        out.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
    }

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun buildXml(
        name: String,
        clips: List<Clip>,
        sources: Map<Long, AudioSource>,
        fileNames: Map<Long, String>,
        trackCount: Int,
        rate: FrameRate,
        pathPrefix: String,
    ): String {
        val rateXml = "<rate><timebase>${rate.timebase}</timebase><ntsc>${if (rate.ntsc) "TRUE" else "FALSE"}</ntsc></rate>"
        val total = rate.toFrames(clips.maxOf { it.end })
        val prefix = pathPrefix.trim().replace('\\', '/').trimStart('/')
            .let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE xmeml>\n<xmeml version=\"5\">\n")
        sb.append("<sequence id=\"sequence-1\">\n<name>${esc(name)}</name>\n<duration>$total</duration>\n$rateXml\n")
        sb.append("<timecode>$rateXml<string>00:00:00:00</string><frame>0</frame><displayformat>NDF</displayformat></timecode>\n")
        sb.append("<media>\n")
        sb.append("<video><format><samplecharacteristics>$rateXml<width>1920</width><height>1080</height>")
        sb.append("<pixelaspectratio>square</pixelaspectratio></samplecharacteristics></format></video>\n")
        sb.append("<audio>\n<numOutputChannels>2</numOutputChannels>\n")
        sb.append("<format><samplecharacteristics><depth>24</depth><samplerate>$SAMPLE_RATE</samplerate></samplecharacteristics></format>\n")

        val defined = HashSet<Long>()
        var clipNum = 1
        for (t in 0 until trackCount) {
            sb.append("<track>\n")
            for (c in clips.filter { it.track == t }.sortedBy { it.start }) {
                val src = sources[c.sourceId] ?: continue
                val start = rate.toFrames(c.start)
                val end = rate.toFrames(c.end)
                val len = end - start
                if (len <= 0) continue
                val inF = rate.toFrames(c.sourceIn)
                val outF = inF + len
                val fileName = fileNames[c.sourceId] ?: src.name
                val srcDuration = rate.toFrames(src.frames)

                if (c.fadeIn.frames > 0) {
                    val f = maxOf(1L, rate.toFrames(c.fadeIn.frames)).coerceAtMost(len)
                    appendFade(sb, rateXml, start, start + f, "start-black", c.fadeIn.curve)
                }
                sb.append("<clipitem id=\"clipitem-${clipNum++}\">\n<name>${esc(fileName)}</name>\n<enabled>TRUE</enabled>\n")
                sb.append("<duration>$srcDuration</duration>\n$rateXml\n")
                sb.append("<start>$start</start><end>$end</end><in>$inF</in><out>$outF</out>\n")
                if (defined.add(c.sourceId)) {
                    val path = (prefix + fileName).replace(" ", "%20")
                    sb.append("<file id=\"file-${c.sourceId}\">\n<name>${esc(fileName)}</name>\n")
                    sb.append("<pathurl>${esc("file://localhost/$path")}</pathurl>\n$rateXml\n<duration>$srcDuration</duration>\n")
                    sb.append("<media><audio><samplecharacteristics><depth>${src.originalBitDepth}</depth>")
                    sb.append("<samplerate>${src.originalSampleRate}</samplerate></samplecharacteristics>")
                    sb.append("<channelcount>${src.originalChannels}</channelcount></audio></media>\n</file>\n")
                } else {
                    sb.append("<file id=\"file-${c.sourceId}\"/>\n")
                }
                sb.append("<sourcetrack><mediatype>audio</mediatype><trackindex>1</trackindex></sourcetrack>\n</clipitem>\n")
                if (c.fadeOut.frames > 0) {
                    val f = maxOf(1L, rate.toFrames(c.fadeOut.frames)).coerceAtMost(len)
                    appendFade(sb, rateXml, end - f, end, "end-black", c.fadeOut.curve)
                }
            }
            sb.append("<enabled>TRUE</enabled><locked>FALSE</locked>\n</track>\n")
        }
        sb.append("</audio>\n</media>\n</sequence>\n</xmeml>\n")
        return sb.toString()
    }

    /** Fades become audio transitions in Resolve. The curve shape is approximated (see NOTES.md). */
    private fun appendFade(sb: StringBuilder, rateXml: String, start: Long, end: Long, alignment: String, curve: FadeCurve) {
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
