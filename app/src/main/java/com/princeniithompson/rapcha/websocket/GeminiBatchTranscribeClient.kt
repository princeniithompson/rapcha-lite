package com.princeniithompson.rapcha.websocket

import android.util.Base64
import android.util.Log
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Non-live batch transcription fallback client using Google's dedicated `gemini-3.5-transcribe`
 * REST model (`generateContent`).
 *
 * Used automatically when the live WebSocket (`gemini-3.5-transcribe-live`) drops, fails to
 * connect, or stalls after Complete on slow/unstable mobile networks. Supports Smart Mode
 * (`mode = "SMART"`) and Custom Vocabulary (capped to the top 100 active terms).
 */
object GeminiBatchTranscribeClient {
    private const val TAG = "GeminiBatchTranscribe"
    const val MODEL = "gemini-3.5-transcribe"
    private const val MAX_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 1_000L
    private const val CONNECT_TIMEOUT_SEC = 20L
    private const val WRITE_TIMEOUT_SEC = 45L
    private const val READ_TIMEOUT_SEC = 45L
    private const val CALL_TIMEOUT_SEC = 60L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Test seam for unit tests without live network calls. */
    internal var httpExecutor: ((url: String, apiKey: String, bodyJson: String) -> Pair<Int, String>)? = null

    fun buildRequestBodyJson(
        audioBytes: ByteArray,
        mimeType: String,
        smartMode: Boolean,
        customVocabulary: List<String>
    ): String {
        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val trimmedVocab = customVocabulary
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(CustomVocabularyRepository.MAX_GEMINI_ACTIVE_VOCAB_TERMS)
            .toList()

        val transcriptionConfig = JSONObject().apply {
            put("mode", if (smartMode) "SMART" else "VERBATIM")
            if (trimmedVocab.isNotEmpty()) {
                val arr = JSONArray()
                trimmedVocab.forEach { arr.put(it) }
                put("custom_vocabulary", arr)
            }
        }

        return JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put(
                            "parts",
                            JSONArray().put(
                                JSONObject().apply {
                                    put(
                                        "inlineData",
                                        JSONObject().apply {
                                            put("mimeType", mimeType)
                                            put("data", base64Audio)
                                        }
                                    )
                                }
                            )
                        )
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("audioTranscriptionConfig", transcriptionConfig)
                }
            )
        }.toString()
    }

    fun parseResponseText(responseBody: String): String? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (text.isNotEmpty()) sb.append(text)
            }
            sb.toString().trim().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed parsing batch transcribe response: ${t.message}")
            null
        }
    }

    data class BatchTranscribeResult(
        val text: String?,
        val lastHttpCode: Int,
        val attempts: Int
    )

    /**
     * Uploads [audioBytes] (`audio/mp4` or `audio/wav`) to `gemini-3.5-transcribe` and returns the
     * transcribed text, or `null` if the request fails after retries.
     */
    suspend fun transcribe(
        apiKey: String,
        audioBytes: ByteArray,
        mimeType: String,
        smartMode: Boolean,
        customVocabulary: List<String>
    ): String? {
        return transcribeDetailed(
            apiKey = apiKey,
            audioBytes = audioBytes,
            mimeType = mimeType,
            smartMode = smartMode,
            customVocabulary = customVocabulary
        ).text
    }

    /**
     * Same as [transcribe], also returning the final HTTP status code and attempt count for
     * release-safe diagnostics.
     */
    suspend fun transcribeDetailed(
        apiKey: String,
        audioBytes: ByteArray,
        mimeType: String,
        smartMode: Boolean,
        customVocabulary: List<String>
    ): BatchTranscribeResult {
        val key = apiKey.trim()
        if (ApiConfig.isPlaceholder(key) || audioBytes.isEmpty()) {
            return BatchTranscribeResult(text = null, lastHttpCode = 0, attempts = 0)
        }

        val url = ApiConfig.geminiGenerateContentUrl(MODEL)
        val bodyJson = buildRequestBodyJson(
            audioBytes = audioBytes,
            mimeType = mimeType,
            smartMode = smartMode,
            customVocabulary = customVocabulary
        )

        return withContext(Dispatchers.IO) {
            var lastCode = 0
            var attemptsRun = 0
            for (attempt in 1..MAX_ATTEMPTS) {
                attemptsRun = attempt
                try {
                    val (code, body) = executeHttp(url, key, bodyJson)
                    lastCode = code
                    com.princeniithompson.rapcha.data.ApiUsageRepository.recordOfflinePushCall(code)
                    if (code in 200..299) {
                        val parsed = parseResponseText(body)
                        Log.i(TAG, "Batch fallback succeeded on attempt $attempt (mime=$mimeType, bytes=${audioBytes.size}, chars=${parsed?.length ?: 0})")
                        return@withContext BatchTranscribeResult(text = parsed, lastHttpCode = code, attempts = attempt)
                    }
                    Log.w(TAG, "Batch fallback HTTP $code on attempt $attempt")
                    if (code == 400 || code == 401 || code == 403 || code == 404) {
                        return@withContext BatchTranscribeResult(text = null, lastHttpCode = code, attempts = attempt)
                    }
                } catch (t: Throwable) {
                    lastCode = 0
                    Log.w(TAG, "Batch fallback attempt $attempt failed: ${t.javaClass.simpleName}: ${t.message}")
                }
                if (attempt < MAX_ATTEMPTS) {
                    delay(RETRY_DELAY_MS)
                }
            }
            BatchTranscribeResult(text = null, lastHttpCode = lastCode, attempts = attemptsRun)
        }
    }

    private fun executeHttp(url: String, apiKey: String, bodyJson: String): Pair<Int, String> {
        httpExecutor?.let { return it(url, apiKey, bodyJson) }
        val client = ApiConfig.createOkHttpClient()
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string().orEmpty())
        }
    }
}
