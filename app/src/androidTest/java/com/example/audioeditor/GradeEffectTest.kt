package com.example.audioeditor

import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Plays a solid-colour video through the preview's colour pass and checks the colours that come out. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class GradeEffectTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val w = 320
    private val h = 240

    /** One second of a single colour, H.264, made with the phone's own encoder. */
    private fun makeVideo(color: Int): File {
        val out = File(ctx.cacheDir, "grade_test.mp4")
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val i = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(enc.outputFormat); mux.start() }
                    i >= 0 -> {
                        val buf = enc.getOutputBuffer(i)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) mux.writeSampleData(track, buf, info)
                        enc.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        repeat(30) {
            val c = surface.lockHardwareCanvas()
            c.drawColor(color)
            surface.unlockCanvasAndPost(c)
            Thread.sleep(33)
            drain(false)
        }
        enc.signalEndOfInputStream()
        drain(true)
        enc.stop(); enc.release(); surface.release()
        mux.stop(); mux.release()
        return out
    }

    /** The colour in the middle of the first frame the player shows with [grade]. */
    private fun render(video: File, grade: GradeState): Int {
        val thread = HandlerThread("reader").apply { start() }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val got = CountDownLatch(1)
        var pixel = 0
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (got.count > 0) {
                val p = img.planes[0]
                val i = (h / 2) * p.rowStride + (w / 2) * p.pixelStride
                val b = p.buffer
                pixel = Color.rgb(b.get(i).toInt() and 0xFF, b.get(i + 1).toInt() and 0xFF, b.get(i + 2).toInt() and 0xFF)
                got.countDown()
            }
            img.close()
        }, Handler(thread.looper))
        val live = LiveGrade().apply { state = grade }
        lateinit var player: ExoPlayer
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync {
            // Software decoders: the emulator's hardware H.264 decoder can't be used from tests.
            val renderers = RedrawableRenderersFactory(ctx).setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { it.hardwareAccelerated }
            }
            player = ExoPlayer.Builder(ctx, renderers).build()
            player.setVideoEffects(listOf(GradeEffect(live)))
            player.setVideoSurface(reader.surface)
            // A bare Surface has no known size (a SurfaceView, as in the app, reports its own), so give it here.
            for (i in 0 until player.rendererCount) {
                if (player.getRendererType(i) != C.TRACK_TYPE_VIDEO) continue
                player.createMessage(player.getRenderer(i))
                    .setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION).setPayload(Size(w, h)).send()
            }
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
            player.prepare()
            player.play()
        }
        val ok = got.await(15, TimeUnit.SECONDS)
        main.runOnMainSync { player.release() }
        reader.close()
        thread.quitSafely()
        assertEquals("a frame was shown", true, ok)
        return pixel
    }

    /** Shows the first frame paused, then changes the grade and asks for a redraw, as the sliders do. */
    @Test
    fun pausedFrameIsRedrawnWithANewGrade() {
        val video = makeVideo(Color.rgb(200, 100, 50))
        val thread = HandlerThread("reader").apply { start() }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val frames = java.util.concurrent.LinkedBlockingQueue<Int>()
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val p = img.planes[0]
            val i = (h / 2) * p.rowStride + (w / 2) * p.pixelStride
            val b = p.buffer
            frames += Color.rgb(b.get(i).toInt() and 0xFF, b.get(i + 1).toInt() and 0xFF, b.get(i + 2).toInt() and 0xFF)
            img.close()
        }, Handler(thread.looper))
        val live = LiveGrade()
        lateinit var player: ExoPlayer
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync {
            val renderers = RedrawableRenderersFactory(ctx).setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { it.hardwareAccelerated }
            }
            player = ExoPlayer.Builder(ctx, renderers).build()
            player.setVideoEffects(listOf(GradeEffect(live)))
            player.setVideoSurface(reader.surface)
            for (i in 0 until player.rendererCount) {
                if (player.getRendererType(i) != C.TRACK_TYPE_VIDEO) continue
                player.createMessage(player.getRenderer(i))
                    .setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION).setPayload(Size(w, h)).send()
            }
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
            player.playWhenReady = false   // paused: only the first frame is shown
            player.prepare()
        }
        val plain = frames.poll(15, TimeUnit.SECONDS)
        assertNotNull("the paused frame was shown", plain)
        Thread.sleep(500)
        frames.clear()
        live.state = GradeState(CubeLut(2, INVERT), 0f, 0f, 0f)
        main.runOnMainSync { player.setVideoEffects(androidx.media3.common.VideoFrameProcessor.REDRAW) }
        val redrawn = frames.poll(5, TimeUnit.SECONDS)
        main.runOnMainSync { player.release() }
        reader.close()
        thread.quitSafely()
        assertNotNull("a new frame came after the redraw", redrawn)
        assertColor(Color.rgb(255 - Color.red(plain!!), 255 - Color.green(plain), 255 - Color.blue(plain)), redrawn!!)
    }

    private val INVERT = floatArrayOf(1f, 1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 0f)

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 14) {
        val msg = "expected ${Integer.toHexString(expected)} got ${Integer.toHexString(actual)}"
        assertEquals(msg, Color.red(expected).toFloat(), Color.red(actual).toFloat(), tolerance.toFloat())
        assertEquals(msg, Color.green(expected).toFloat(), Color.green(actual).toFloat(), tolerance.toFloat())
        assertEquals(msg, Color.blue(expected).toFloat(), Color.blue(actual).toFloat(), tolerance.toFloat())
    }

    @Test
    fun lutAndAdjustmentsChangeTheFrame() {
        val src = Color.rgb(200, 100, 50)
        val video = makeVideo(src)

        val plain = render(video, GradeState.NONE)
        assertColor(src, plain)

        // An inverting LUT, stored large enough (40 points) that it's also shrunk for the GPU.
        val invert = CubeLut(2, floatArrayOf(1f, 1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 0f))
            .resized(40)
        val inverted = render(video, GradeState(invert, 0f, 0f, 0f))
        assertColor(Color.rgb(255 - Color.red(plain), 255 - Color.green(plain), 255 - Color.blue(plain)), inverted)

        // One stop brighter, worked out the same way as on the GPU.
        val brighter = render(video, GradeState(null, 1f, 0f, 0f))
        fun up(v: Int) = (GradeMath.expose(v / 255f, 1f).coerceAtMost(1f) * 255).toInt()
        assertColor(Color.rgb(up(Color.red(plain)), up(Color.green(plain)), up(Color.blue(plain))), brighter)

        assertNotNull(video)
    }
}
