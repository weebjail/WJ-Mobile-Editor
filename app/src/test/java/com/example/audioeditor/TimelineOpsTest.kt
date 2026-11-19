package com.example.audioeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOpsTest {
    private var id = 100L
    private fun newId() = id++
    private val sec = SAMPLE_RATE.toLong()

    @Test fun splitKeepsSourceContinuity() {
        val c = Clip(1, 1, 0, start = 0, sourceIn = 10 * sec, length = 10 * sec)
        val out = TimelineOps.split(listOf(c), c, 4 * sec, ::newId)
        assertEquals(2, out.size)
        val (l, r) = out
        assertEquals(4 * sec, l.length)
        assertEquals(4 * sec, r.start)
        assertEquals(14 * sec, r.sourceIn)
        assertEquals(6 * sec, r.length)
    }

    @Test fun rippleTrimStartPullsLaterClipsOnSameLaneOnly() {
        val a = Clip(1, 1, 0, 0, 0, 10 * sec)
        val b = Clip(2, 1, 0, 10 * sec, 0, 5 * sec)
        val other = Clip(3, 1, 1, 10 * sec, 0, 5 * sec)
        val video = Clip(4, 1, 0, 10 * sec, 0, 5 * sec, video = true)
        val out = TimelineOps.rippleTrimStart(listOf(a, b, other, video), a, 3 * sec).associateBy { it.id }
        assertEquals(7 * sec, out[1]!!.length)
        assertEquals(3 * sec, out[1]!!.sourceIn)
        assertEquals(7 * sec, out[2]!!.start)
        assertEquals(10 * sec, out[3]!!.start)   // different track
        assertEquals(10 * sec, out[4]!!.start)   // video lane
    }

    @Test fun crossfadeOverlapsClipsCentredOnTheCut() {
        // A plays source 0-10 s, B plays source 5-15 s; both sources are 20 s long.
        val a = Clip(1, 1, 0, 0, 0, 10 * sec)
        val b = Clip(2, 2, 0, 10 * sec, 5 * sec, 10 * sec)
        val r = TimelineOps.crossfade(listOf(a, b), b, 10 * sec, sec, FadeCurve.CURVED) { 20 * sec }
        val out = (r as TimelineOps.CrossfadeResult.Done).clips.associateBy { it.id }
        val na = out[1]!!; val nb = out[2]!!
        assertEquals(10 * sec + sec / 2, na.end)          // A runs half a second past the cut
        assertEquals(10 * sec - sec / 2, nb.start)        // B starts half a second early
        assertEquals(sec, na.fadeOut.frames)
        assertEquals(sec, nb.fadeIn.frames)
        // Sync is preserved: B's source still lines up with the timeline the same way.
        assertEquals(b.start - b.sourceIn, nb.start - nb.sourceIn)
    }

    @Test fun crossfadeUsesOtherSideWhenOneLacksMedia() {
        val a = Clip(1, 1, 0, 0, 0, 10 * sec)                  // source ends exactly at clip end
        val b = Clip(2, 2, 0, 10 * sec, 5 * sec, 10 * sec)
        val r = TimelineOps.crossfade(listOf(a, b), a, 10 * sec, sec, FadeCurve.LINEAR) { id ->
            if (id == 1L) 10 * sec else 20 * sec
        }
        val out = (r as TimelineOps.CrossfadeResult.Done).clips.associateBy { it.id }
        assertEquals(10 * sec, out[1]!!.end)
        assertEquals(9 * sec, out[2]!!.start)
    }

    @Test fun crossfadeCanBeResizedAndRemoved() {
        val a = Clip(1, 1, 0, 0, 0, 10 * sec)
        val b = Clip(2, 2, 0, 10 * sec, 5 * sec, 10 * sec)
        val first = (TimelineOps.crossfade(listOf(a, b), b, 10 * sec, sec, FadeCurve.CURVED) { 20 * sec }
            as TimelineOps.CrossfadeResult.Done).clips
        // Lengthen it to 2 s, still centred on the original cut.
        val nb = first.first { it.id == 2L }
        val longer = (TimelineOps.crossfade(first, nb, nb.start, 2 * sec, FadeCurve.CURVED) { 20 * sec }
            as TimelineOps.CrossfadeResult.Done).clips.associateBy { it.id }
        assertEquals(11 * sec, longer[1]!!.end)
        assertEquals(9 * sec, longer[2]!!.start)
        // Length 0 goes back to the plain cut with no fades.
        val none = (TimelineOps.crossfade(first, nb, nb.start, 0, FadeCurve.CURVED) { 20 * sec }
            as TimelineOps.CrossfadeResult.Done).clips.associateBy { it.id }
        assertEquals(10 * sec, none[1]!!.end)
        assertEquals(10 * sec, none[2]!!.start)
        assertEquals(5 * sec, none[2]!!.sourceIn)
        assertEquals(0L, none[1]!!.fadeOut.frames)
        assertEquals(0L, none[2]!!.fadeIn.frames)
    }

    @Test fun crossfadeWithNoHandlesIsRefused() {
        val a = Clip(1, 1, 0, 0, 0, 10 * sec)
        val b = Clip(2, 2, 0, 10 * sec, 0, 10 * sec)
        val r = TimelineOps.crossfade(listOf(a, b), b, 10 * sec, sec, FadeCurve.CURVED) { 10 * sec }
        assertEquals(TimelineOps.CrossfadeResult.NoHandles, r)
    }

    @Test fun curvedCrossfadeKeepsConstantPower() {
        for (i in 0..10) {
            val t = i / 10f
            val gin = fadeInGain(t, FadeCurve.CURVED)
            val gout = fadeInGain(1 - t, FadeCurve.CURVED)
            assertEquals(1f, gin * gin + gout * gout, 1e-4f)
        }
        // Curved fade-in rises faster than linear (the curve bows upward, like Resolve's +3 dB).
        assertTrue(fadeInGain(0.25f, FadeCurve.CURVED) > 0.25f)
    }

    @Test fun placeOverwritesOnlyTheSameLane() {
        val under = Clip(1, 1, 0, 0, 0, 10 * sec)
        val videoAbove = Clip(2, 1, 0, 0, 0, 10 * sec, video = true)
        val m = Clip(3, 1, 0, 4 * sec, 0, 2 * sec)
        val out = TimelineOps.place(listOf(under, videoAbove), m, ::newId)
        assertEquals(4, out.size)   // head + tail of `under`, untouched video, m
        assertTrue(out.any { it.id == 2L && it.length == 10 * sec })
    }

    @Test fun crossfadePairsFindOnlyPartialOverlaps() {
        val a = Clip(1, 1, 0, 0, 0, 10 * sec)
        val b = Clip(2, 1, 0, 9 * sec, 0, 10 * sec)        // starts inside a, ends after: crossfade
        val c = Clip(3, 1, 0, 19 * sec, 0, 5 * sec)        // touches b: plain cut
        assertEquals(listOf(0), TimelineOps.crossfadePairs(listOf(a, b, c)))
        val inside = Clip(4, 1, 0, 2 * sec, 0, 3 * sec)    // wholly inside a: not a crossfade
        assertEquals(emptyList<Int>(), TimelineOps.crossfadePairs(listOf(a, inside)))
    }

    @Test fun rippleRemoveRangeKeepsAllTracksInSync() {
        val video = Clip(1, 1, 0, 0, 0, 20 * sec, video = true, linkId = 9)
        val cam = Clip(2, 1, 0, 0, 0, 20 * sec, linkId = 9)
        val rec = Clip(3, 2, 1, 2 * sec, 0, 30 * sec)            // recorder, starts 2s in
        val later = Clip(4, 3, 0, 25 * sec, 0, 5 * sec)          // after the removed range
        val out = TimelineOps.rippleRemoveRange(listOf(video, cam, rec, later), 5 * sec, 10 * sec, ::newId)
        // Every track loses 5..10s: each straddling clip becomes two pieces that now touch at 5s.
        val camPieces = out.filter { !it.video && it.sourceId == 1L }.sortedBy { it.start }
        assertEquals(listOf(0L, 5 * sec), camPieces.map { it.start })
        assertEquals(listOf(5 * sec, 10 * sec), camPieces.map { it.length })
        assertEquals(10 * sec, camPieces[1].sourceIn)
        val recPieces = out.filter { it.sourceId == 2L }.sortedBy { it.start }
        assertEquals(listOf(2 * sec, 5 * sec), recPieces.map { it.start })
        assertEquals(8 * sec, recPieces[1].sourceIn)             // recorder media stays in sync with the camera
        assertEquals(20 * sec, out.first { it.id == 4L }.start)   // later clip pulled left by 5s
        // The right-hand video + camera pieces are linked to each other, not to the left-hand pieces.
        val rightLinks = out.filter { it.sourceId == 1L && it.start == 5 * sec }.map { it.linkId }.toSet()
        assertEquals(1, rightLinks.size)
        assertTrue(9L !in rightLinks)
    }

    @Test fun rippleRemoveRangeLeavesLockedTracksAlone() {
        val a = Clip(1, 1, 0, 0, 0, 20 * sec)
        val locked = Clip(2, 2, 1, 0, 0, 20 * sec)
        val out = TimelineOps.rippleRemoveRange(listOf(a, locked), 5 * sec, 10 * sec, ::newId) { it.track != 1 }
        assertEquals(locked, out.single { it.id == 2L })                      // untouched
        assertEquals(2, out.count { it.sourceId == 1L })                       // unlocked track was cut
    }

    @Test fun trimRangeStopsAtMediaAndNeighbours() {
        val a = Clip(1, 1, 0, 0, sourceIn = 2 * sec, length = 5 * sec)
        val b = Clip(2, 1, 0, 8 * sec, 0, 5 * sec)
        val clips = listOf(a, b)
        // Start can extend back 2s (media before sourceIn), but not before 0:00.
        assertEquals(0L, TimelineOps.trimRange(clips, a, true, 20 * sec, emptySet()).first)
        // End can extend 3s, up to b.
        assertEquals(3 * sec, TimelineOps.trimRange(clips, a, false, 20 * sec, emptySet()).last)
        // b's start can't extend back at all: there's no media before its sourceIn of 0.
        assertEquals(0L, TimelineOps.trimRange(clips, b, true, 20 * sec, emptySet()).first)
        val trimmed = TimelineOps.trimEdge(a, atStart = true, d = sec)
        assertEquals(sec, trimmed.start)
        assertEquals(3 * sec, trimmed.sourceIn)
        assertEquals(4 * sec, trimmed.length)
    }

    @Test fun projectNamesAreMadeSafe() {
        assertEquals("a_b_c", ProjectStore.safeName("a/b:c"))
        assertEquals("Untitled", ProjectStore.safeName("  ../  "))
    }

    @Test fun rippleInsertGapSplitsAndPushesClipsRight() {
        val a = Clip(1, 1, 0, 0, 0, 10 * sec)                    // across the insert point
        val b = Clip(2, 1, 0, 12 * sec, 0, 5 * sec)              // after it
        val other = Clip(3, 2, 1, 4 * sec, 0, 10 * sec)          // another track, not affected
        val out = TimelineOps.rippleInsertGap(listOf(a, b, other), 4 * sec, 3 * sec, ::newId) { it.track == 0 }
        val pieces = out.filter { it.sourceId == 1L }.sortedBy { it.start }
        assertEquals(listOf(0L, 7 * sec, 15 * sec), pieces.map { it.start })   // left piece, right piece, b
        assertEquals(4 * sec, pieces[1].sourceIn)                              // right piece continues the media
        assertEquals(other, out.single { it.id == 3L })
    }

    @Test fun clipMarkersStayWithTheirMediaWhenSplitAndMoved() {
        val m = Marker(50, frame = 6 * sec)                                   // 6s into the source file
        val a = Clip(1, 1, 0, start = 10 * sec, sourceIn = 2 * sec, length = 10 * sec, markers = listOf(m))
        assertEquals(listOf(m to 14 * sec), a.visibleMarkers())               // 10s + (6s - 2s)
        val out = TimelineOps.split(listOf(a), a, 12 * sec, ::newId).sortedBy { it.start }
        assertTrue(out[0].visibleMarkers().isEmpty())                         // left piece ends before it
        assertEquals(listOf(m to 14 * sec), out[1].visibleMarkers())
        assertEquals(listOf(m to 2 * sec), out[1].copy(start = 0).visibleMarkers())   // moving the clip moves it too
    }

    // A crossfades into B over 9.5-10.5 s (the edit point is at 10 s); C follows B.
    private val xa = Clip(1, 1, 0, 0, 0, 10 * sec + sec / 2, fadeOut = Fade(sec))
    private val xb = Clip(2, 2, 0, 9 * sec + sec / 2, 0, 5 * sec + sec / 2, fadeIn = Fade(sec))
    private val xc = Clip(3, 3, 0, 15 * sec, 0, 5 * sec)

    @Test fun rippleTrimEndStopsAtTheCrossfadeMiddle() {
        val out = TimelineOps.rippleTrimEnd(listOf(xa, xb, xc), xa, 7 * sec).associateBy { it.id }
        // Only A's own part (7-10 s) goes; the crossfade stays, now centred on 7 s.
        assertEquals(7 * sec + sec / 2, out[1]!!.end)
        assertEquals(sec, out[1]!!.fadeOut.frames)
        assertEquals(6 * sec + sec / 2, out[2]!!.start)
        assertEquals(0L, out[2]!!.sourceIn)
        assertEquals(12 * sec, out[3]!!.start)
        assertEquals(7 * sec to 12 * sec, TimelineOps.visibleSpan(out.values.toList(), out[2]!!))
    }

    @Test fun rippleTrimStartStopsAtTheCrossfadeMiddle() {
        val out = TimelineOps.rippleTrimStart(listOf(xa, xb, xc), xb, 12 * sec).associateBy { it.id }
        // Only B's own part (10-12 s) goes; the crossfade stays, now leading into B's media from 12 s.
        assertEquals(10 * sec + sec / 2, out[1]!!.end)
        assertEquals(9 * sec + sec / 2, out[2]!!.start)
        assertEquals(2 * sec, out[2]!!.sourceIn)
        assertEquals(sec, out[2]!!.fadeIn.frames)
        assertEquals(13 * sec, out[3]!!.start)
    }

    @Test fun rippleTrimPastTheCrossfadeMakesAPlainCut() {
        // Trimming B to its last 0.2 s: too short to keep the crossfade, so it becomes a cut first.
        val out = TimelineOps.rippleTrimStart(listOf(xa, xb), xb, 14 * sec + sec * 8 / 10).associateBy { it.id }
        assertEquals(10 * sec, out[1]!!.end)
        assertEquals(10 * sec, out[2]!!.start)
        assertEquals(sec / 5, out[2]!!.length)
        assertEquals(0L, out[2]!!.fadeIn.frames)
    }

    @Test fun volumeLineRoundTrips() {
        for (v in floatArrayOf(0f, 0.25f, 0.5f, 1f, 2f, MAX_CLIP_VOLUME)) {
            assertEquals(v, lineFractionToVolume(volumeToLineFraction(v)), 1e-4f)
        }
        assertEquals(1f / 3f, volumeToLineFraction(1f), 1e-6f)
    }

    @Test fun extendingIntoANeighbourTrimsItBack() {
        // A (0-10 s) touches B (10-20 s, source from 5 s). Dragging A's end 3 s right trims 3 s off B's start.
        val a = Clip(1, 1, 0, 0, 0, 10 * sec)
        val b = Clip(2, 2, 0, 10 * sec, 5 * sec, 10 * sec)
        val range = TimelineOps.trimRange(listOf(a, b), a, false, 60 * sec, emptySet(), { true }) { listOf(it) }
        assertEquals(10 * sec - TimelineOps.MIN_TRIM_LENGTH, range.last)   // B can shrink to the minimum
        val out = TimelineOps.trimWithPush(listOf(a, b), listOf(a), false, 3 * sec, { true }) { listOf(it) }.associateBy { it.id }
        assertEquals(13 * sec, out[1]!!.end)
        assertEquals(13 * sec, out[2]!!.start)
        assertEquals(8 * sec, out[2]!!.sourceIn)   // B's media stays in place
        assertEquals(20 * sec, out[2]!!.end)
        // Without permission to push (e.g. B is locked) the edge stops at B, as before.
        assertEquals(0L, TimelineOps.trimRange(listOf(a, b), a, false, 60 * sec, emptySet()).last)
    }

    @Test fun extendingStartIntoANeighbourTrimsItsEnd() {
        val a = Clip(1, 1, 0, 0, 0, 10 * sec)
        val b = Clip(2, 2, 0, 10 * sec, 5 * sec, 10 * sec)
        val out = TimelineOps.trimWithPush(listOf(a, b), listOf(b), true, -2 * sec, { true }) { listOf(it) }.associateBy { it.id }
        assertEquals(8 * sec, out[1]!!.end)
        assertEquals(8 * sec, out[2]!!.start)
        assertEquals(3 * sec, out[2]!!.sourceIn)
    }
}
