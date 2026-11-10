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

    // ------------------------------------------------------------ OpenTimelineIO

    private fun otio(clips: List<Clip>, sources: List<AudioSource>, rate: FrameRate, folder: String, muted: Set<Int> = emptySet()) =
        org.json.JSONObject(
            Exporters.buildOtio(Exporters.Timeline("Test", clips, sources.associateBy { it.id }, 2, 1, muted, 1920, 1080), rate, folder),
        )

    private fun org.json.JSONObject.value(key: String) = getJSONObject(key).getDouble("value").toLong()

    private fun tracks(o: org.json.JSONObject) = o.getJSONObject("tracks").getJSONArray("children").let { a ->
        List(a.length()) { a.getJSONObject(it) }
    }

    private fun children(track: org.json.JSONObject) = track.getJSONArray("children").let { a -> List(a.length()) { a.getJSONObject(it) } }

    @Test fun otioStructureMatchesTheXml() {
        val r = Random(3)
        for (rate in listOf(FrameRate.F23_976, FrameRate.F25, FrameRate.F29_97, FrameRate.F60)) {
            val src = source(1, "rec 1.wav", 30 * minute, start = 3600.0 + 0.4)
            val clips = ArrayList<Clip>()
            var tl = 0L; var sp = 0L; var id = 1L
            repeat(25) {
                val len = 20_000 + r.nextLong(10L * SAMPLE_RATE)
                tl += if (r.nextBoolean()) r.nextLong(2L * SAMPLE_RATE) else 0   // some gaps
                clips += Clip(id++, 1, if (it % 3 == 0) 1 else 0, tl, sp, len, fadeIn = Fade(if (it % 4 == 0) 9000 else 0))
                tl += len; sp += len + r.nextLong(SAMPLE_RATE.toLong())
            }
            val o = otio(clips, listOf(src), rate, "", muted = setOf(1))
            assertEquals("Timeline.1", o.getString("OTIO_SCHEMA"))
            val ts = tracks(o)
            assertEquals(listOf("V1", "A1", "A2"), ts.map { it.getString("name") })
            assertEquals(listOf("Video", "Audio", "Audio"), ts.map { it.getString("kind") })
            assertEquals(false, ts[2].getBoolean("enabled"))   // muted track

            // Same clip positions as the XML, track by track.
            val xml = export(clips, listOf(src), rate, "")
            val xmlTracks = xml.all("audio").first { it.parentNode.nodeName == "media" }.all("track")
            val tc = Math.floor((3600.4) * rate.fps + 1e-6).toLong()
            for (a in 0..1) {
                val xmlItems = xmlTracks[a].all("clipitem").map { c ->
                    listOf("start", "end", "in").map { c.getElementsByTagName(it).item(0).textContent.toLong() }
                }
                var pos = 0L
                val placed = ArrayList<List<Long>>()
                for (item in children(ts[a + 1])) {
                    when (item.getString("OTIO_SCHEMA")) {
                        "Gap.1" -> pos += item.getJSONObject("source_range").value("duration")
                        "Clip.1" -> {
                            val sr = item.getJSONObject("source_range")
                            val d = sr.value("duration")
                            placed += listOf(pos, pos + d, sr.value("start_time") - tc)
                            pos += d
                            val ref = item.getJSONObject("media_reference")
                            assertEquals("rec 1.wav", ref.getString("target_url"))   // relative: next to the .otio
                            assertEquals(tc, ref.getJSONObject("available_range").value("start_time"))
                        }
                        "Transition.1" -> assertEquals("SMPTE_Dissolve", item.getString("transition_type"))
                        else -> throw AssertionError("unexpected ${item.getString("OTIO_SCHEMA")}")
                    }
                }
                assertEquals("${rate.label} A${a + 1}", xmlItems, placed)
            }
        }
    }

    @Test fun otioFadesAndCrossfades() {
        val src = source(1, "a.wav", 10 * minute)
        val second = SAMPLE_RATE.toLong()
        // Clip 1 fades in; clips 1 and 2 overlap by 1 s with a crossfade; clip 2 fades out.
        val clips = listOf(
            Clip(1, 1, 0, 0, 0, 10 * second, fadeIn = Fade(2 * second), fadeOut = Fade(1 * second)),
            Clip(2, 1, 0, 9 * second, 20 * second, 10 * second, fadeIn = Fade(1 * second), fadeOut = Fade(3 * second)),
        )
        val kids = children(tracks(otio(clips, listOf(src), FrameRate.F25, ""))[1])
        assertEquals(
            listOf("Transition.1", "Clip.1", "Transition.1", "Clip.1", "Transition.1"),
            kids.map { it.getString("OTIO_SCHEMA") },
        )
        assertEquals(listOf(0L, 50L), listOf(kids[0].value("in_offset"), kids[0].value("out_offset")))     // 2 s fade-in
        assertEquals(listOf(13L, 12L), listOf(kids[2].value("in_offset"), kids[2].value("out_offset")))    // 1 s crossfade: 225..250 around the cut at 238
        assertEquals(listOf(75L, 0L), listOf(kids[4].value("in_offset"), kids[4].value("out_offset")))    // 3 s fade-out
        // The crossfade cut is at its centre (9.5 s = frame 237.5 -> 238).
        assertEquals(238L, kids[1].getJSONObject("source_range").value("duration"))
    }

    @Test fun otioFolderGivesFullUrls() {
        val src = source(1, "ZOOM0014_Tr3.WAV", minute)
        for ((folder, expected) in listOf(
            "D:\\Shoots\\Day 1" to "file:///D:/Shoots/Day%201/ZOOM0014_Tr3.WAV",
            "/Users/me/Shoots" to "file:///Users/me/Shoots/ZOOM0014_Tr3.WAV",
        )) {
            val clip = children(tracks(otio(listOf(Clip(1, 1, 0, 0, 0, minute)), listOf(src), FrameRate.F25, folder))[1]).first()
            assertEquals(expected, clip.getJSONObject("media_reference").getString("target_url"))
        }
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
