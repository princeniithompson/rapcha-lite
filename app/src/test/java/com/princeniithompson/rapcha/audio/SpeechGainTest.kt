package com.princeniithompson.rapcha.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpeechGainTest {

    /** 100 ms of a 220 Hz tone at the given peak amplitude, PCM16 LE. */
    private fun tone(peak: Int, samples: Int = 1600): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (peak * sin(2 * PI * 220 * i / 16000.0)).toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun silence(level: Int = 40): ByteArray = tone(level)

    @Test
    fun `loud speech passes through untouched`() {
        val gain = SpeechGain()
        gain.process(silence())
        val loud = tone(9000)
        val out = gain.process(loud)
        assertSame(loud, out)
        assertEquals(1.0, gain.currentGain, 0.0001)
    }

    @Test
    fun `a soft word is lifted toward the target without clipping`() {
        val gain = SpeechGain()
        repeat(5) { gain.process(silence()) }
        val soft = tone(560) // RMS ~400, a quiet word on MIC
        val before = SpeechGain.rms(soft)
        val out = gain.process(soft)
        val second = gain.process(soft)
        val after = SpeechGain.rms(second)
        assertTrue("gain should rise above 4x, was ${gain.currentGain}", gain.currentGain > 4.0)
        assertTrue(gain.currentGain <= SpeechGain.MAX_GAIN)
        assertTrue("rms $before -> $after", after > before * 4)
        assertTrue(SpeechGain.peak(out) <= 32767)
        assertTrue(SpeechGain.peak(second) <= SpeechGain.PEAK_CEILING.toInt() + 1)
    }

    @Test
    fun `the input buffer is never modified`() {
        val gain = SpeechGain()
        gain.process(silence())
        val soft = tone(500)
        val copy = soft.copyOf()
        gain.process(soft)
        assertArrayEquals(copy, soft)
    }

    @Test
    fun `steady background noise alone does not pump the gain`() {
        val gain = SpeechGain()
        repeat(50) { gain.process(silence(120)) }
        assertEquals(1.0, gain.currentGain, 0.0001)
    }

    @Test
    fun `a loud burst after quiet speech never wraps or clips`() {
        val gain = SpeechGain()
        repeat(3) { gain.process(silence()) }
        repeat(3) { gain.process(tone(500)) }
        assertTrue(gain.currentGain > 3.0)
        val shout = tone(30000)
        val out = gain.process(shout)
        // Same sign as the input everywhere (no int16 wrap-around) and never past full scale.
        var i = 0
        while (i < shout.size) {
            val a = SpeechGain.sample(shout, i)
            val b = SpeechGain.sample(out, i)
            if (a != 0) assertTrue("sample $i wrapped: $a -> $b", (a > 0) == (b >= 0))
            assertTrue(abs(b) <= 32767)
            i += 2
        }
        assertTrue(gain.currentGain < 1.5)
    }

    @Test
    fun `replayed audio is boosted but does not retrain the gain`() {
        val gain = SpeechGain()
        repeat(3) { gain.process(silence()) }
        gain.process(tone(500))
        val learned = gain.currentGain
        gain.process(tone(9000), adapt = false)
        assertEquals(learned, gain.currentGain, 0.0001)
    }

    @Test
    fun `soft limiter is monotonic and bounded`() {
        var last = 0.0
        var x = 0.0
        while (x < 200000.0) {
            val y = SpeechGain.softLimit(x)
            assertTrue(y >= last)
            assertTrue(y <= 32767.0)
            last = y
            x += 500.0
        }
        assertEquals(-SpeechGain.softLimit(40000.0), SpeechGain.softLimit(-40000.0), 0.0001)
    }
}
