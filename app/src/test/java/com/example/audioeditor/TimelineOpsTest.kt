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

    @Test fun projectNamesAreMadeSafe() {
        assertEquals("a_b_c", ProjectStore.safeName("a/b:c"))
        assertEquals("Untitled", ProjectStore.safeName("  ../  "))
    }
}
