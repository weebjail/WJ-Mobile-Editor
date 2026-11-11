package com.example.audioeditor

import android.content.Context
import android.net.Uri
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A video clip's colour settings, applied in Resolve's order: the LUT first (e.g. GP-Log or F-Log to Rec.709),
 * then exposure (in stops), then highlights and shadows (-1..1). [lut] names a LUT in the [LutLibrary].
 */
data class Grade(
    val lut: String? = null,
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
) {
    val isNeutral: Boolean get() = lut == null && exposure == 0f && highlights == 0f && shadows == 0f
}

/** A 3D LUT from a .cube file. [data] holds RGB triples with red changing fastest, then green, then blue. */
class CubeLut(
    val size: Int,
    val data: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    init {
        require(size >= 2 && data.size == size * size * size * 3)
    }

    private fun at(r: Int, g: Int, b: Int, ch: Int) = data[((b * size + g) * size + r) * 3 + ch]

    /** The LUT's output for an input colour (0..1 within its domain), blending between its points. */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val n = size - 1
        fun pos(v: Float, ch: Int) =
            ((v - domainMin[ch]) / (domainMax[ch] - domainMin[ch])).coerceIn(0f, 1f) * n
        val x = pos(r, 0); val y = pos(g, 1); val z = pos(b, 2)
        val x0 = floor(x).toInt().coerceAtMost(n - 1); val y0 = floor(y).toInt().coerceAtMost(n - 1)
        val z0 = floor(z).toInt().coerceAtMost(n - 1)
        val fx = x - x0; val fy = y - y0; val fz = z - z0
        return FloatArray(3) { ch ->
            fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
            val c00 = lerp(at(x0, y0, z0, ch), at(x0 + 1, y0, z0, ch), fx)
            val c10 = lerp(at(x0, y0 + 1, z0, ch), at(x0 + 1, y0 + 1, z0, ch), fx)
            val c01 = lerp(at(x0, y0, z0 + 1, ch), at(x0 + 1, y0, z0 + 1, ch), fx)
            val c11 = lerp(at(x0, y0 + 1, z0 + 1, ch), at(x0 + 1, y0 + 1, z0 + 1, ch), fx)
            lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz)
        }
    }

    /** The same LUT with [n] points per side (big LUTs are made smaller to fit in a GPU texture). */
    fun resized(n: Int): CubeLut {
        if (n == size) return this
        val out = FloatArray(n * n * n * 3)
        var k = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val v = sample(
                domainMin[0] + (domainMax[0] - domainMin[0]) * r / (n - 1f),
                domainMin[1] + (domainMax[1] - domainMin[1]) * g / (n - 1f),
                domainMin[2] + (domainMax[2] - domainMin[2]) * b / (n - 1f),
            )
            out[k++] = v[0]; out[k++] = v[1]; out[k++] = v[2]
        }
        return CubeLut(n, out, domainMin, domainMax)
    }

    /**
     * RGBA bytes for a 2D texture holding the cube as [size] slices side by side (one per blue level):
     * [size]×[size] wide and [size] high, so it works on every phone (3D textures need OpenGL ES 3).
     */
    fun toTextureBytes(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(size * size * size * 4).order(ByteOrder.nativeOrder())
        for (g in 0 until size) for (b in 0 until size) for (r in 0 until size) {
            for (ch in 0 until 3) buf.put((at(r, g, b, ch).coerceIn(0f, 1f) * 255f).roundToInt().toByte())
            buf.put(0xFF.toByte())
        }
        buf.rewind()
        return buf
    }

    companion object {
        /** Reads a .cube file (Adobe / Resolve format, as published by GoPro, Fujifilm and others). */
        fun parse(text: String): CubeLut {
            var size = 0
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            var values: FloatArray? = null
            var count = 0
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                val key = parts[0].uppercase()
                when {
                    key == "LUT_3D_SIZE" -> {
                        size = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad LUT_3D_SIZE")
                        if (size !in 2..256) throw IOException("Unsupported LUT size $size")
                        values = FloatArray(size * size * size * 3)
                    }
                    key == "LUT_1D_SIZE" -> throw IOException("This is a 1D LUT; only 3D LUTs are supported")
                    key == "DOMAIN_MIN" || key == "DOMAIN_MAX" -> {
                        val target = if (key == "DOMAIN_MIN") min else max
                        for (c in 0 until 3) target[c] = parts.getOrNull(c + 1)?.toFloatOrNull() ?: throw IOException("Bad $key")
                    }
                    key == "TITLE" || key.startsWith("LUT_") || key[0].isLetter() -> {}   // other keywords
                    else -> {
                        val v = values ?: throw IOException("LUT_3D_SIZE is missing")
                        if (parts.size < 3) throw IOException("Bad line: $line")
                        if (count + 3 > v.size) throw IOException("Too many entries")
                        for (c in 0 until 3) v[count++] = parts[c].toFloatOrNull() ?: throw IOException("Bad number: ${parts[c]}")
                    }
                }
            }
            val v = values ?: throw IOException("Not a 3D .cube LUT")
            if (count != v.size) throw IOException("The LUT is incomplete (${count / 3} of ${v.size / 3} entries)")
            for (c in 0 until 3) if (max[c] <= min[c]) throw IOException("Bad DOMAIN_MIN / DOMAIN_MAX")
            return CubeLut(size, v, min, max)
        }

        /** Largest LUT used on the GPU (a 65-point LUT would need a 4225-pixel-wide texture). */
        const val MAX_GPU_SIZE = 33
    }
}

/** Shader math shared with the tests: the tone curve used for highlights and shadows. */
object GradeMath {
    /** How strongly the highlights / shadows sliders bend the curve. Small enough to keep it smooth. */
    const val TONE_STRENGTH = 0.14f * 6.75f

    /** New brightness for brightness [y] (0..1). Black and white stay put; shadows peak at 1/3, highlights at 2/3. */
    fun tone(y: Float, highlights: Float, shadows: Float): Float {
        val inv = 1f - y
        return y + TONE_STRENGTH * (shadows * y * inv * inv + highlights * y * y * inv)
    }

    /** Exposure in stops applied to a Rec.709 (gamma-encoded) value. */
    fun expose(v: Float, stops: Float): Float = (v.coerceAtLeast(0f).pow(2.4f) * 2f.pow(stops)).pow(1f / 2.4f)
}

/**
 * LUTs the user has loaded (.cube files), kept in the app's own storage so projects keep working if the
 * original file is moved. Each is known by its file name without ".cube".
 */
object LutLibrary {
    private fun dir(context: Context) = File(context.filesDir, "luts").apply { mkdirs() }

    fun names(context: Context): List<String> =
        dir(context).listFiles().orEmpty().filter { it.extension.equals("cube", true) }
            .map { it.nameWithoutExtension }.sortedBy { it.lowercase() }

    fun load(context: Context, name: String): CubeLut = CubeLut.parse(File(dir(context), "$name.cube").readText())

    /** Copies a .cube file into the library (checking that it reads), returning its name and contents. */
    fun import(context: Context, uri: Uri): Pair<String, CubeLut> {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Couldn't open the file")
        val lut = CubeLut.parse(text)
        val name = ProjectStore.safeName(AudioDecoder.displayName(context, uri).removeSuffix(".cube").removeSuffix(".CUBE"))
        File(dir(context), "$name.cube").writeText(text)
        return name to lut
    }

    fun delete(context: Context, name: String) { File(dir(context), "$name.cube").delete() }
}

/** The grade the preview is showing now; written on the main thread, read on the video thread for each frame. */
class GradeState(val lut: CubeLut?, val exposure: Float, val highlights: Float, val shadows: Float) {
    companion object { val NONE = GradeState(null, 0f, 0f, 0f) }
}

class LiveGrade {
    @Volatile var state: GradeState = GradeState.NONE
}

/** The preview's colour effect: one GPU pass that reads [live] for every frame, so changes show straight away. */
@UnstableApi
class GradeEffect(private val live: LiveGrade) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = GradeShaderProgram(live, useHdr)
}

@UnstableApi
private class GradeShaderProgram(private val live: LiveGrade, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var lutTexture = 0
    private var uploadedLut: CubeLut? = null
    private var gpuLutSize = 0

    init {
        try {
            program = GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute(
            "aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)

    private fun uploadLut(lut: CubeLut) {
        val small = if (lut.size > CubeLut.MAX_GPU_SIZE) lut.resized(CubeLut.MAX_GPU_SIZE) else lut
        if (lutTexture == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            lutTexture = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, small.size * small.size, small.size, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, small.toTextureBytes(),
        )
        gpuLutSize = small.size
        uploadedLut = lut
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val s = live.state
        try {
            val lut = s.lut
            if (lut != null && lut !== uploadedLut) uploadLut(lut)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            // The LUT slot always needs a texture; with no LUT it's given the frame itself, which the shader then ignores.
            program.setSamplerTexIdUniform("uLut", if (lut != null) lutTexture else inputTexId, 1)
            program.setFloatUniform("uLutSize", if (lut != null) gpuLutSize.toFloat() else 0f)
            val dMin = lut?.domainMin ?: floatArrayOf(0f, 0f, 0f)
            val dMax = lut?.domainMax ?: floatArrayOf(1f, 1f, 1f)
            program.setFloatsUniform("uDomainMin", dMin)
            program.setFloatsUniform("uDomainScale", FloatArray(3) { 1f / (dMax[it] - dMin[it]) })
            program.setFloatUniform("uExposure", 2f.pow(s.exposure))
            program.setFloatUniform("uHighlights", s.highlights)
            program.setFloatUniform("uShadows", s.shadows)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
            if (lutTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

        // Same steps as GradeMath, on the GPU. The LUT is stored as side-by-side slices, one per blue level:
        // red and green are blended by the texture hardware, blue between two slices here.
        val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uLut;
uniform float uLutSize;
uniform vec3 uDomainMin;
uniform vec3 uDomainScale;
uniform float uExposure;
uniform float uHighlights;
uniform float uShadows;
varying vec2 vTexSamplingCoord;

vec3 applyLut(vec3 c) {
  float n = uLutSize;
  c = clamp((c - uDomainMin) * uDomainScale, 0.0, 1.0);
  float z = c.b * (n - 1.0);
  float z0 = min(floor(z), n - 2.0);
  float f = z - z0;
  float x = c.r * (n - 1.0) + 0.5;
  float y = (c.g * (n - 1.0) + 0.5) / n;
  vec3 a = texture2D(uLut, vec2((z0 * n + x) / (n * n), y)).rgb;
  vec3 b = texture2D(uLut, vec2(((z0 + 1.0) * n + x) / (n * n), y)).rgb;
  return mix(a, b, f);
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = src.rgb;
  if (uLutSize > 1.5) c = applyLut(c);
  if (uExposure != 1.0) c = pow(pow(max(c, 0.0), vec3(2.4)) * uExposure, vec3(1.0 / 2.4));
  if (uHighlights != 0.0 || uShadows != 0.0) {
    float y = clamp(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
    float inv = 1.0 - y;
    float ny = y + ${GradeMath.TONE_STRENGTH} * (uShadows * y * inv * inv + uHighlights * y * y * inv);
    c *= ny / max(y, 0.0001);
  }
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""
    }
}
