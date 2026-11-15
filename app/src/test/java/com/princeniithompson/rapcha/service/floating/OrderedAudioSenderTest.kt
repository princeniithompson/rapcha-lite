package com.princeniithompson.rapcha.service.floating

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderedAudioSenderTest {

    private class FakeSink : OrderedAudioSender.Sink {
        override var ready = false
        var accept = true
        val sent = mutableListOf<Int>()
        override fun send(chunk: ByteArray): Boolean {
            if (!accept) return false
            sent += chunk[0].toInt()
            return true
        }
    }

    private fun chunk(id: Int) = ByteArray(3200).also { it[0] = id.toByte() }

    @Test
    fun `audio captured before setup is sent first and in order`() {
        val sender = OrderedAudioSender(150)
        val sink = FakeSink()
        for (i in 1..5) sender.submit(chunk(i), sink)
        assertEquals(emptyList<Int>(), sink.sent)
        sink.ready = true
        // A new chunk arriving before the setup flush must not jump the queue.
        sender.submit(chunk(6), sink)
        sender.flush(sink)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), sink.sent)
        assertEquals(6, sender.chunksSent)
        assertEquals(0, sender.queued)
    }

    @Test
    fun `a refused send keeps the chunk and later chunks wait behind it`() {
        val sender = OrderedAudioSender(150)
        val sink = FakeSink().apply { ready = true }
        sender.submit(chunk(1), sink)
        sink.accept = false
        sender.submit(chunk(2), sink)
        sender.submit(chunk(3), sink)
        sink.accept = true
        sender.submit(chunk(4), sink)
        assertEquals(listOf(1, 2, 3, 4), sink.sent)
    }

    @Test
    fun `a socket that is gone queues up to the cap and drops the oldest`() {
        val sender = OrderedAudioSender(3)
        for (i in 1..5) sender.submit(chunk(i), null)
        assertEquals(3, sender.queued)
        assertEquals(2, sender.dropped)
        val sink = FakeSink().apply { ready = true }
        sender.flush(sink)
        assertEquals(listOf(3, 4, 5), sink.sent)
    }

    @Test
    fun `fifteen seconds are held while connecting`() {
        assertEquals(150, FloatingDictationSessionManager.MAX_AUDIO_QUEUE_CHUNKS)
        assertEquals(true, FloatingDictationSessionManager.COMPLETE_TAIL_MS in 100L..250L)
    }
}
