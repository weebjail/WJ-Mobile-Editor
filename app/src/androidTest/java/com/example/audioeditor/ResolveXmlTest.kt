package com.example.audioeditor

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

/** The DaVinci Resolve timeline: valid XML, correct media paths, timecode, and frame-continuous cuts. */
@RunWith(AndroidJUnit4::class)
class ResolveXmlTest {

    private val minute = 60L * SAMPLE_RATE

    private fun source(id: Long, name: String, frames: Long, start: Double? = null, rate: Int = 96000, channels: Int = 1) =
        AudioSource(id, Uri.parse("content://test/$id"), name, frames, rate, channels, 24, null, FloatArray(0), startSeconds = start)

    private fun export(clips: List<Clip>, sources: List<AudioSource>, rate: FrameRate, folder: String): Element {
        val t = Exporters.Timeline("Test", clips, sources.associateBy { it.id }, 2, 1, emptySet(), 1920, 1080)
        val out = ByteArrayOutputStream()
        Exporters.writeResolveXml(out, t, rate, folder)
        // Parsing proves it is well-formed XML.
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(out.toByteArray().inputStream()).documentElement
    }

    private fun Element.all(tag: String): List<Element> {
        val n = getElementsByTagName(tag)
        return List(n.length) { n.item(it) as Element }
    }

    private fun Element.text(tag: String): String = all(tag).first().textContent

    @Test fun mediaPathsUseTheComputerFolder() {
        val src = source(1, "ZOOM0014_Tr3.WAV", 10 * minute)
        val clip = Clip(1, 1, 0, 0, 0, minute)
        for ((folder, expected) in listOf(
            "D:\\Shoots\\Day 1" to "file://localhost/D:/Shoots/Day%201/ZOOM0014_Tr3.WAV",
            "D:/Shoots/Day 1/" to "file://localhost/D:/Shoots/Day%201/ZOOM0014_Tr3.WAV",
            "/Users/me/Shoots" to "file://localhost/Users/me/Shoots/ZOOM0014_Tr3.WAV",
            "" to "file://localhost/ZOOM0014_Tr3.WAV",
        )) {
            val x = export(listOf(clip), listOf(src), FrameRate.F29_97, folder)
            assertEquals(folder, expected, x.text("pathurl"))
            assertEquals("ZOOM0014_Tr3.WAV", x.all("file").first().getElementsByTagName("name").item(0).textContent)
        }
    }

    @Test fun recorderTimecodeIsWritten() {
        val start = 13 * 3600 + 31 * 60 + 30.5   // 13:31:30 and a half
        val x = export(listOf(Clip(1, 1, 0, 0, 0, minute)), listOf(source(1, "a.wav", 10 * minute, start)), FrameRate.F25, "")
        val tc = x.all("file").first().getElementsByTagName("timecode").item(0) as Element
        assertEquals("13:31:30:12", tc.text("string"))
        assertEquals(((13 * 3600 + 31 * 60 + 30) * 25 + 12).toString(), tc.text("frame"))
        // A file without timecode has none.
        val y = export(listOf(Clip(1, 1, 0, 0, 0, minute)), listOf(source(1, "a.wav", 10 * minute)), FrameRate.F25, "")
        assertEquals(0, y.all("file").first().getElementsByTagName("timecode").length)
    }

    @Test fun piecesOfOneRecordingStayFrameContinuous() {
        // One long recording cut at random sample positions, with gaps closed up and pieces moved, like ripple edits.
        val r = Random(1)
        for (rate in FrameRate.entries) {
            val total = 58 * minute
            val src = source(1, "rec.wav", total)
            val clips = ArrayList<Clip>()
            var srcPos = r.nextLong(SAMPLE_RATE.toLong())
            var tl = 0L
            var id = 1L
            while (srcPos < total - minute) {
                val len = 4 * 4000 + r.nextLong(20L * SAMPLE_RATE)   // every piece is over a frame long
                // Back-to-back pieces (a plain cut) share the same source-to-timeline offset.
                val runs = 1 + r.nextInt(4)
                repeat(runs) {
                    val l = minOf(len / runs, total - srcPos)
                    clips += Clip(id++, 1, 0, tl, srcPos, l)
                    tl += l; srcPos += l
                }
                srcPos += r.nextLong(5L * SAMPLE_RATE)   // a removed stretch
            }
            val x = export(clips, listOf(src), rate, "")
            val items = x.all("clipitem").map { c ->
                listOf("start", "end", "in", "out").map { c.getElementsByTagName(it).item(0).textContent.toLong() }
            }
            val srcFrames = rate.toFrames(total)
            for ((i, c) in items.withIndex()) {
                val (s, e, inF, outF) = c
                assertEquals("${rate.label} item $i length", e - s, outF - inF)
                assertTrue("${rate.label} item $i in range", inF >= 0 && outF <= srcFrames)
                if (i > 0) assertEquals("${rate.label} item $i follows on the timeline", items[i - 1][1], s)
            }
            // Within each back-to-back run the source continues exactly: no repeated or skipped frame.
            var checked = 0
            for (i in 1 until clips.size) {
                val a = clips[i - 1]; val b = clips[i]
                if (b.start == a.end && b.sourceIn == a.sourceIn + a.length) {
                    assertEquals("${rate.label} cut $i", items[i - 1][3], items[i][2])
                    checked++
                }
            }
            assertTrue(checked > 10)
        }
    }
}
