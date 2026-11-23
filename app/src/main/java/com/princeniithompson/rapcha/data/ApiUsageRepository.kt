package com.princeniithompson.rapcha.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Parsed limits scraped from the user's Google AI Studio Rate Limits page.
 * A limit of [UNLIMITED_LIMIT] (-1) indicates the dashboard reports `Unlimited`.
 */
data class SyncedRateLimitData(
    val tierLabel: String,
    val modulesUsed: Int?,
    val modulesLimit: Int?,
    val offlineUsed: Int?,
    val offlineLimit: Int?
)

/**
 * Live snapshot of the user's daily Gemini API usage across Rapcha's two metered models:
 * - Modules (`gemini-3.5-flash-lite`): default 500 RPD on Free tier
 * - Offline / network-fallback push (`gemini-3.5-transcribe`): default 25 RPD on Free tier
 *
 * Live voice dictation (`gemini-3.5-transcribe-live`) is unlimited daily and intentionally
 * excluded from quota bars.
 */
data class ApiUsageSnapshot(
    val modulesUsed: Int = 0,
    val modulesLimit: Int = ApiUsageRepository.DEFAULT_FREE_MODULES_RPD,
    val modulesRateLimited: Boolean = false,
    val modulesPaidOverflow: Boolean = false,
    val offlineUsed: Int = 0,
    val offlineLimit: Int = ApiUsageRepository.DEFAULT_FREE_OFFLINE_RPD,
    val offlineRateLimited: Boolean = false,
    val offlinePaidOverflow: Boolean = false,
    val tierLabel: String = "Free tier",
    val pacificDayKey: String = "",
    val lastSyncedAtMs: Long = 0L
) {
    val modulesUnlimited: Boolean get() = modulesLimit == ApiUsageRepository.UNLIMITED_LIMIT
    val offlineUnlimited: Boolean get() = offlineLimit == ApiUsageRepository.UNLIMITED_LIMIT

    /**
     * True only when the user is genuinely on a paid / higher-limit tier:
     * either they exceeded Free-tier counts and requests kept succeeding, or Google AI Studio
     * explicitly reported higher-than-Free RPD limits.
     */
    val isConfirmedPaid: Boolean
        get() = modulesPaidOverflow || offlinePaidOverflow ||
            modulesUnlimited || offlineUnlimited ||
            modulesLimit > ApiUsageRepository.DEFAULT_FREE_MODULES_RPD ||
            offlineLimit > ApiUsageRepository.DEFAULT_FREE_OFFLINE_RPD

    val modulesRemaining: Int
        get() = if (modulesRateLimited) 0
        else if (modulesUnlimited) Int.MAX_VALUE
        else (modulesLimit - modulesUsed).coerceAtLeast(0)

    val offlineRemaining: Int
        get() = if (offlineRateLimited) 0
        else if (offlineUnlimited) Int.MAX_VALUE
        else (offlineLimit - offlineUsed).coerceAtLeast(0)

    val modulesProgress: Float
        get() = when {
            modulesRateLimited -> 1f
            modulesUnlimited || modulesPaidOverflow || modulesLimit <= 0 -> 0f
            else -> (modulesUsed.toFloat() / modulesLimit.toFloat()).coerceIn(0f, 1f)
        }

    val offlineProgress: Float
        get() = when {
            offlineRateLimited -> 1f
            offlineUnlimited || offlinePaidOverflow || offlineLimit <= 0 -> 0f
            else -> (offlineUsed.toFloat() / offlineLimit.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * Tracks daily API call counts for Rapcha's metered Gemini endpoints and automatically resets
 * at Midnight Pacific Time (`America/Los_Angeles`) — the exact Gemini API RPD reset boundary —
 * while rendering reset times in each user's own local timezone (e.g., 8:00 AM in Ghana).
 */
object ApiUsageRepository {
    const val DEFAULT_FREE_MODULES_RPD = 500
    const val DEFAULT_FREE_OFFLINE_RPD = 25
    const val UNLIMITED_LIMIT = -1

    private const val PREFS_NAME = "rapcha_api_usage_prefs"
    private const val KEY_DAY = "pacific_day_key"
    private const val KEY_MODULES_USED = "modules_used"
    private const val KEY_MODULES_LIMIT = "modules_limit"
    private const val KEY_MODULES_RATE_LIMITED = "modules_rate_limited"
    private const val KEY_MODULES_PAID_OVERFLOW = "modules_paid_overflow"
    private const val KEY_OFFLINE_USED = "offline_used"
    private const val KEY_OFFLINE_LIMIT = "offline_limit"
    private const val KEY_OFFLINE_RATE_LIMITED = "offline_rate_limited"
    private const val KEY_OFFLINE_PAID_OVERFLOW = "offline_paid_overflow"
    private const val KEY_TIER_LABEL = "tier_label"
    private const val KEY_LAST_SYNCED_MS = "last_synced_ms"

    private val lock = Any()
    private var prefs: SharedPreferences? = null

    private val _usage = MutableStateFlow(ApiUsageSnapshot())
    val usage: StateFlow<ApiUsageSnapshot> = _usage.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            loadOrRollOverLocked(System.currentTimeMillis())
        }
    }

    fun refreshIfDayRolledOver(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            loadOrRollOverLocked(nowMs)
        }
    }

    /** Records a call to `gemini-3.5-flash-lite` (Modules / Telescope actions / smart vocab). */
    fun recordModulesCall(httpCode: Int, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val current = ensureCurrentDayLocked(nowMs)
            val updated = when {
                httpCode in 200..299 -> {
                    val nextUsed = current.modulesUsed + 1
                    val overflow = current.modulesPaidOverflow ||
                        (!current.modulesUnlimited && current.modulesLimit > 0 && nextUsed > current.modulesLimit)
                    current.copy(
                        modulesUsed = nextUsed,
                        modulesRateLimited = false,
                        modulesPaidOverflow = overflow,
                        tierLabel = if (overflow && current.tierLabel.equals("Free tier", ignoreCase = true)) {
                            "Paid / higher tier"
                        } else {
                            current.tierLabel
                        }
                    )
                }
                httpCode == 429 -> current.copy(modulesRateLimited = true)
                else -> current
            }
            persistAndEmitLocked(updated)
        }
    }

    /** Records a call to `gemini-3.5-transcribe` (offline / network-fallback audio push). */
    fun recordOfflinePushCall(httpCode: Int, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val current = ensureCurrentDayLocked(nowMs)
            val updated = when {
                httpCode in 200..299 -> {
                    val nextUsed = current.offlineUsed + 1
                    val overflow = current.offlinePaidOverflow ||
                        (!current.offlineUnlimited && current.offlineLimit > 0 && nextUsed > current.offlineLimit)
                    current.copy(
                        offlineUsed = nextUsed,
                        offlineRateLimited = false,
                        offlinePaidOverflow = overflow,
                        tierLabel = if (overflow && current.tierLabel.equals("Free tier", ignoreCase = true)) {
                            "Paid / higher tier"
                        } else {
                            current.tierLabel
                        }
                    )
                }
                httpCode == 429 -> current.copy(offlineRateLimited = true)
                else -> current
            }
            persistAndEmitLocked(updated)
        }
    }

    /** Applies freshly synced tier & per-model RPD limits scraped from Google AI Studio. */
    fun applySyncedRateLimits(data: SyncedRateLimitData, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val current = ensureCurrentDayLocked(nowMs)
            val newModulesLimit = data.modulesLimit ?: current.modulesLimit
            val newOfflineLimit = data.offlineLimit ?: current.offlineLimit
            val newModulesUsed = data.modulesUsed?.let { maxOf(it, current.modulesUsed) } ?: current.modulesUsed
            val newOfflineUsed = data.offlineUsed?.let { maxOf(it, current.offlineUsed) } ?: current.offlineUsed

            val updated = current.copy(
                tierLabel = data.tierLabel.ifBlank { current.tierLabel },
                modulesLimit = newModulesLimit,
                modulesUsed = newModulesUsed,
                modulesRateLimited = if (newModulesLimit != UNLIMITED_LIMIT && newModulesLimit > 0 && newModulesUsed >= newModulesLimit) {
                    true
                } else {
                    false
                },
                modulesPaidOverflow = newModulesLimit != UNLIMITED_LIMIT && newModulesLimit > 0 && newModulesUsed > newModulesLimit,
                offlineLimit = newOfflineLimit,
                offlineUsed = newOfflineUsed,
                offlineRateLimited = if (newOfflineLimit != UNLIMITED_LIMIT && newOfflineLimit > 0 && newOfflineUsed >= newOfflineLimit) {
                    true
                } else {
                    false
                },
                offlinePaidOverflow = newOfflineLimit != UNLIMITED_LIMIT && newOfflineLimit > 0 && newOfflineUsed > newOfflineLimit,
                lastSyncedAtMs = nowMs
            )
            persistAndEmitLocked(updated)
        }
    }

    /** Resets stored tier/limits back to Free-tier defaults when the user clears their API key. */
    fun resetForAccountChange(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val fresh = ApiUsageSnapshot(
                pacificDayKey = currentPacificDayKey(nowMs)
            )
            persistAndEmitLocked(fresh)
        }
    }

    private fun loadOrRollOverLocked(nowMs: Long) {
        val sp = prefs
        val todayKey = currentPacificDayKey(nowMs)
        if (sp == null) {
            if (_usage.value.pacificDayKey != todayKey) {
                _usage.value = _usage.value.copy(
                    modulesUsed = 0,
                    modulesRateLimited = false,
                    offlineUsed = 0,
                    offlineRateLimited = false,
                    pacificDayKey = todayKey
                )
            }
            return
        }

        val savedDay = sp.getString(KEY_DAY, "").orEmpty()
        val modulesLimit = sp.getInt(KEY_MODULES_LIMIT, DEFAULT_FREE_MODULES_RPD)
        val offlineLimit = sp.getInt(KEY_OFFLINE_LIMIT, DEFAULT_FREE_OFFLINE_RPD)
        val modulesPaidOverflow = if (savedDay == todayKey) sp.getBoolean(KEY_MODULES_PAID_OVERFLOW, false) else false
        val offlinePaidOverflow = if (savedDay == todayKey) sp.getBoolean(KEY_OFFLINE_PAID_OVERFLOW, false) else false
        val rawTierLabel = sp.getString(KEY_TIER_LABEL, "Free tier").orEmpty().ifBlank { "Free tier" }
        val confirmedPaid = modulesPaidOverflow || offlinePaidOverflow ||
            modulesLimit == UNLIMITED_LIMIT || offlineLimit == UNLIMITED_LIMIT ||
            modulesLimit > DEFAULT_FREE_MODULES_RPD || offlineLimit > DEFAULT_FREE_OFFLINE_RPD
        val tierLabel = if (!confirmedPaid) "Free tier" else rawTierLabel
        val lastSynced = sp.getLong(KEY_LAST_SYNCED_MS, 0L)

        val snapshot = if (savedDay == todayKey) {
            ApiUsageSnapshot(
                modulesUsed = sp.getInt(KEY_MODULES_USED, 0),
                modulesLimit = modulesLimit,
                modulesRateLimited = sp.getBoolean(KEY_MODULES_RATE_LIMITED, false),
                modulesPaidOverflow = modulesPaidOverflow,
                offlineUsed = sp.getInt(KEY_OFFLINE_USED, 0),
                offlineLimit = offlineLimit,
                offlineRateLimited = sp.getBoolean(KEY_OFFLINE_RATE_LIMITED, false),
                offlinePaidOverflow = offlinePaidOverflow,
                tierLabel = tierLabel,
                pacificDayKey = todayKey,
                lastSyncedAtMs = lastSynced
            )
        } else {
            ApiUsageSnapshot(
                modulesUsed = 0,
                modulesLimit = modulesLimit,
                modulesRateLimited = false,
                modulesPaidOverflow = false,
                offlineUsed = 0,
                offlineLimit = offlineLimit,
                offlineRateLimited = false,
                offlinePaidOverflow = false,
                tierLabel = tierLabel,
                pacificDayKey = todayKey,
                lastSyncedAtMs = lastSynced
            )
        }
        persistAndEmitLocked(snapshot)
    }

    private fun ensureCurrentDayLocked(nowMs: Long): ApiUsageSnapshot {
        val todayKey = currentPacificDayKey(nowMs)
        val current = _usage.value
        return if (current.pacificDayKey == todayKey) {
            current
        } else {
            current.copy(
                modulesUsed = 0,
                modulesRateLimited = false,
                modulesPaidOverflow = false,
                offlineUsed = 0,
                offlineRateLimited = false,
                offlinePaidOverflow = false,
                pacificDayKey = todayKey
            )
        }
    }

    private fun persistAndEmitLocked(snapshot: ApiUsageSnapshot) {
        _usage.value = snapshot
        prefs?.edit()
            ?.putString(KEY_DAY, snapshot.pacificDayKey)
            ?.putInt(KEY_MODULES_USED, snapshot.modulesUsed)
            ?.putInt(KEY_MODULES_LIMIT, snapshot.modulesLimit)
            ?.putBoolean(KEY_MODULES_RATE_LIMITED, snapshot.modulesRateLimited)
            ?.putBoolean(KEY_MODULES_PAID_OVERFLOW, snapshot.modulesPaidOverflow)
            ?.putInt(KEY_OFFLINE_USED, snapshot.offlineUsed)
            ?.putInt(KEY_OFFLINE_LIMIT, snapshot.offlineLimit)
            ?.putBoolean(KEY_OFFLINE_RATE_LIMITED, snapshot.offlineRateLimited)
            ?.putBoolean(KEY_OFFLINE_PAID_OVERFLOW, snapshot.offlinePaidOverflow)
            ?.putString(KEY_TIER_LABEL, snapshot.tierLabel)
            ?.putLong(KEY_LAST_SYNCED_MS, snapshot.lastSyncedAtMs)
            ?.apply()
    }

    /** Returns `YYYY-MM-DD` in `America/Los_Angeles` (Gemini API's RPD reset timezone). */
    fun currentPacificDayKey(nowMs: Long = System.currentTimeMillis()): String {
        val pacificTz = TimeZone.getTimeZone("America/Los_Angeles")
        val cal = Calendar.getInstance(pacificTz).apply { timeInMillis = nowMs }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    /** Computes the exact epoch millis of the upcoming Midnight Pacific Time (`00:00 America/Los_Angeles`). */
    fun nextResetEpochMillis(nowMs: Long = System.currentTimeMillis()): Long {
        val pacificTz = TimeZone.getTimeZone("America/Los_Angeles")
        val cal = Calendar.getInstance(pacificTz).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    /** Formats the upcoming Gemini daily reset in the user's own local timezone (e.g. `8:00 AM`). */
    fun formatLocalResetTime(
        nowMs: Long = System.currentTimeMillis(),
        localTimeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val resetMs = nextResetEpochMillis(nowMs)
        val fmt = SimpleDateFormat("h:mm a", locale).apply { timeZone = localTimeZone }
        return fmt.format(Date(resetMs))
    }

    /** Formats remaining time until the next Gemini daily reset (e.g. `5h 18m` or `42m`). */
    fun formatResetCountdown(nowMs: Long = System.currentTimeMillis()): String {
        val remainingMs = (nextResetEpochMillis(nowMs) - nowMs).coerceAtLeast(0L)
        val totalMinutes = (remainingMs / 60_000L).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes.coerceAtLeast(1)}m"
        }
    }

    /**
     * Parses raw copied/scraped text from Google AI Studio's Rate Limits page (`aistudio.google.com/rate-limit`),
     * stripping out all unrelated models and extracting ONLY:
     * - Tier (`Free tier`, `Tier 1`, `Tier 2`, `Tier 3`, `Paid`)
     * - `Gemini 3.5 Flash Lite` RPD (under Text-out models, ignoring Map grounding)
     * - `Gemini 3.5 Transcribe` RPD (under Live API, ignoring Live & Map grounding rows)
     */
    fun parseAiStudioRateLimitText(rawText: String): SyncedRateLimitData? {
        if (rawText.isBlank()) return null

        // Stop before the "Tools" / "Map grounding" section so 0 / 0 tool rows never override model quotas.
        val modelsSection = rawText
            .substringBefore("\nTools\n")
            .substringBefore("\r\nTools\r\n")

        val lines = modelsSection.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val flashLiteRpd = extractModelRpd(
            lines = lines,
            exactModelTitle = "Gemini 3.5 Flash Lite",
            disallowedCategory = "Map grounding"
        )
        val batchTranscribeRpd = extractModelRpd(
            lines = lines,
            exactModelTitle = "Gemini 3.5 Transcribe",
            disallowedCategory = "Map grounding"
        )

        // Never match a stray "Paid" word (AI Studio pages include upgrade/filter labels saying "Paid").
        // Only trust explicit Paid tiers when limits actually exceed Free-tier quotas (500 / 25).
        val explicitPaidMatch = Regex(
            pattern = "\\b(Tier\\s*[1-9]|Pay-as-you-go)\\b",
            option = RegexOption.IGNORE_CASE
        ).find(modelsSection)
        val explicitFreeMatch = Regex(
            pattern = "\\bFree\\s+tier\\b",
            option = RegexOption.IGNORE_CASE
        ).find(modelsSection)

        val limitsIndicatePaid = (flashLiteRpd != null && (flashLiteRpd.second == UNLIMITED_LIMIT || flashLiteRpd.second > DEFAULT_FREE_MODULES_RPD)) ||
            (batchTranscribeRpd != null && (batchTranscribeRpd.second == UNLIMITED_LIMIT || batchTranscribeRpd.second > DEFAULT_FREE_OFFLINE_RPD))

        val tierLabel = when {
            limitsIndicatePaid && explicitPaidMatch != null ->
                explicitPaidMatch.value.trim().replaceFirstChar { it.uppercase() }
            limitsIndicatePaid -> "Paid"
            explicitFreeMatch != null -> "Free tier"
            else -> "Free tier"
        }

        if (flashLiteRpd == null && batchTranscribeRpd == null && explicitFreeMatch == null && explicitPaidMatch == null) {
            return null
        }

        return SyncedRateLimitData(
            tierLabel = tierLabel,
            modulesUsed = flashLiteRpd?.first,
            modulesLimit = flashLiteRpd?.second,
            offlineUsed = batchTranscribeRpd?.first,
            offlineLimit = batchTranscribeRpd?.second
        )
    }

    private fun extractModelRpd(
        lines: List<String>,
        exactModelTitle: String,
        disallowedCategory: String
    ): Pair<Int, Int>? {
        val ratioRegex = Regex("([0-9]+(?:\\.[0-9]+)?[KMBkmb]?)\\s*/\\s*(Unlimited|[0-9]+(?:\\.[0-9]+)?[KMBkmb]?)")

        for (i in lines.indices) {
            val line = lines[i]
            // Match either a standalone title line or a tab-separated table row starting with exact title
            val isMatch = line.equals(exactModelTitle, ignoreCase = true) ||
                line.startsWith("$exactModelTitle\t", ignoreCase = true)
            if (!isMatch) continue

            // Gather up to 5 lines belonging to this row (Model, Category, RPM, TPM, RPD)
            val windowEnd = minOf(lines.size, i + 6)
            val windowText = lines.subList(i, windowEnd).joinToString(" ")
            if (windowText.contains(disallowedCategory, ignoreCase = true)) continue

            val matches = ratioRegex.findAll(windowText).toList()
            // AI Studio columns are always: [0]=RPM, [1]=TPM, [2]=RPD
            if (matches.size >= 3) {
                val rpdMatch = matches[2]
                val used = parseCompactCount(rpdMatch.groupValues[1]) ?: 0
                val limit = parseCompactCount(rpdMatch.groupValues[2]) ?: continue
                return used to limit
            }
        }
        return null
    }

    internal fun parseCompactCount(token: String): Int? {
        val clean = token.trim().replace(",", "")
        if (clean.equals("Unlimited", ignoreCase = true)) return UNLIMITED_LIMIT
        if (clean.isEmpty() || clean == "-") return null
        val multiplier = when (clean.last().uppercaseChar()) {
            'K' -> 1_000.0
            'M' -> 1_000_000.0
            'B' -> 1_000_000_000.0
            else -> 1.0
        }
        val numericPart = if (multiplier > 1.0) clean.dropLast(1) else clean
        val parsed = numericPart.toDoubleOrNull() ?: return null
        return (parsed * multiplier).toInt()
    }
}
