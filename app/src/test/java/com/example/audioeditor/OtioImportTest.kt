package com.example.audioeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtioImportTest {

    private fun t(v: Double, rate: Double = 24.0) = """{"OTIO_SCHEMA": "RationalTime.1", "rate": $rate, "value": $v}"""
    private fun range(start: Double, dur: Double, rate: Double = 24.0) =
        """{"OTIO_SCHEMA": "TimeRange.1", "start_time": ${t(start, rate)}, "duration": ${t(dur, rate)}}"""

    private fun clip(url: String, start: Double, dur: Double, mediaStart: Double = 0.0, mediaDur: Double = 2400.0) =
        """{"OTIO_SCHEMA": "Clip.1", "name": "x", "source_range": ${range(start, dur)},
           "media_reference": {"OTIO_SCHEMA": "ExternalReference.1", "target_url": "$url",
             "available_range": ${range(mediaStart, mediaDur)}}}"""

    private fun gap(dur: Double) = """{"OTIO_SCHEMA": "Gap.1", "source_range": ${range(0.0, dur)}}"""
    private fun trans(inOff: Double, outOff: Double) =
        """{"OTIO_SCHEMA": "Transition.1", "name": "Cross Fade (+3dB)", "in_offset": ${t(inOff)}, "out_offset": ${t(outOff)}}"""

    private fun track(kind: String, vararg items: String, enabled: Boolean = true) =
        """{"OTIO_SCHEMA": "Track.1", "kind": "$kind", "enabled": $enabled, "children": [${items.joinToString(",")}]}"""

    private fun timeline(vararg tracks: String) =
        """{"OTIO_SCHEMA": "Timeline.1", "name": "Day 1", "global_start_time": ${t(86400.0)},
           "tracks": {"OTIO_SCHEMA": "Stack.1", "children": [${tracks.joinToString(",")}]}}"""

    @Test
    fun readsClipsGapsAndTimecodeOffsets() {
        val otio = OtioImport.parse(
            timeline(
                track("Video", gap(24.0), clip("file:///D:/Shoots/Day%201/C0001.MP4", 86400.0 + 48, 96.0, mediaStart = 86400.0)),
                track("Audio", clip("REC_01.WAV", 10.0, 240.0)),
            )
        )
        assertEquals("Day 1", otio.name)
        assertEquals(24.0, otio.rate!!, 1e-9)
        assertEquals(listOf("C0001.MP4", "REC_01.WAV"), otio.mediaNames)
        val v = otio.tracks[0].clips.single()
        assertTrue(otio.tracks[0].video)
        assertEquals(1.0, v.start, 1e-9)       // after a one-second gap
        assertEquals(2.0, v.sourceIn, 1e-9)    // 48 frames past the file's own start timecode
        assertEquals(4.0, v.duration, 1e-9)
        val a = otio.tracks[1].clips.single()
        assertEquals(0.0, a.start, 1e-9)
        assertEquals(10.0 / 24, a.sourceIn, 1e-9)
    }

    @Test
    fun audioTransitionsBecomeFadesAndCrossfades() {
        val otio = OtioImport.parse(
            timeline(
                track(
                    "Audio",
                    trans(0.0, 12.0), clip("a.wav", 240.0, 240.0), trans(6.0, 6.0), clip("b.wav", 240.0, 240.0), trans(24.0, 0.0),
                ),
            )
        )
        val (a, b) = otio.tracks[0].clips
        assertEquals(0.5, a.fadeIn, 1e-9)
        // The crossfade: A runs a quarter second past the cut, B starts a quarter second before it.
        assertEquals(10.25, a.duration, 1e-9)
        assertEquals(0.5, a.fadeOut, 1e-9)
        assertEquals(9.75, b.start, 1e-9)
        assertEquals(240.0 / 24 - 0.25, b.sourceIn, 1e-9)
        assertEquals(0.5, b.fadeIn, 1e-9)
        assertEquals(1.0, b.fadeOut, 1e-9)
    }

    @Test
    fun videoDissolvesStayCutsAndUnsupportedItemsAreSkippedButTakeTime() {
        val generator = """{"OTIO_SCHEMA": "Clip.1", "source_range": ${range(0.0, 48.0)},
            "media_reference": {"OTIO_SCHEMA": "GeneratorReference.1"}}"""
        val otio = OtioImport.parse(
            timeline(track("Video", clip("a.mp4", 0.0, 48.0), trans(6.0, 6.0), generator, clip("b.mp4", 0.0, 48.0)))
        )
        val (a, b) = otio.tracks[0].clips
        assertEquals(2.0, a.duration, 1e-9)
        assertEquals(0.0, a.fadeOut, 1e-9)
        assertEquals(4.0, b.start, 1e-9)       // the title still takes up its two seconds
        assertEquals(1, otio.skipped)
    }

    @Test
    fun readsNewerClipSchemaWithSeveralReferences() {
        val c = """{"OTIO_SCHEMA": "Clip.2", "source_range": ${range(0.0, 24.0)}, "active_media_reference_key": "DEFAULT_MEDIA",
            "media_references": {"DEFAULT_MEDIA": {"OTIO_SCHEMA": "ExternalReference.1", "target_url": "file:///x/My%20Clip.mov"}}}"""
        val otio = OtioImport.parse(timeline(track("Video", c)))
        assertEquals("My Clip.mov", otio.tracks[0].clips.single().media)
    }

    @Test
    fun buildsLinkedClipsAtTheOffsetOnTheRightTracks() {
        val otio = OtioImport.parse(
            timeline(
                track("Video", clip("cam.mp4", 24.0, 48.0)),
                track("Audio", clip("cam.mp4", 24.0, 48.0)),
                track("Audio", clip("rec.wav", 0.0, 96.0), enabled = false),
                track("Audio", clip("gone.wav", 0.0, 24.0)),
            )
        )
        val media = mapOf(
            "cam.mp4" to OtioImport.Media(1, hasVideo = true, videoFrames = 10 * 48000L, audioFrames = 10 * 48000L),
            "rec.wav" to OtioImport.Media(2, hasVideo = false, videoFrames = 0, audioFrames = 60 * 48000L),
        )
        var id = 100L
        val r = OtioImport.toClips(otio, media, offset = 48000, videoBase = 1, audioBase = 2, FadeCurve.CURVED) { id++ }
        assertEquals(1, r.skipped)                    // gone.wav isn't available
        assertEquals(1, r.videoTracksUsed)
        assertEquals(2, r.audioTracksUsed)
        assertEquals(setOf(3), r.mutedAudio)         // the switched-off track, shifted to its new number
        val v = r.clips.single { it.video }
        val cam = r.clips.single { !it.video && it.sourceId == 1L }
        val rec = r.clips.single { it.sourceId == 2L }
        assertEquals(1, v.track)
        assertEquals(48000L, v.start)
        assertEquals(48000L, v.sourceIn)
        assertEquals(96000L, v.length)
        assertEquals(2, cam.track)
        assertNotNull(v.linkId)
        assertEquals(v.linkId, cam.linkId)
        assertNull(rec.linkId)
        assertEquals(3, rec.track)
    }

    @Test
    fun clipsAreKeptInsideTheirMedia() {
        // A crossfade that would need audio from before the file starts, and a clip running past its end.
        val otio = OtioImport.parse(
            timeline(track("Audio", clip("a.wav", 0.0, 48.0), trans(12.0, 0.0), clip("b.wav", 0.0, 48.0)))
        )
        val media = mapOf(
            "a.wav" to OtioImport.Media(1, false, 0, 48000L),        // only one second long
            "b.wav" to OtioImport.Media(2, false, 0, 10 * 48000L),
        )
        var id = 1L
        val r = OtioImport.toClips(otio, media, 0, 0, 0, FadeCurve.CURVED) { id++ }
        val a = r.clips.single { it.sourceId == 1L }
        val b = r.clips.single { it.sourceId == 2L }
        assertEquals(48000L, a.length)
        assertEquals(0L, b.sourceIn)
        assertEquals(96000L, b.start)
    }

    @Test
    fun fileNamesFromUrls() {
        assertEquals("C0001.MP4", OtioImport.fileName("file:///D:/Shoots/C0001.MP4"))
        assertEquals("Day 1 + 2.wav", OtioImport.fileName("file:///x/Day%201%20+%202.wav"))
        assertEquals("C0001.MP4", OtioImport.fileName("D:\\Shoots\\C0001.MP4"))
        assertEquals("REC.WAV", OtioImport.fileName("REC.WAV"))
    }

    @Test
    fun readsJsonEdgeCases() {
        val json = """{"OTIO_SCHEMA": "Timeline.1", "name": "Edit \"1\"\n", "tracks": {"OTIO_SCHEMA": "Stack.1",
            "children": [{"OTIO_SCHEMA": "Track.1", "kind": "Audio", "children": [], "enabled": true}]}, "x": [1e3, -2.5, null, NaN]}"""
        val otio = OtioImport.parse(json)
        assertEquals("Edit \"1\"\n", otio.name)
        assertEquals(0, otio.clipCount)
    }

    @Test fun readsResolveClipVolume() {
        // As Resolve writes it: a Fairlight clip volume effect with the level in dB.
        val c = MiniJson.parse(
            """{"effects": [{"OTIO_SCHEMA": "Effect.1", "effect_name": "Resolve Effect", "metadata": {"Resolve_OTIO": {""" +
                """"Effect Name": "Fairlight Clip Volume and Fades", "Enabled": true, "Name": "Volume", "Type": 62, """ +
                """"Parameters": [{"Parameter ID": "volume", "Parameter Value": -6.0206, "Variant Type": "Double"}]}}}]}"""
        ) as Map<*, *>
        assertEquals(0.5f, OtioImport.clipVolume(c), 1e-3f)
        // Left at 0 dB, Resolve writes the effect with no parameters.
        val plain = MiniJson.parse(
            """{"effects": [{"metadata": {"Resolve_OTIO": {"Effect Name": "Fairlight Clip Volume and Fades", "Parameters": []}}}]}"""
        ) as Map<*, *>
        assertEquals(1f, OtioImport.clipVolume(plain), 0f)
    }
}
