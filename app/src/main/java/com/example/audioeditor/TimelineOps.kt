package com.example.audioeditor

import kotlin.math.abs

/**
 * Editing rules. Each function takes the current list of clips and returns a new list,
 * which keeps undo/redo simple (we just remember old lists).
 * Ripple edits only shift clips on the same track.
 */
object TimelineOps {

    /** Cut a clip in two at [at]. The left part keeps the fade-in, the right part keeps the fade-out. */
    fun split(clips: List<Clip>, target: Clip, at: Long, newId: () -> Long): List<Clip> {
        if (at <= target.start || at >= target.end) return clips
        val left = target.copy(
            length = at - target.start,
            fadeOut = Fade(curve = target.fadeOut.curve),
        ).withValidFades()
        val right = target.copy(
            id = newId(),
            start = at,
            sourceIn = target.sourceIn + (at - target.start),
            length = target.end - at,
            fadeIn = Fade(curve = target.fadeIn.curve),
        ).withValidFades()
        return clips.flatMap { if (it.id == target.id) listOf(left, right) else listOf(it) }
    }

    /** Remove everything from the clip's start up to [at], then pull the rest of the track left. */
    fun rippleTrimStart(clips: List<Clip>, target: Clip, at: Long): List<Clip> {
        val d = at - target.start
        if (d <= 0 || at >= target.end) return clips
        return clips.map {
            when {
                it.id == target.id ->
                    it.copy(sourceIn = it.sourceIn + d, length = it.length - d).withValidFades()
                it.track == target.track && it.start >= target.end -> it.copy(start = it.start - d)
                else -> it
            }
        }
    }

    /** Remove everything from [at] to the clip's end, then pull the rest of the track left. */
    fun rippleTrimEnd(clips: List<Clip>, target: Clip, at: Long): List<Clip> {
        val d = target.end - at
        if (d <= 0 || at <= target.start) return clips
        return clips.map {
            when {
                it.id == target.id -> it.copy(length = at - it.start).withValidFades()
                it.track == target.track && it.start >= target.end -> it.copy(start = it.start - d)
                else -> it
            }
        }
    }

    fun delete(clips: List<Clip>, target: Clip): List<Clip> = clips.filter { it.id != target.id }

    fun rippleDelete(clips: List<Clip>, target: Clip): List<Clip> =
        clips.filter { it.id != target.id }.map {
            if (it.track == target.track && it.start >= target.end) it.copy(start = it.start - target.length) else it
        }

    /** Applies a crossfade transition between two adjacent or overlapping clips on the same track. */
    fun crossfade(clips: List<Clip>, target: Clip, durationFrames: Long = SAMPLE_RATE.toLong()): List<Clip> {
        val sameTrack = clips.filter { it.track == target.track }.sortedBy { it.start }
        val idx = sameTrack.indexOfFirst { it.id == target.id }
        if (idx < 0) return clips
        val prev = sameTrack.getOrNull(idx - 1)
        val next = sameTrack.getOrNull(idx + 1)

        val leftClip: Clip?
        val rightClip: Clip?

        if (prev != null && (abs(target.start - prev.end) < durationFrames * 2 || target.start < prev.end)) {
            leftClip = prev
            rightClip = target
        } else if (next != null && (abs(next.start - target.end) < durationFrames * 2 || next.start < target.end)) {
            leftClip = target
            rightClip = next
        } else {
            val dur = minOf(durationFrames, target.length / 2).coerceAtLeast(1L)
            return clips.map {
                if (it.id == target.id) {
                    it.copy(fadeIn = Fade(dur, target.fadeIn.curve), fadeOut = Fade(dur, target.fadeOut.curve)).withValidFades()
                } else it
            }
        }

        val dur = minOf(durationFrames, leftClip.length, rightClip.length).coerceAtLeast(1L)
        return clips.map {
            when (it.id) {
                leftClip.id -> it.copy(fadeOut = Fade(dur, leftClip.fadeOut.curve)).withValidFades()
                rightClip.id -> it.copy(fadeIn = Fade(dur, rightClip.fadeIn.curve)).withValidFades()
                else -> it
            }
        }
    }

    /**
     * Drop clip [m] onto the timeline in "overwrite" mode, like a pro editor:
     * whatever it lands on is trimmed, split or removed.
     */
    fun place(others: List<Clip>, m: Clip, newId: () -> Long): List<Clip> {
        val result = ArrayList<Clip>()
        for (o in others) {
            if (o.track != m.track || o.end <= m.start || o.start >= m.end) {
                result += o; continue
            }
            val keepsHead = o.start < m.start
            val keepsTail = o.end > m.end
            if (keepsHead) result += o.copy(length = m.start - o.start).withValidFades()
            if (keepsTail) {
                val d = m.end - o.start
                result += o.copy(
                    id = if (keepsHead) newId() else o.id,
                    start = m.end,
                    sourceIn = o.sourceIn + d,
                    length = o.length - d,
                ).withValidFades()
            }
        }
        result += m
        return result
    }

    /**
     * If the dragged clip's start or end is within [threshold] of any edge, snap to it.
     * Returns the (possibly adjusted) start and the edge snapped to, or null.
     */
    fun snap(proposed: Long, length: Long, edges: List<Long>, threshold: Long): Pair<Long, Long?> {
        var best = proposed
        var bestDist = threshold + 1
        var line: Long? = null
        for (e in edges) {
            val d1 = abs(e - proposed)
            if (d1 < bestDist) { bestDist = d1; best = e; line = e }
            val d2 = abs(e - (proposed + length))
            if (d2 < bestDist) { bestDist = d2; best = e - length; line = e }
        }
        return best to line
    }
}
