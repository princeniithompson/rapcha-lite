package com.princeniithompson.rapcha.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Light adaptive gain for the copy of the audio that is sent to Gemini.
 *
 * Quiet speech (one or two words, said softly) often reaches the model at a very low
 * level on budget phones. This raises it toward [TARGET_RMS], at most [MAX_GAIN]x,
 * and never above unity for speech that is already loud enough.
 *
 * - The level is learned only from chunks that look like speech (well above the noise
 *   floor), so silence and steady background noise do not pump the gain up.
 * - Gain falls fast and rises slowly, and it ramps inside each chunk (no clicks).
 * - The gain for a chunk is capped so its peak stays under [PEAK_CEILING]; a soft knee
 *   above [KNEE] keeps any ramp overshoot from clipping. Output never wraps.
 * - Input bytes are never modified; a new array is returned when gain is applied.
 *
 * PCM is 16-bit signed little-endian mono.
 */
class SpeechGain {
    companion object {
        const val TARGET_RMS = 2500.0
        const val MAX_GAIN = 6.0
        const val PEAK_CEILING = 29000.0
        const val KNEE = 28000.0
        private const val MIN_SPEECH_RMS = 150.0
        private const val SPEECH_OVER_FLOOR = 2.0
        private const val RISE = 0.25

        fun rms(pcm: ByteArray, length: Int = pcm.size): Double {
            val n = length / 2
            if (n <= 0) return 0.0
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val s = sample(pcm, i).toDouble()
                sum += s * s
                i += 2
            }
            return sqrt(sum / n)
        }

        fun peak(pcm: ByteArray, length: Int = pcm.size): Int {
            var p = 0
            var i = 0
            while (i + 1 < length) {
                val s = abs(sample(pcm, i))
                if (s > p) p = s
                i += 2
            }
            return p
        }

        internal fun sample(pcm: ByteArray, i: Int): Int =
            ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()

        internal fun softLimit(x: Double): Double {
            val a = abs(x)
            if (a <= KNEE) return x
            val over = a - KNEE
            val limited = min(32767.0, KNEE + over / (1.0 + over / (32767.0 - KNEE)))
            return if (x < 0) -limited else limited
        }
    }

    private var noiseFloor = -1.0
    private var speechLevel = -1.0
    var currentGain = 1.0
        private set

    fun reset() {
        noiseFloor = -1.0
        speechLevel = -1.0
        currentGain = 1.0
        previousApplied = 1.0
    }

    /**
     * Returns the chunk to transmit. With [adapt] false (replayed audio) the current gain is
     * applied without learning from the chunk.
     */
    fun process(pcm: ByteArray, adapt: Boolean = true): ByteArray {
        val len = pcm.size - (pcm.size % 2)
        if (len <= 0) return pcm
        val r = rms(pcm, len)
        val p = peak(pcm, len)

        if (adapt) {
            if (noiseFloor < 0) {
                noiseFloor = max(30.0, r)
            } else if (r < noiseFloor) {
                noiseFloor = max(30.0, noiseFloor * 0.7 + r * 0.3)
            } else {
                noiseFloor = noiseFloor * 0.995 + r * 0.005
            }
            val isSpeech = r >= max(MIN_SPEECH_RMS, noiseFloor * SPEECH_OVER_FLOOR)
            if (isSpeech) {
                val firstSpeech = speechLevel < 0
                speechLevel = if (firstSpeech) r else speechLevel * 0.8 + r * 0.2
                val target = (TARGET_RMS / max(speechLevel, 1.0)).coerceIn(1.0, MAX_GAIN)
                currentGain = when {
                    // The first quiet word should already be lifted.
                    firstSpeech -> target
                    target < currentGain -> target
                    else -> currentGain + (target - currentGain) * RISE
                }
            }
        }

        val cap = if (p > 0) PEAK_CEILING / p else MAX_GAIN
        val end = min(currentGain, cap).coerceAtLeast(1.0)
        val start = min(previousApplied, cap).coerceAtLeast(1.0)
        previousApplied = end
        if (start == 1.0 && end == 1.0) return pcm

        val out = ByteArray(pcm.size)
        val n = len / 2
        var i = 0
        var k = 0
        while (i + 1 < len) {
            val g = start + (end - start) * (k.toDouble() / n)
            val y = softLimit(sample(pcm, i) * g)
            val v = y.toInt().coerceIn(-32768, 32767)
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
            k++
        }
        if (pcm.size > len) out[len] = pcm[len]
        return out
    }

    private var previousApplied = 1.0
}
