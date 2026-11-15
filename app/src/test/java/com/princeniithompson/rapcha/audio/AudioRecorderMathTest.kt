package com.princeniithompson.rapcha.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecorderMathTest {

    @Test
    fun `the capture buffer holds about one second even when the minimum is tiny`() {
        assertEquals(AudioRecorder.TARGET_BUFFER_BYTES, AudioRecorder.bufferSizeFor(640))
        assertEquals(32000, AudioRecorder.bufferSizeFor(1280))
        assertEquals(4 * 12000, AudioRecorder.bufferSizeFor(12000))
        assertTrue(AudioRecorder.bufferSizeFor(1) >= AudioRecorder.CHUNK_SIZE_BYTES * 2)
    }

    @Test
    fun `the last partial packet is flushed on Complete`() {
        val buf = ByteArray(AudioRecorder.CHUNK_SIZE_BYTES) { 1 }
        assertNull(AudioRecorder.tailPacket(buf, 0))
        assertNull(AudioRecorder.tailPacket(buf, 100))
        assertEquals(1280, AudioRecorder.tailPacket(buf, 1280)!!.size)
        // Odd byte counts are trimmed to whole 16-bit samples.
        assertEquals(1280, AudioRecorder.tailPacket(buf, 1281)!!.size)
    }
}
