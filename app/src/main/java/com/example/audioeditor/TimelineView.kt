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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private enum class Mode { UNDECIDED, SCRUB, MOVE, PAN, PINCH }

private val Background = Color(0xFF1C1D21)
private val TrackA = Color(0xFF24262B)
private val TrackB = Color(0xFF282A30)
private val RulerBg = Color(0xFF30333A)
private val TextDim = Color(0xFFB8BCC6)
private val PlayheadColor = Color(0xFFFF5A4E)
private val SnapColor = Color(0xFFFFD54A)
private val ClipColors = listOf(Color(0xFF2F7A5C), Color(0xFF3B5DA3), Color(0xFF7D4FA3), Color(0xFFA0683A))
private val RulerSteps = doubleArrayOf(
    0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0,
)

/**
 * Gestures:
 *  - drag on the ruler (top strip): move the playhead
 *  - tap a clip: select it.  Tap empty space: deselect and move the playhead there
 *  - press and drag a clip: move it (left/right in time, up/down between tracks)
 *  - drag empty space: scroll.  Pinch with two fingers: zoom
 */
@Composable
fun TimelineView(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rulerH = with(density) { 28.dp.toPx() }
    val trackH = with(density) { 76.dp.toPx() }
    var scrollY by remember { mutableFloatStateOf(0f) }

    fun frameAt(x: Float) = (vm.scrollFrames + x * vm.samplesPerPx).toLong().coerceAtLeast(0L)
    fun xOf(f: Long) = ((f - vm.scrollFrames) / vm.samplesPerPx).toFloat()
    fun trackAt(y: Float) = floor((y - rulerH + scrollY) / trackH).toInt()
    fun clipAt(p: Offset): Clip? {
        if (p.y < rulerH) return null
        val t = trackAt(p.y)
        val f = frameAt(p.x)
        return vm.clips.lastOrNull { it.track == t && f >= it.start && f < it.end }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { vm.viewWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                val snapPx = 14.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val onRuler = down.position.y < rulerH
                    val hit = clipAt(down.position)
                    var mode = if (onRuler) Mode.SCRUB else Mode.UNDECIDED
                    if (onRuler) vm.seek(frameAt(down.position.x))
                    var total = Offset.Zero
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 || mode == Mode.PINCH) {
                            if (mode == Mode.MOVE) vm.cancelDrag()
                            mode = Mode.PINCH
                            if (pressed.size >= 2) {
                                val zoom = event.calculateZoom()
                                val centroid = event.calculateCentroid()
                                if (zoom != 1f && centroid.isSpecified) vm.zoom(zoom, centroid.x)
                                vm.scrollByPx(event.calculatePan().x)
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val ch = pressed.first()
                        val delta = ch.positionChange()
                        total += delta
                        if (mode == Mode.SCRUB) {
                            vm.seek(frameAt(ch.position.x))
                        } else {
                            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                if (hit != null) { mode = Mode.MOVE; vm.beginDrag(hit.id) } else mode = Mode.PAN
                            }
                            when (mode) {
                                Mode.MOVE -> vm.dragTo(total.x, trackAt(ch.position.y), snapPx)
                                Mode.PAN -> {
                                    vm.scrollByPx(delta.x)
                                    val maxY = (rulerH + vm.trackCount * trackH - size.height).coerceAtLeast(0f)
                                    scrollY = (scrollY - delta.y).coerceIn(0f, maxY)
                                }
                                else -> {}
                            }
                        }
                        ch.consume()
                    }
                    when (mode) {
                        Mode.MOVE -> vm.endDrag()
                        Mode.UNDECIDED -> if (hit != null) vm.select(hit.id) else {
                            vm.select(null)
                            vm.seek(frameAt(down.position.x))
                        }
                        else -> {}
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val spp = vm.samplesPerPx
        drawRect(Background)

        // Tracks
        for (t in 0 until vm.trackCount) {
            val top = rulerH + t * trackH - scrollY
            drawRect(if (t % 2 == 0) TrackA else TrackB, Offset(0f, top), Size(w, trackH))
        }

        // Clips
        val preview = vm.dragPreview
        val list = if (preview != null) vm.clips.filter { it.id != preview.id } + preview else vm.clips
        for (c in list) {
            val x0 = xOf(c.start)
            val x1 = xOf(c.end)
            if (x1 < 0 || x0 > w) continue
            val top = rulerH + c.track * trackH - scrollY + 4f
            val ch = trackH - 8f
            val base = ClipColors[c.track % ClipColors.size]
            val isSelected = c.id == vm.selectedId
            drawRoundRect(
                base.copy(alpha = if (c === preview) 0.75f else 1f),
                Offset(x0, top), Size(x1 - x0, ch), CornerRadius(10f),
            )

            // Waveform (shown with fades applied)
            val src = vm.sources[c.sourceId]
            if (src != null) {
                val mid = top + ch / 2
                val half = ch / 2 - 6f
                val step = 2
                var x = max(x0, 0f).toInt()
                val xEnd = min(x1, w).toInt()
                while (x < xEnd) {
                    val rel = (vm.scrollFrames + x * spp).toLong() - c.start
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
                        val amp = peak.coerceAtMost(1f) * c.gainAt(rel) * half
                        drawLine(
                            Color.White.copy(alpha = 0.7f),
                            Offset(x.toFloat(), mid - amp), Offset(x.toFloat(), mid + amp), strokeWidth = 1.5f,
                        )
                    }
                    x += step
                }
            }

            // Fade shapes
            if (c.fadeIn.frames > 0) drawFade(x0, xOf(c.start + c.fadeIn.frames), top, ch, true, c.fadeIn.curve)
            if (c.fadeOut.frames > 0) drawFade(xOf(c.end - c.fadeOut.frames), x1, top, ch, false, c.fadeOut.curve)

            // Name
            val textX = max(x0, 0f) + 8f
            val textW = x1 - textX - 6f
            val name = src?.name ?: "missing"
            if (textW > 24f) {
                drawText(
                    textMeasurer, name, Offset(textX, top + 4f),
                    TextStyle(color = Color.White, fontSize = 11.sp),
                    overflow = TextOverflow.Ellipsis, maxLines = 1, size = Size(textW, ch / 2),
                )
            }
            if (isSelected) {
                drawRoundRect(
                    Color.White, Offset(x0, top), Size(x1 - x0, ch), CornerRadius(10f),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        // Track labels
        for (t in 0 until vm.trackCount) {
            val top = rulerH + t * trackH - scrollY
            drawText(
                textMeasurer, "A${t + 1}", Offset(6f, top + trackH - 20.dp.toPx()),
                TextStyle(color = TextDim, fontSize = 10.sp),
            )
        }

        // Snap guide
        vm.snapLine?.let {
            val x = xOf(it)
            drawLine(SnapColor, Offset(x, rulerH), Offset(x, h), strokeWidth = 2.dp.toPx())
        }

        // Ruler
        drawRect(RulerBg, Offset.Zero, Size(w, rulerH))
        val secPerPx = spp / SAMPLE_RATE
        val stepSec = RulerSteps.firstOrNull { it >= 90 * secPerPx } ?: 600.0
        val firstIndex = floor(vm.scrollFrames / SAMPLE_RATE / stepSec).toLong()
        var i = 0L
        while (true) {
            val sec = (firstIndex + i) * stepSec
            val x = ((sec * SAMPLE_RATE - vm.scrollFrames) / spp).toFloat()
            if (x > w) break
            drawLine(TextDim, Offset(x, rulerH * 0.55f), Offset(x, rulerH), strokeWidth = 1f)
            drawText(textMeasurer, rulerLabel(sec, stepSec), Offset(x + 4f, 2f), TextStyle(color = TextDim, fontSize = 10.sp))
            i++
        }

        // Playhead
        val px = xOf(vm.playhead)
        drawLine(PlayheadColor, Offset(px, 0f), Offset(px, h), strokeWidth = 2.dp.toPx())
        val tri = Path().apply {
            moveTo(px - 8.dp.toPx(), 0f); lineTo(px + 8.dp.toPx(), 0f); lineTo(px, 10.dp.toPx()); close()
        }
        drawPath(tri, PlayheadColor)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFade(
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
