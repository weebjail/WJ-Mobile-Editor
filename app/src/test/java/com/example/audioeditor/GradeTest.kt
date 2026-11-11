package com.example.audioeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class GradeTest {

    /** A 2-point LUT that inverts colours, in .cube format. */
    private val invert = """
        # comment
        TITLE "Invert"
        LUT_3D_SIZE 2
        1 1 1
        0 1 1
        1 0 1
        0 0 1
        1 1 0
        0 1 0
        1 0 0
        0 0 0
    """.trimIndent()

    @Test
    fun parsesAndSamplesCube() {
        val lut = CubeLut.parse(invert)
        assertEquals(2, lut.size)
        val v = lut.sample(0.25f, 0.5f, 1f)
        assertEquals(0.75f, v[0], 1e-6f)
        assertEquals(0.5f, v[1], 1e-6f)
        assertEquals(0f, v[2], 1e-6f)
    }

    @Test
    fun resizingKeepsTheMapping() {
        val big = CubeLut.parse(invert).resized(5)
        assertEquals(5, big.size)
        val v = big.sample(0.3f, 0.6f, 0.1f)
        assertEquals(0.7f, v[0], 1e-5f)
        assertEquals(0.4f, v[1], 1e-5f)
        assertEquals(0.9f, v[2], 1e-5f)
    }

    @Test
    fun textureLayoutPutsBlueSlicesSideBySide() {
        val bytes = CubeLut.parse(invert).toTextureBytes()
        // Row g=0: (r0,b0) (r1,b0) (r0,b1) (r1,b1). Entry r=1,g=0,b=1 inverts to (0, 1, 0).
        val i = (0 * 4 + 1 * 2 + 1) * 4
        assertEquals(0, bytes.get(i).toInt() and 0xFF)
        assertEquals(255, bytes.get(i + 1).toInt() and 0xFF)
        assertEquals(0, bytes.get(i + 2).toInt() and 0xFF)
    }

    @Test
    fun domainIsApplied() {
        val lut = CubeLut.parse("DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" + invert)
        assertEquals(0.5f, lut.sample(1f, 1f, 1f)[0], 1e-6f)
    }

    @Test
    fun rejectsBrokenFiles() {
        for (bad in listOf("LUT_1D_SIZE 4\n0 0 0", "LUT_3D_SIZE 2\n0 0 0", "0 0 0", "LUT_3D_SIZE 2\n0 0 x")) {
            try {
                CubeLut.parse(bad); fail("accepted: $bad")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun toneCurveKeepsEndsAndStaysSmooth() {
        for (h in listOf(-1f, 0f, 1f)) for (s in listOf(-1f, 0f, 1f)) {
            assertEquals(0f, GradeMath.tone(0f, h, s), 1e-6f)
            assertEquals(1f, GradeMath.tone(1f, h, s), 1e-6f)
            var last = -1f
            for (i in 0..100) {
                val y = GradeMath.tone(i / 100f, h, s)
                assertTrue("never turns back (h=$h s=$s)", y >= last)
                last = y
            }
        }
        assertTrue(GradeMath.tone(0.3f, 0f, 1f) > 0.3f)     // shadows up lifts the darks
        assertTrue(GradeMath.tone(0.7f, -1f, 0f) < 0.7f)    // highlights down pulls in the brights
        assertEquals(0.5f, GradeMath.expose(0.5f, 0f), 1e-6f)
        assertTrue(GradeMath.expose(0.5f, 1f) > 0.6f)
    }
}
