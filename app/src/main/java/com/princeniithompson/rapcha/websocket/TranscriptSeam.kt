package com.princeniithompson.rapcha.websocket

import java.util.Locale

/**
 * Joins finalized dictation pieces and drops the repeated seam left when the
 * last two seconds of audio are replayed into the next piece.
 */
object TranscriptSeam {
    data class Overlap(val text: String, val wordsRemoved: Int)

    fun append(existing: String, piece: String): String {
        val next = piece.trim()
        if (next.isEmpty()) return existing.trim()
        val previous = existing.trim()
        if (previous.isEmpty()) return next
        return "$previous $next"
    }

    /**
     * Lowercase and strip punctuation, then drop the longest run of at least
     * two words that the start of [next] shares with the end of [previous].
     */
    fun dropOverlap(previous: String, next: String, window: Int = 15): Overlap {
        val nextTokens = tokens(next)
        if (nextTokens.size < 2 || previous.isBlank()) {
            return Overlap(next.trim(), 0)
        }
        val prevNorm = tokens(previous).takeLast(window).map { normalize(it) }
        val nextNorm = nextTokens.take(window).map { normalize(it) }
        val limit = minOf(prevNorm.size, nextNorm.size)
        var best = 0
        for (length in limit downTo 2) {
            if (prevNorm.takeLast(length) == nextNorm.take(length)) {
                best = length
                break
            }
        }
        if (best < 2) return Overlap(next.trim(), 0)
        return Overlap(nextTokens.drop(best).joinToString(" "), best)
    }

    /**
     * Appends [next] onto [existing] while preventing duplicate utterances or raw-vs-polished
     * double batches, and stripping any seam overlap between adjacent pieces.
     */
    fun appendDeduplicated(existing: String, next: String, overlapWindow: Int = 25): String {
        val prev = existing.trim()
        val incoming = next.trim()
        if (incoming.isEmpty()) return prev
        if (prev.isEmpty()) return incoming

        val prevTokens = tokens(prev)
        val nextTokens = tokens(incoming)
        val prevNorm = prevTokens.map { normalize(it) }.filter { it.isNotEmpty() }
        val nextNorm = nextTokens.map { normalize(it) }.filter { it.isNotEmpty() }

        if (nextNorm.isEmpty()) return prev
        if (prevNorm.isEmpty()) return incoming

        // 1. Exact normalized match -> prefer incoming (typically the finalized/SMART-polished version).
        if (prevNorm == nextNorm) return incoming

        // 2. Incoming is a fuller version that already starts with all of previous.
        if (nextNorm.size >= prevNorm.size && nextNorm.take(prevNorm.size) == prevNorm) {
            return incoming
        }

        // 3. Previous already ends with incoming verbatim (normalized).
        if (nextNorm.size >= 2 && prevNorm.size >= nextNorm.size &&
            prevNorm.takeLast(nextNorm.size) == nextNorm
        ) {
            return prev
        }

        // 4. Full-utterance SMART rewrite guard (e.g. raw live preview vs SMART-polished fallback
        //    of the exact same speech where minor words/punctuation differ).
        if (isLikelySameUtteranceRewrite(prevNorm, nextNorm)) {
            return incoming
        }

        // 5. Trailing-utterance rewrite guard for multi-piece sessions where only the last piece
        //    was re-transcribed by fallback.
        if (prevNorm.size > nextNorm.size && nextNorm.size >= 4) {
            val tailLen = minOf(prevNorm.size, (nextNorm.size * 1.35).toInt().coerceAtLeast(nextNorm.size))
            val tailNorm = prevNorm.takeLast(tailLen)
            if (isLikelySameUtteranceRewrite(tailNorm, nextNorm, threshold = 0.78)) {
                val keepCount = (prevTokens.size - nextTokens.size).coerceAtLeast(0)
                val prefix = prevTokens.take(keepCount).joinToString(" ")
                return append(prefix, incoming)
            }
        }

        // 6. Standard seam overlap stripping when joining distinct adjacent pieces.
        val seam = dropOverlap(prev, incoming, window = overlapWindow)
        return append(prev, seam.text)
    }

    internal fun isLikelySameUtteranceRewrite(
        firstNorm: List<String>,
        secondNorm: List<String>,
        threshold: Double = 0.72
    ): Boolean {
        val minSize = minOf(firstNorm.size, secondNorm.size)
        val maxSize = maxOf(firstNorm.size, secondNorm.size)
        if (minSize < 4) return false
        val lengthRatio = minSize.toDouble() / maxSize.toDouble()
        if (lengthRatio < 0.55) return false

        val shared = if (maxSize <= 220) {
            longestCommonSubsequenceLength(firstNorm, secondNorm)
        } else {
            multisetOverlapCount(firstNorm, secondNorm)
        }
        return (shared.toDouble() / minSize.toDouble()) >= threshold
    }

    private fun longestCommonSubsequenceLength(a: List<String>, b: List<String>): Int {
        var prev = IntArray(b.size + 1)
        var curr = IntArray(b.size + 1)
        for (i in 1..a.size) {
            val ai = a[i - 1]
            for (j in 1..b.size) {
                curr[j] = if (ai == b[j - 1]) {
                    prev[j - 1] + 1
                } else {
                    maxOf(prev[j], curr[j - 1])
                }
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.size]
    }

    private fun multisetOverlapCount(a: List<String>, b: List<String>): Int {
        val counts = HashMap<String, Int>(a.size)
        for (w in a) counts[w] = (counts[w] ?: 0) + 1
        var matched = 0
        for (w in b) {
            val left = counts[w] ?: 0
            if (left > 0) {
                matched++
                counts[w] = left - 1
            }
        }
        return matched
    }

    private fun tokens(text: String): List<String> {
        return text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun normalize(word: String): String {
        return word.lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}]"), "")
    }
}
