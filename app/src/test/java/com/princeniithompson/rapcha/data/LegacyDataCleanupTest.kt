package com.princeniithompson.rapcha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyDataCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `retry WAVs, recordings and diagnostics files are deleted and other files are kept`() {
        val dir = tmp.newFolder("files")
        File(dir, "retry").mkdirs()
        File(dir, "retry/clip-1.wav").writeBytes(ByteArray(64))
        File(dir, "recordings").mkdirs()
        File(dir, "recordings/a.wav").writeBytes(ByteArray(64))
        File(dir, "diagnostics_history.jsonl").writeText("{}")
        File(dir, "crash_pending.txt").writeText("x")
        File(dir, "keep_me.txt").writeText("keep")

        assertEquals(4, LegacyDataCleanup.deleteLeftovers(dir))
        assertFalse(File(dir, "retry").exists())
        assertFalse(File(dir, "recordings").exists())
        assertFalse(File(dir, "diagnostics_history.jsonl").exists())
        assertFalse(File(dir, "crash_pending.txt").exists())
        assertTrue(File(dir, "keep_me.txt").exists())
        assertEquals(0, LegacyDataCleanup.deleteLeftovers(dir))
        assertEquals(0, LegacyDataCleanup.deleteLeftovers(null))
    }
}
