package com.example.audioeditor

import kotlin.math.abs

/**
 * Editing rules. Each function takes the current list of clips and returns a new list,
 * which keeps undo/redo simple (we just remember old lists).
 * Ripple edits only shift clips in the same lane (same kind and track number).
 */
object TimelineOps {

    /** Clips whose ends are this close are treated as touching when making a crossfade (~1 video frame). */
    const val ADJACENT_TOLERANCE = SAMPLE_RATE / 25L

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

    /** Remove everything from the clip's start up to [at], then pull the rest of the lane left. */
    fun rippleTrimStart(clips: List<Clip>, target: Clip, at: Long): List<Clip> {
        val d = at - target.start
        if (d <= 0 || at >= target.end) return clips
        return clips.map {
            when {
                it.id == target.id ->
                    it.copy(sourceIn = it.sourceIn + d, length = it.length - d).withValidFades()
                it.sameLane(target) && it.start > target.start -> it.copy(start = it.start - d)
                else -> it
            }
        }
    }

    /** Remove everything from [at] to the clip's end, then pull the rest of the lane left. */
    fun rippleTrimEnd(clips: List<Clip>, target: Clip, at: Long): List<Clip> {
        val d = target.end - at
        if (d <= 0 || at <= target.start) return clips
        return clips.map {
            when {
                it.id == target.id -> it.copy(length = at - it.start).withValidFades()
                it.sameLane(target) && it.start > target.start -> it.copy(start = it.start - d)
                else -> it
            }
        }
    }

    /**
     * Ripple across all tracks: removes the time range [from, to) from every track (clips inside it are removed,
     * clips crossing its edges are cut there) and pulls everything after it left, so all tracks stay in sync.
     * Clips for which [affects] is false (on locked tracks) are left exactly as they are.
     */
    fun rippleRemoveRange(
        clips: List<Clip>, from: Long, to: Long, newId: () -> Long, affects: (Clip) -> Boolean = { true },
    ): List<Clip> {
        if (to <= from) return clips
        var out = clips
        for (at in longArrayOf(from, to)) {
            val targets = out.filter { at > it.start && at < it.end && affects(it) }
            for (t in targets) out = split(out, t, at, newId)
            // The right-hand pieces of a linked video + audio become their own linked pair (as with a cut).
            for (link in targets.mapNotNull { it.linkId }.toSet()) {
                val fresh = newId()
                out = out.map { if (it.linkId == link && it.start >= at && affects(it)) it.copy(linkId = fresh) else it }
            }
        }
        val d = to - from
        return out.filter { !affects(it) || it.start < from || it.end > to }
            .map { if (affects(it) && it.start >= to) it.copy(start = it.start - d) else it }
    }

    /**
     * Opens a gap [length] long at [at] on the clips [affects] accepts: a clip across [at] is split there, and
     * everything from [at] on moves right (the opposite of [rippleRemoveRange]; used for insert-pasting).
     */
    fun rippleInsertGap(
        clips: List<Clip>, at: Long, length: Long, newId: () -> Long, affects: (Clip) -> Boolean = { true },
    ): List<Clip> {
        if (length <= 0) return clips
        var out = clips
        val targets = out.filter { at > it.start && at < it.end && affects(it) }
        for (t in targets) out = split(out, t, at, newId)
        // The right-hand pieces of a linked video + audio become their own linked pair (as with a cut).
        for (link in targets.mapNotNull { it.linkId }.toSet()) {
            val fresh = newId()
            out = out.map { if (it.linkId == link && it.start >= at && affects(it)) it.copy(linkId = fresh) else it }
        }
        return out.map { if (affects(it) && it.start >= at) it.copy(start = it.start + length) else it }
    }

    /** Shortest a clip can be trimmed to by dragging its edge (one video frame at 25 fps). */
    const val MIN_TRIM_LENGTH = SAMPLE_RATE / 25L

    /**
     * How far (in frames, negative = left) the start or end edge of [c] can move when dragged: limited by
     * the media in its source file, by the neighbouring clips in its lane (it can't be extended over them),
     * and by a minimum length. Clips in [ignore] (e.g. clips linked to [c]) don't count as neighbours.
     */
    fun trimRange(clips: List<Clip>, c: Clip, atStart: Boolean, sourceFrames: Long, ignore: Set<Long>): LongRange {
        val lane = clips.filter { it.sameLane(c) && it.id != c.id && it.id !in ignore }
        return if (atStart) {
            val prevLimit = lane.filter { it.start < c.start }.maxOfOrNull { minOf(it.end, c.start) } ?: 0L
            maxOf(-c.sourceIn, prevLimit - c.start)..(c.length - MIN_TRIM_LENGTH)
        } else {
            val nextLimit = lane.filter { it.start > c.start }.minOfOrNull { maxOf(it.start, c.end) } ?: Long.MAX_VALUE
            -(c.length - MIN_TRIM_LENGTH)..minOf(sourceFrames - (c.sourceIn + c.length), nextLimit - c.end)
        }
    }

    /** Moves the start or end edge of [c] by [d] frames (the media stays in place, like Resolve's trim). */
    fun trimEdge(c: Clip, atStart: Boolean, d: Long): Clip =
        if (atStart) c.copy(start = c.start + d, sourceIn = c.sourceIn + d, length = c.length - d).withValidFades()
        else c.copy(length = c.length + d).withValidFades()

    fun delete(clips: List<Clip>, target: Clip): List<Clip> = clips.filter { it.id != target.id }

    fun rippleDelete(clips: List<Clip>, target: Clip): List<Clip> =
        clips.filter { it.id != target.id }.map {
            if (it.sameLane(target) && it.start > target.start) it.copy(start = it.start - target.length) else it
        }

    sealed interface CrossfadeResult {
        data class Done(val clips: List<Clip>) : CrossfadeResult
        data object NoHandles : CrossfadeResult
    }

    /**
     * Crossfade like Resolve: at the edit point nearest [near], the outgoing clip is extended past the cut
     * and the incoming clip starts before it, using the extra media beyond each edit ("handles"),
     * so both play at once while one fades out and the other fades in. Clip timing (sync) is unchanged.
     * The transition is centred on the cut; if one side lacks media the other side makes up the difference.
     * With no neighbouring clip, the clip fades from/to silence at both ends instead.
     */
    fun crossfade(
        clips: List<Clip>,
        target: Clip,
        near: Long,
        duration: Long,
        curve: FadeCurve,
        sourceFrames: (Long) -> Long,
    ): CrossfadeResult {
        val lane = clips.filter { it.sameLane(target) && it.id != target.id }
        val prev = lane.filter { it.start < target.start && it.end >= target.start - ADJACENT_TOLERANCE }
            .maxByOrNull { it.end }
        val next = lane.filter { it.start > target.start && it.start <= target.end + ADJACENT_TOLERANCE }
            .minByOrNull { it.start }
        val pair = when {
            prev != null && next != null ->
                if (abs(near - target.start) <= abs(near - target.end)) prev to target else target to next
            prev != null -> prev to target
            next != null -> target to next
            else -> null
        }
        if (pair == null) {
            val dur = minOf(duration, target.length / 2).coerceAtLeast(1L)
            return CrossfadeResult.Done(clips.map {
                if (it.id == target.id) it.copy(fadeIn = Fade(dur, curve), fadeOut = Fade(dur, curve)).withValidFades() else it
            })
        }
        var (a, b) = pair

        // If these clips already overlap (an existing crossfade), go back to a plain cut in the middle first.
        if (b.start < a.end) {
            val cut = (b.start + a.end) / 2
            a = a.copy(length = cut - a.start)
            b = b.copy(start = cut, sourceIn = b.sourceIn + (cut - b.start), length = b.end - cut)
        }
        if (duration <= 0) {
            // No crossfade: just the plain cut.
            val plainA = a.copy(fadeOut = Fade(0L, curve))
            val plainB = b.copy(fadeIn = Fade(0L, curve))
            return CrossfadeResult.Done(clips.map {
                when (it.id) {
                    plainA.id -> plainA
                    plainB.id -> plainB
                    else -> it
                }
            })
        }
        val cut = b.start
        val gap = cut - a.end                                         // 0 when touching
        val handleA = sourceFrames(a.sourceId) - (a.sourceIn + a.length) - gap
        val handleB = b.sourceIn
        if (handleA < 0) return CrossfadeResult.NoHandles             // A can't even reach across the gap
        val half = duration / 2
        var after = minOf(half, handleA).coerceAtLeast(0L)            // how far A runs past the cut
        var before = minOf(half, handleB).coerceAtLeast(0L)           // how early B starts before the cut
        after = minOf(duration - before, handleA).coerceAtLeast(0L)
        before = minOf(duration - after, handleB).coerceAtLeast(0L)
        // Neither clip may be swallowed by the transition.
        after = minOf(after, b.length / 2)
        before = minOf(before, a.length / 2)
        if (after + before <= 0) return CrossfadeResult.NoHandles

        // A also covers any small gap before the cut; the blended region is [cut - before, cut + after].
        val len = after + before
        val newA = a.copy(length = cut + after - a.start, fadeOut = Fade(len, curve)).withValidFades()
        val newB = b.copy(
            start = cut - before,
            sourceIn = b.sourceIn - before,
            length = b.end - (cut - before),
            fadeIn = Fade(len, curve),
        ).withValidFades()
        return CrossfadeResult.Done(clips.map {
            when (it.id) {
                newA.id -> newA
                newB.id -> newB
                else -> it
            }
        })
    }

    /**
     * Drop clip [m] onto the timeline in "overwrite" mode, like Resolve:
     * whatever it lands on in the same lane is trimmed, split or removed.
     */
    fun place(others: List<Clip>, m: Clip, newId: () -> Long): List<Clip> {
        val result = ArrayList<Clip>()
        for (o in others) {
            if (!o.sameLane(m) || o.end <= m.start || o.start >= m.end) {
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
     * Crossfades in one lane's clips: neighbours (by start time) where the next clip begins inside the
     * previous one and runs past its end. Returns index pairs into [sorted], which must be sorted by start.
     */
    fun crossfadePairs(sorted: List<Clip>): List<Int> = (0 until sorted.size - 1).filter { i ->
        val a = sorted[i]
        val b = sorted[i + 1]
        b.start > a.start && b.start < a.end && b.end > a.end
    }

    /** True if [start, end) is free of clips in the given lane, ignoring clips in [ignore]. */
    fun laneFree(clips: List<Clip>, video: Boolean, track: Int, start: Long, end: Long, ignore: Set<Long>): Boolean =
        clips.none { it.video == video && it.track == track && it.id !in ignore && it.start < end && it.end > start }

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
