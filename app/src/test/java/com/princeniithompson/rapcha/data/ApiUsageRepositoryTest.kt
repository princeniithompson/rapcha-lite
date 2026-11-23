package com.princeniithompson.rapcha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ApiUsageRepositoryTest {

    @Before
    fun setUp() {
        ApiUsageRepository.resetForAccountChange(nowMs = pstTimestamp(2026, 11, 22, 12, 0))
    }

    private fun pstTimestamp(year: Int, month1Based: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        cal.set(year, month1Based - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun parseAiStudioRateLimitText_extractsExactFreeTierLimitsAndIgnoresStrayPaidWords() {
        val sampleDashboard = """
            Upgrade to Paid
            Gemini API Rate Limit
            Free tier
            Rapcha 30frst
            1 Day
            Rate limits by model
            Status
            Model
            Category
            RPM
            TPM
            RPD
            Gemini 3.5 Flash Lite
            Text-out models
            0 / 15
            0 / 250K
            69 / 500
            Gemini 3.5 Transcribe
            Live API
            0 / 3
            0 / 100K
            8 / 25
            Gemini 3.5 Transcribe Live
            Live API
            0 / 3
            0 / 100K
            0 / Unlimited
            Tools
            Gemini 3.5 Flash Lite
            Map grounding
            0 / 0
            0 / 0
            0 / 0
            Gemini 3.5 Transcribe
            Map grounding
            0 / 0
            0 / 0
            0 / 0
        """.trimIndent()

        val parsed = ApiUsageRepository.parseAiStudioRateLimitText(sampleDashboard)
        assertNotNull(parsed)
        assertEquals("Free tier", parsed!!.tierLabel)
        assertEquals(69, parsed.modulesUsed)
        assertEquals(500, parsed.modulesLimit)
        assertEquals(8, parsed.offlineUsed)
        assertEquals(25, parsed.offlineLimit)

        ApiUsageRepository.applySyncedRateLimits(parsed, nowMs = pstTimestamp(2026, 11, 22, 12, 0))
        assertFalse(ApiUsageRepository.usage.value.isConfirmedPaid)
    }

    @Test
    fun parseAiStudioRateLimitText_extractsPaidTier1WithCompactKNotation() {
        val paidDashboard = """
            Gemini API Rate Limit
            Tier 1
            Gemini 3.5 Flash Lite
            Text-out models
            2 / 4K
            10K / 4M
            150 / 10K
            Gemini 3.5 Transcribe
            Live API
            1 / 100
            5K / 2M
            10 / Unlimited
        """.trimIndent()

        val parsed = ApiUsageRepository.parseAiStudioRateLimitText(paidDashboard)
        assertNotNull(parsed)
        assertEquals("Tier 1", parsed!!.tierLabel)
        assertEquals(150, parsed.modulesUsed)
        assertEquals(10_000, parsed.modulesLimit)
        assertEquals(10, parsed.offlineUsed)
        assertEquals(ApiUsageRepository.UNLIMITED_LIMIT, parsed.offlineLimit)
    }

    @Test
    fun formatLocalResetTime_convertsMidnightPacificToGhanaLocalTime() {
        // November 22, 2026 is PST (UTC-8), so Midnight Pacific is 8:00 AM GMT (Africa/Accra).
        val novNoonPst = pstTimestamp(2026, 11, 22, 12, 0)
        val ghanaTimePst = ApiUsageRepository.formatLocalResetTime(
            nowMs = novNoonPst,
            localTimeZone = TimeZone.getTimeZone("Africa/Accra"),
            locale = Locale.US
        )
        assertEquals("8:00 AM", ghanaTimePst)

        // July 15, 2026 is PDT (UTC-7), so Midnight Pacific is 7:00 AM GMT (Africa/Accra).
        val julNoonPdt = pstTimestamp(2026, 7, 15, 12, 0)
        val ghanaTimePdt = ApiUsageRepository.formatLocalResetTime(
            nowMs = julNoonPdt,
            localTimeZone = TimeZone.getTimeZone("Africa/Accra"),
            locale = Locale.US
        )
        assertEquals("7:00 AM", ghanaTimePdt)
    }

    @Test
    fun recordCalls_http429_paidOverflow_andPacificDayRollover() {
        val day1 = pstTimestamp(2026, 11, 22, 23, 30)
        ApiUsageRepository.resetForAccountChange(nowMs = day1)

        // Record 2 offline push calls and 1 modules call
        ApiUsageRepository.recordOfflinePushCall(200, nowMs = day1)
        ApiUsageRepository.recordOfflinePushCall(200, nowMs = day1)
        ApiUsageRepository.recordModulesCall(200, nowMs = day1)

        var snap = ApiUsageRepository.usage.value
        assertEquals(2, snap.offlineUsed)
        assertEquals(23, snap.offlineRemaining)
        assertEquals(1, snap.modulesUsed)
        assertEquals(499, snap.modulesRemaining)
        assertFalse(snap.offlineRateLimited)

        // HTTP 429 immediately marks quota exhausted (red bar)
        ApiUsageRepository.recordOfflinePushCall(429, nowMs = day1)
        snap = ApiUsageRepository.usage.value
        assertTrue(snap.offlineRateLimited)
        assertEquals(0, snap.offlineRemaining)
        assertEquals(1f, snap.offlineProgress, 0.001f)

        // Roll over past Midnight Pacific Time -> counts & 429 flags reset cleanly
        val day2 = pstTimestamp(2026, 11, 23, 0, 5)
        ApiUsageRepository.refreshIfDayRolledOver(nowMs = day2)
        snap = ApiUsageRepository.usage.value
        assertEquals("2026-11-23", snap.pacificDayKey)
        assertEquals(0, snap.offlineUsed)
        assertEquals(25, snap.offlineRemaining)
        assertEquals(0, snap.modulesUsed)
        assertFalse(snap.offlineRateLimited)

        // Paid-tier overflow self-correction: 26th successful offline call on default 25 limit upgrades tier label
        repeat(26) {
            ApiUsageRepository.recordOfflinePushCall(200, nowMs = day2)
        }
        snap = ApiUsageRepository.usage.value
        assertEquals(26, snap.offlineUsed)
        assertTrue(snap.offlinePaidOverflow)
        assertFalse(snap.offlineRateLimited)
        assertEquals("Paid / higher tier", snap.tierLabel)
    }
}
