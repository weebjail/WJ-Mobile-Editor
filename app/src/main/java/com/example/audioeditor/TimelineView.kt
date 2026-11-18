package com.example.audioeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Mode { UNDECIDED, SCRUB, PLAYHEAD, MOVE, TRIM, PAN, PINCH, HELD }

/** A clip edge under the finger: which clip, which end, and how far the finger is from it (px). */
private class EdgeHit(val clip: Clip, val atStart: Boolean, val distance: Float)

private val Background = Color(0xFF1C1D21)
private val TrackA = Color(0xFF24262B)
private val TrackB = Color(0xFF282A30)
private val VideoLane = Color(0xFF1F2430)
private val RulerBg = Color(0xFF30333A)
private val HeaderBg = Color(0xFF2A2C32)
private val TextDim = Color(0xFFB8BCC6)
private val PlayheadColor = Color(0xFFFF5A4E)
private val SnapColor = Color(0xFFFFD54A)
private val TransitionFill = Color(0xFF15161A).copy(alpha = 0.55f)
private val TransitionEdge = Color(0xFFDADDE5)
private val MuteOn = Color(0xFFE0B03A)
private val LockOn = Color(0xFFE0675A)
private val TargetOn = Color(0x40E1005F) // #E1005F at 25% opacity
private val LockShade =Color.Black.copy(alpha = 0.35f)
private val VideoClipColor = Color(0xFF35507A)
private val ClipColors = listOf(Color(0xFF2F7A5C), Color(0xFF3B5DA3), Color(0xFF7D4FA3), Color(0xFFA0683A))
private val RulerSteps = doubleArrayOf(
    0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0,
)

/** Size of the track-header column and the ruler; the corner where they meet holds the ripple-all button. */
val TimelineHeaderWidth = 52.dp
val TimelineRulerHeight = 28.dp

private val HeaderButton = 20.dp
private val HeaderGap = 4.dp

/**
 * Gestures:
 *  - drag on the ruler (top strip), or swipe starting on the playhead line: move the playhead
 *    (it snaps to clip edges, cuts and markers when snapping is on). Markers show as flags on the ruler.
 *  - tap a clip: select it.  Tap empty space: deselect and move the playhead there
 *  - press and drag a clip: move it (and anything linked to it) in time, or to another track of the same kind
 *  - press and drag a clip's edge: shorten or extend it
 *  - drag anywhere else: scroll.  Pinch with two fingers: zoom
 *  - track header (left edge): tap the name (V1, A1…) to make it the paste track; below it, the left half
 *    locks / unlocks the track and the right half mutes / unmutes an audio track
 *  Clips on locked tracks can't be selected or changed.
 */
@Composable
fun TimelineView(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val rulerH = with(density) { TimelineRulerHeight.toPx() }
    val trackH = with(density) { 72.dp.toPx() }
    val headerW = with(density) { TimelineHeaderWidth.toPx() }
    var scrollY by remember { mutableFloatStateOf(0f) }
    val lockIcon = rememberVectorPainter(Icons.Default.Lock)
    val unlockIcon = rememberVectorPainter(Icons.Default.LockOpen)

    // Which clips to draw, and how crossfades split them. Worked out only when the clips change,
    // not on every redraw (the playhead moves every frame during playback).
    val preview = vm.dragPreview
    val layout = remember(vm.clips, preview) { ClipLayout.of(vm.clips, preview) }

    fun frameAt(x: Float) = (vm.scrollFrames + (x - headerW) * vm.samplesPerPx).toLong().coerceAtLeast(0L)
    fun xOf(f: Long) = headerW + ((f - vm.scrollFrames) / vm.samplesPerPx).toFloat()
    fun laneAt(y: Float) = floor((y - rulerH + scrollY) / trackH).toInt()
    fun clipAt(p: Offset): Clip? {
        if (p.y < rulerH || p.x < headerW) return null
        val lane = laneAt(p.y)
        if (lane !in 0 until vm.laneCount) return null
        val (video, track) = vm.laneKind(lane)
        if (vm.isLocked(video, track)) return null
        val f = frameAt(p.x)
        // Where two clips overlap (a crossfade), each owns its half, matching how they're drawn.
        return vm.clips.filter { it.video == video && it.track == track && f >= it.start && f < it.end && vm.isEditable(it) }
            .maxByOrNull { min(f - it.start, it.end - f) }
    }

    /**
     * The clip edge nearest the finger, within [zonePx]. Where two clips touch, the side the finger is on
     * decides: just left of a cut grabs the left clip's end, just right grabs the right clip's start.
     */
    fun edgeAt(p: Offset, zonePx: Float): EdgeHit? {
        if (p.y < rulerH || p.x < headerW) return null
        val lane = laneAt(p.y)
        if (lane !in 0 until vm.laneCount) return null
        val (video, track) = vm.laneKind(lane)
        if (vm.isLocked(video, track)) return null
        var best: EdgeHit? = null
        var bestScore = Float.MAX_VALUE
        for (c in vm.clips) {
            if (c.video != video || c.track != track || !vm.isEditable(c)) continue
            // On short clips keep the middle free for moving the clip.
            val zone = min(zonePx, (xOf(c.end) - xOf(c.start)) / 3f)
            for (atStart in booleanArrayOf(true, false)) {
                val ex = xOf(if (atStart) c.start else c.end)
                val d = abs(p.x - ex)
                if (d > zone) continue
                val onClipSide = if (atStart) p.x >= ex else p.x <= ex
                val score = d + if (onClipSide) 0f else zonePx   // prefer the edge of the clip being touched
                if (score < bestScore) { bestScore = score; best = EdgeHit(c, atStart, d) }
            }
        }
        return best
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { vm.viewWidthPx = it.width - headerW }
            .pointerInput(Unit) {
                val snapPx = 14.dp.toPx()
                val grabPx = 22.dp.toPx()
                val trimPx = 16.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val onRuler = down.position.y < rulerH
                    val onHeader = !onRuler && down.position.x < headerW
                    val hit = clipAt(down.position)
                    // Distance from the playhead line, kept while dragging so the playhead doesn't jump.
                    val playheadX = xOf(vm.playhead)
                    val grabOffset = down.position.x - playheadX
                    // Only when the playhead is actually visible (not scrolled under the track headers).
                    var onPlayhead = !onRuler && !onHeader && playheadX >= headerW && abs(grabOffset) <= grabPx
                    // A clip edge under the finger can be dragged to trim. If the playhead is just as close,
                    // the playhead wins (it can sit right on a cut); otherwise whichever is nearer.
                    val edge = if (onRuler || onHeader) null else edgeAt(down.position, trimPx)
                        ?.takeIf { !onPlayhead || it.distance < abs(grabOffset) }
                    if (edge != null) onPlayhead = false
                    var mode = if (onRuler) Mode.SCRUB else Mode.UNDECIDED
                    if (onRuler) vm.scrubTo(frameAt(down.position.x), snapPx)
                    var total = Offset.Zero
                    // Holding a finger on a track header (without moving) asks to delete that track.
                    val holdUntil = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                    while (true) {
                        val event = if (onHeader && mode == Mode.UNDECIDED) {
                            val now = currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
                            withTimeoutOrNull((holdUntil - now).coerceAtLeast(1L)) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (event == null) {
                            mode = Mode.HELD
                            val lane = laneAt(down.position.y)
                            if (lane in 0 until vm.laneCount) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.askDeleteTrack(lane)
                            }
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 || mode == Mode.PINCH) {
                            if (mode == Mode.MOVE || mode == Mode.TRIM) vm.cancelDrag()
                            if (mode == Mode.SCRUB || mode == Mode.PLAYHEAD) vm.endScrub()
                            mode = Mode.PINCH
                            if (pressed.size >= 2) {
                                val zoom = event.calculateZoom()
                                val centroid = event.calculateCentroid()
                                if (zoom != 1f && centroid.isSpecified) vm.zoom(zoom, centroid.x - headerW)
                                vm.scrollByPx(event.calculatePan().x)
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val ch = pressed.first()
                        val delta = ch.positionChange()
                        total += delta
                        if (mode == Mode.UNDECIDED && total.getDistance() > viewConfiguration.touchSlop) {
                            mode = when {
                                onPlayhead -> Mode.PLAYHEAD
                                edge != null -> { vm.beginTrim(edge.clip.id, edge.atStart); Mode.TRIM }
                                hit != null -> { vm.beginDrag(hit.id); Mode.MOVE }
                                else -> Mode.PAN
                            }
                        }
                        when (mode) {
                            Mode.SCRUB -> vm.scrubTo(frameAt(ch.position.x), snapPx)
                            Mode.PLAYHEAD -> vm.scrubTo(frameAt(ch.position.x - grabOffset), snapPx)
                            Mode.MOVE -> vm.dragTo(total.x, laneAt(ch.position.y), snapPx)
                            Mode.TRIM -> vm.trimTo(total.x, snapPx)
                            Mode.PAN -> {
                                vm.scrollByPx(delta.x)
                                val maxY = (rulerH + vm.laneCount * trackH - size.height).coerceAtLeast(0f)
                                scrollY = (scrollY - delta.y).coerceIn(0f, maxY)
                            }
                            else -> {}
                        }
                        ch.consume()
                    }
                    when (mode) {
                        Mode.MOVE -> vm.endDrag()
                        Mode.TRIM -> vm.endTrim()
                        Mode.SCRUB, Mode.PLAYHEAD -> vm.endScrub()
                        Mode.UNDECIDED -> when {
                            onHeader -> {
                                val lane = laneAt(down.position.y)
                                if (lane in 0 until vm.laneCount) {
                                    val (video, track) = vm.laneKind(lane)
                                    val laneTop = rulerH + lane * trackH - scrollY
                                    val buttonsTop = laneTop + trackH - HeaderButton.toPx() - 12.dp.toPx()
                                    // Track name (top): make it the destination track for paste and import (hold to delete the track).  Below it, left half: lock button,
                                    // right half: mute button (audio tracks only).
                                    if (down.position.y < buttonsTop) vm.setTarget(video, track)
                                    else if (down.position.x < headerW / 2 || video) vm.toggleLock(video, track)
                                    else vm.toggleMute(track)
                                }
                            }
                            hit != null -> vm.select(hit.id)
                            else -> {
                                vm.select(null)
                                vm.scrubTo(frameAt(down.position.x), snapPx)
                                vm.endScrub()
                            }
                        }
                        else -> {}
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val spp = vm.samplesPerPx
        val maxY = (rulerH + vm.laneCount * trackH - h).coerceAtLeast(0f)
        if (scrollY > maxY) scrollY = maxY   // e.g. after undoing "add track"
        drawRect(Background)

        // Lane backgrounds
        for (lane in 0 until vm.laneCount) {
            val top = rulerH + lane * trackH - scrollY
            val video = vm.laneKind(lane).first
            drawRect(if (video) VideoLane else if (lane % 2 == 0) TrackA else TrackB, Offset(0f, top), Size(w, trackH))
        }

        // Clips
        val list = layout.list
        val previewIds = layout.previewIds
        val visStart = layout.visStart
        val visEnd = layout.visEnd
        val selected = vm.selectedIds
        val waveColor = Color.White.copy(alpha = 0.7f)

        for (c in list) {
            val vs = visStart[c.id] ?: c.start
            val ve = visEnd[c.id] ?: c.end
            val x0 = xOf(vs)
            val x1 = xOf(ve)
            if (x1 < headerW || x0 > w) continue
            val top = rulerH + vm.laneOf(c) * trackH - scrollY + 4f
            val ch = trackH - 8f
            val muted = !c.video && c.track in vm.mutedTracks
            val alpha = when {
                c.id in previewIds -> 0.75f
                muted -> 0.4f
                else -> 1f
            }
            val base = if (c.video) VideoClipColor else ClipColors[c.track % ClipColors.size]
            drawRoundRect(base.copy(alpha = alpha), Offset(x0, top), Size(x1 - x0, ch), CornerRadius(10f))
            val src = vm.sources[c.sourceId]

            if (c.video) {
                vm.thumbnails[c.sourceId]?.let { thumbs -> drawThumbnails(c, thumbs, x0, x1, top, ch, w, spp) }
            } else if (src != null && src.peaks.isNotEmpty()) {
                // Waveform (with fades applied), batched into one path per clip.
                val mid = top + ch / 2
                val half = ch / 2 - 6f
                val step = 2
                val wave = Path()
                var x = max(x0, headerW).toInt()
                val xEnd = min(x1, w).toInt()
                while (x < xEnd) {
                    val rel = (vm.scrollFrames + (x - headerW) * spp).toLong() - c.start
                    if (rel in 0 until c.length) {
                        val s0 = c.sourceIn + rel
                        val p0 = (s0 / PEAK_BLOCK).toInt()
                        val p1 = ((s0 + (spp * step).toLong()) / PEAK_BLOCK).toInt()
                        var peak = 0f
                        var i = p0
                        while (i <= p1 && i < src.peaks.size) {
                            if (src.peaks[i] > peak) peak = src.peaks[i]
                            i++
                        }
                        val amp = maxOf(peak.coerceAtMost(1f) * c.gainAt(rel) * half, 0.5f)
                        wave.moveTo(x.toFloat(), mid - amp)
                        wave.lineTo(x.toFloat(), mid + amp)
                    }
                    x += step
                }
                drawPath(wave, waveColor.copy(alpha = 0.7f * alpha), style = Stroke(width = 1.5f))
            }

            // Fade shapes (fades that are part of a crossfade are drawn with the transition block instead)
            if (c.fadeIn.frames > 0 && vs == c.start) {
                drawFade(x0, xOf(c.start + c.fadeIn.frames), top, ch, true, c.fadeIn.curve)
            }
            if (c.fadeOut.frames > 0 && ve == c.end) {
                drawFade(xOf(c.end - c.fadeOut.frames), x1, top, ch, false, c.fadeOut.curve)
            }

            // Name (with a link mark for linked clips)
            val textX = max(x0, headerW) + 8f
            // Only the on-screen part of the clip: a long clip can be hundreds of thousands of pixels wide,
            // more than text layout can handle.
            val textW = min(x1, w) - textX - 6f
            val name = (if (c.linkId != null) "🔗 " else "") + (src?.name ?: tr("missing", "見つかりません"))
            if (textW > 24f) {
                drawText(
                    textMeasurer, name, Offset(textX, top + 4f),
                    TextStyle(color = Color.White, fontSize = 11.sp),
                    overflow = TextOverflow.Ellipsis, maxLines = 1, size = Size(textW, ch / 2),
                )
            }
            // Clip markers: small flags along the top of the clip, hanging from its top edge.
            val cm = c.visibleMarkers()
            if (cm.isNotEmpty()) {
                val fw = 5.dp.toPx()
                val fh = 12.dp.toPx()
                clipRect(max(x0, headerW), top, min(x1, w), top + ch) {
                    for ((m, at) in cm) {
                        val mx = xOf(at)
                        val flag = Path().apply {
                            moveTo(mx - fw, top); lineTo(mx + fw, top); lineTo(mx + fw, top + fh - fw)
                            lineTo(mx, top + fh); lineTo(mx - fw, top + fh - fw); close()
                        }
                        drawPath(flag, Color(m.color.argb))
                        drawPath(flag, Color.Black.copy(alpha = 0.5f), style = Stroke(width = 1f))
                    }
                }
            }

            if (c.id in selected) {
                drawRoundRect(
                    Color.White, Offset(x0, top), Size(x1 - x0, ch), CornerRadius(10f),
                    style = Stroke(width = 3.dp.toPx()),
                )
                // Trim handles: a hint that the edges can be dragged.
                val hw = 4.dp.toPx()
                val hh = ch * 0.4f
                val hy = top + (ch - hh) / 2
                if (vs == c.start) drawRoundRect(Color.White, Offset(x0, hy), Size(hw, hh), CornerRadius(hw / 2))
                if (ve == c.end) drawRoundRect(Color.White, Offset(x1 - hw, hy), Size(hw, hh), CornerRadius(hw / 2))
            }
        }

        // Crossfade blocks: one box over the overlap, with the outgoing and incoming curves crossing in an X.
        for ((a, b) in layout.transitions) {
            val xs = xOf(b.start)
            val xe = xOf(a.end)
            if (xe < headerW || xs > w) continue
            val top = rulerH + vm.laneOf(a) * trackH - scrollY + 4f
            val ch = trackH - 8f
            val dim = if (a.track in vm.mutedTracks) 0.4f else 1f
            drawRoundRect(TransitionFill, Offset(xs, top), Size(xe - xs, ch), CornerRadius(6f))
            val outLine = Path()
            val inLine = Path()
            val n = 32
            for (i in 0..n) {
                val t = i / n.toFloat()
                val f = b.start + ((a.end - b.start) * t).toLong()
                val x = xs + (xe - xs) * t
                val yOut = top + ch * (1f - a.gainAt(f - a.start))
                val yIn = top + ch * (1f - b.gainAt(f - b.start))
                if (i == 0) {
                    outLine.moveTo(x, yOut); inLine.moveTo(x, yIn)
                } else {
                    outLine.lineTo(x, yOut); inLine.lineTo(x, yIn)
                }
            }
            drawPath(outLine, Color.White.copy(alpha = dim), style = Stroke(width = 2f))
            drawPath(inLine, Color.White.copy(alpha = dim), style = Stroke(width = 2f))
            drawRoundRect(
                TransitionEdge.copy(alpha = dim), Offset(xs, top), Size(xe - xs, ch), CornerRadius(6f),
                style = Stroke(width = 1.5f),
            )
        }

        // Snap guide
        vm.snapLine?.let {
            val x = xOf(it)
            drawLine(SnapColor, Offset(x, rulerH), Offset(x, h), strokeWidth = 2.dp.toPx())
        }

        // Locked tracks are shaded over, so it's clear their clips can't be touched.
        for (lane in 0 until vm.laneCount) {
            val (video, track) = vm.laneKind(lane)
            if (!vm.isLocked(video, track)) continue
            val top = rulerH + lane * trackH - scrollY
            drawRect(LockShade, Offset(headerW, top), Size(w - headerW, trackH))
        }

        // Track headers: V1, A1…, a lock button on every track, and a mute button on audio tracks
        drawRect(HeaderBg, Offset(0f, rulerH), Size(headerW, h - rulerH))
        val box = HeaderButton.toPx()
        val gap = HeaderGap.toPx()
        for (lane in 0 until vm.laneCount) {
            val top = rulerH + lane * trackH - scrollY
            val (video, track) = vm.laneKind(lane)
            // The paste track for each kind is highlighted, like Resolve's destination track control.
            val target = track == if (video) vm.targetVideo else vm.targetAudio
            if (target) {
                drawRoundRect(TargetOn, Offset(gap, top + 4f), Size(headerW - gap * 2, 18.dp.toPx()), CornerRadius(6f))
            }
            drawText(
                textMeasurer, (if (video) "V" else "A") + (track + 1), Offset(gap * 2, top + 8f),
                TextStyle(color = if (target) Color.White else TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold),
            )
            val by = top + trackH - box - 8.dp.toPx()
            val locked = vm.isLocked(video, track)
            drawRoundRect(
                if (locked) LockOn else TextDim.copy(alpha = 0.25f),
                Offset(gap, by), Size(box, box), CornerRadius(6f),
            )
            val inset = 3.dp.toPx()
            translate(gap + inset, by + inset) {
                with(if (locked) lockIcon else unlockIcon) {
                    draw(Size(box - inset * 2, box - inset * 2), colorFilter = ColorFilter.tint(if (locked) Color.Black else TextDim))
                }
            }
            if (!video) {
                val muted = track in vm.mutedTracks
                val bx = gap * 2 + box
                drawRoundRect(
                    if (muted) MuteOn else TextDim.copy(alpha = 0.25f),
                    Offset(bx, by), Size(box, box), CornerRadius(6f),
                )
                drawText(
                    textMeasurer, "M", Offset(bx + box / 2 - 4.dp.toPx(), by + 3.dp.toPx()),
                    TextStyle(color = if (muted) Color.Black else TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                )
            }
            drawLine(Background, Offset(0f, top + trackH), Offset(headerW, top + trackH), strokeWidth = 1f)
        }

        // Ruler
        drawRect(RulerBg, Offset.Zero, Size(w, rulerH))
        val secPerPx = spp / SAMPLE_RATE
        val stepSec = RulerSteps.firstOrNull { it >= 90 * secPerPx } ?: 600.0
        val firstIndex = floor(vm.scrollFrames / SAMPLE_RATE / stepSec).toLong()
        var i = 0L
        while (true) {
            val sec = (firstIndex + i) * stepSec
            val x = headerW + ((sec * SAMPLE_RATE - vm.scrollFrames) / spp).toFloat()
            if (x > w) break
            if (x >= headerW) {
                drawLine(TextDim, Offset(x, rulerH * 0.55f), Offset(x, rulerH), strokeWidth = 1f)
                drawText(textMeasurer, rulerLabel(sec, stepSec), Offset(x + 4f, 2f), TextStyle(color = TextDim, fontSize = 10.sp))
            }
            i++
        }

        // Markers: Resolve-style flags (a square with a point at the bottom) along the lower edge of the ruler.
        val mw = 6.dp.toPx()
        val mTop = rulerH * 0.35f
        for (m in vm.markers) {
            val x = xOf(m.frame)
            if (x < headerW - mw || x > w + mw) continue
            val flag = Path().apply {
                moveTo(x - mw, mTop); lineTo(x + mw, mTop); lineTo(x + mw, rulerH - mw)
                lineTo(x, rulerH); lineTo(x - mw, rulerH - mw); close()
            }
            clipRect(headerW, 0f, w, h) {
                drawPath(flag, Color(m.color.argb))
                drawPath(flag, Color.Black.copy(alpha = 0.5f), style = Stroke(width = 1f))
            }
        }

        // Playhead
        val px = xOf(vm.playhead)
        if (px >= headerW) {
            drawLine(PlayheadColor, Offset(px, 0f), Offset(px, h), strokeWidth = 2.dp.toPx())
            val tri = Path().apply {
                moveTo(px - 8.dp.toPx(), 0f); lineTo(px + 8.dp.toPx(), 0f); lineTo(px, 10.dp.toPx()); close()
            }
            drawPath(tri, PlayheadColor)
        }
    }
}

/**
 * The clips to draw (with a drag preview standing in for the clips being dragged) and where crossfades split
 * them: each clip in a crossfade is drawn up to the middle of the overlap, and the overlap itself is drawn as
 * one transition block on top, like Resolve.
 */
private class ClipLayout(
    val list: List<Clip>,
    val previewIds: Set<Long>,
    val visStart: Map<Long, Long>,
    val visEnd: Map<Long, Long>,
    val transitions: List<Pair<Clip, Clip>>,
) {
    companion object {
        fun of(clips: List<Clip>, preview: List<Clip>?): ClipLayout {
            val previewIds = preview?.map { it.id }?.toSet() ?: emptySet()
            val list = if (preview != null) clips.filter { it.id !in previewIds } + preview else clips
            val visStart = HashMap<Long, Long>()
            val visEnd = HashMap<Long, Long>()
            val transitions = ArrayList<Pair<Clip, Clip>>()
            for (laneClips in list.filter { !it.video && it.id !in previewIds }.groupBy { it.track }.values) {
                val sorted = laneClips.sortedBy { it.start }
                for (i in TimelineOps.crossfadePairs(sorted)) {
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val mid = (b.start + a.end) / 2
                    visEnd[a.id] = min(visEnd[a.id] ?: a.end, mid)
                    visStart[b.id] = max(visStart[b.id] ?: b.start, mid)
                    transitions += a to b
                }
            }
            return ClipLayout(list, previewIds, visStart, visEnd, transitions)
        }
    }
}

/** Tiles the video's thumbnails along the clip, each showing the frame nearest its position. */
private fun DrawScope.drawThumbnails(
    c: Clip, thumbs: List<Thumb>, x0: Float, x1: Float, top: Float, ch: Float, w: Float, spp: Double,
) {
    if (thumbs.isEmpty()) return
    val img0 = thumbs[0].image
    val tileH = (ch - 18f).coerceAtLeast(8f)
    val tileW = tileH * img0.width / img0.height.coerceAtLeast(1)
    val tileTop = top + 16f
    clipRect(max(x0, 0f), top, min(x1, w), top + ch) {
        var x = x0
        while (x < x1 && x < w) {
            if (x + tileW >= 0) {
                val frame = c.sourceIn + ((x + tileW / 2 - x0) * spp).toLong()
                var lo = 0
                var hi = thumbs.size - 1
                while (lo < hi) {               // last thumb at or before this frame
                    val mid = (lo + hi + 1) / 2
                    if (thumbs[mid].frame <= frame) lo = mid else hi = mid - 1
                }
                drawImage(
                    thumbs[lo].image,
                    dstOffset = IntOffset(x.roundToInt(), tileTop.roundToInt()),
                    dstSize = IntSize(tileW.roundToInt().coerceAtLeast(1), tileH.roundToInt()),
                )
            }
            x += tileW
        }
    }
}

private fun DrawScope.drawFade(
    xs: Float, xe: Float, top: Float, ch: Float, isIn: Boolean, curve: FadeCurve,
) {
    val shade = Path()
    val line = Path()
    shade.moveTo(xs, top)
    val n = 24
    for (i in 0..n) {
        val t = i / n.toFloat()
        val x = xs + (xe - xs) * t
        val g = if (isIn) fadeInGain(t, curve) else fadeInGain(1f - t, curve)
        val y = top + ch * (1f - g)
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        shade.lineTo(x, y)
    }
    shade.lineTo(xe, top)
    shade.close()
    drawPath(shade, Color.Black.copy(alpha = 0.35f))
    drawPath(line, Color.White, style = Stroke(width = 2f))
}

private fun rulerLabel(sec: Double, step: Double): String {
    val m = (sec / 60).toInt()
    val s = sec - m * 60
    return if (step >= 1.0) "%d:%02d".format(m, s.toInt()) else "%d:%05.2f".format(m, s)
}
