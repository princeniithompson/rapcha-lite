package com.princeniithompson.rapcha.data

import android.content.Context
import android.util.Log
import com.princeniithompson.rapcha.core.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class TranslateLanguage(val displayName: String, val flagEmoji: String) {
    ENGLISH("English", "\uD83C\uDDEC\uD83C\uDDE7"),
    SPANISH("Spanish", "\uD83C\uDDEA\uD83C\uDDF8"),
    FRENCH("French", "\uD83C\uDDEB\uD83C\uDDF7"),
    GERMAN("German", "\uD83C\uDDE9\uD83C\uDDEA"),
    ITALIAN("Italian", "\uD83C\uDDEE\uD83C\uDDF9");

    val chipLabel: String get() = "$flagEmoji $displayName"

    companion object {
        fun fromName(name: String?): TranslateLanguage {
            return entries.firstOrNull { it.displayName.equals(name?.trim(), ignoreCase = true) }
                ?: ENGLISH
        }
    }
}

enum class ReplyLength(val displayName: String, val guidance: String) {
    SHORT("Short", "1 short, natural sentence"),
    MEDIUM("Medium", "2 to 3 natural, balanced sentences"),
    LONG("Long", "a thorough, thoughtful 4 to 5 sentence reply"),
    NO_REPLY("No Reply", "a polite, natural, believable excuse declining the request or invitation");

    companion object {
        fun fromName(name: String?): ReplyLength {
            return entries.firstOrNull { it.displayName.equals(name?.trim(), ignoreCase = true) }
                ?: SHORT
        }
    }
}

sealed class TextActionRequest {
    data class Grammar(val text: String) : TextActionRequest()

    data class Translate(
        val text: String,
        val languageName: String,
        val instruction: String = ""
    ) : TextActionRequest() {
        constructor(text: String, language: TranslateLanguage) : this(
            text = text,
            languageName = language.displayName,
            instruction = ""
        )
    }

    data class Reply(val text: String, val length: ReplyLength) : TextActionRequest()

    data class Tone(val text: String, val toneName: String, val instruction: String) : TextActionRequest()

    data class CustomModule(
        val text: String,
        val moduleName: String,
        val query: String
    ) : TextActionRequest()
}

sealed class TextActionResult {
    data class Success(val text: String) : TextActionResult()
    data class Error(val message: String) : TextActionResult()
}

/**
 * Executes floating Telescope text actions (Grammar, Translate, Reply, Tone, Custom Modules)
 * against `gemini-3.5-flash-lite` using Rapcha's existing Gemini API key and shared OkHttp pool.
 */
object FloatingTextActionsClient {
    private const val TAG = "FloatingTextActions"
    const val MODEL = "gemini-3.5-flash-lite"
    const val RESULT_ONLY_SUFFIX = "Return ONLY the result text, no explanations, no quotes."
    private const val TIMEOUT_SECONDS = 20L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    internal var httpExecutor: ((url: String, apiKey: String, bodyJson: String) -> Pair<Int, String>)? = null

    fun buildPrompt(request: TextActionRequest): String {
        val core = when (request) {
            is TextActionRequest.Grammar -> {
                "Fix the spelling, grammar, and punctuation of the following text while preserving its original meaning, tone, and language.\n\n" +
                    "Text:\n${request.text.trim()}"
            }
            is TextActionRequest.Translate -> {
                val dialectNote = request.instruction.trim()
                val noteLine = if (dialectNote.isNotEmpty()) {
                    "\nDialect/style instruction: $dialectNote"
                } else {
                    ""
                }
                "Translate the following text naturally into ${request.languageName.trim()}, preserving its meaning and tone.$noteLine\n\n" +
                    "Text:\n${request.text.trim()}"
            }
            is TextActionRequest.Reply -> {
                if (request.length == ReplyLength.NO_REPLY) {
                    "Read the following message and write a polite, natural, believable excuse declining the request or invitation so the sender feels respected without sounding rude or blunt. Make it clear I cannot make it right now, keep it concise and human, and match the language of the message.\n\n" +
                        "Message:\n${request.text.trim()}"
                } else {
                    "Write a helpful, natural reply to the following message. Keep the reply ${request.length.guidance} and match the language of the message.\n\n" +
                        "Message:\n${request.text.trim()}"
                }
            }
            is TextActionRequest.Tone -> {
                "Apply the following tone instruction to the text below.\n" +
                    "Tone (${request.toneName}): ${request.instruction.trim()}\n\n" +
                    "Text:\n${request.text.trim()}"
            }
            is TextActionRequest.CustomModule -> {
                "Apply the following instruction (${request.moduleName.trim()}) to the text below.\n" +
                    "Instruction: ${request.query.trim()}\n\n" +
                    "Text:\n${request.text.trim()}"
            }
        }
        return "$core\n\n$RESULT_ONLY_SUFFIX"
    }

    internal fun buildRequestBodyJson(prompt: String): String {
        return JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.35)
                }
            )
        }.toString()
    }

    internal fun parseResponseText(responseBody: String): String? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val partText = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (partText.isNotEmpty()) sb.append(partText)
            }
            cleanModelOutput(sb.toString()).takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun cleanModelOutput(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```") && text.length >= 6) {
            text = text.removePrefix("```").removeSuffix("```").trim()
            val firstNewline = text.indexOf('\n')
            if (firstNewline in 1..12 && text.substring(0, firstNewline).all { it.isLetter() }) {
                text = text.substring(firstNewline + 1).trim()
            }
        }
        if (text.length >= 2 &&
            ((text.startsWith("\"") && text.endsWith("\"")) ||
                (text.startsWith("\u201C") && text.endsWith("\u201D")))
        ) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    suspend fun execute(context: Context, request: TextActionRequest): TextActionResult {
        val apiKey = ApiConfig.resolveApiKey(context)
        if (ApiConfig.isPlaceholder(apiKey)) {
            return TextActionResult.Error("Gemini API key is required. Set it in Rapcha Settings.")
        }
        val prompt = buildPrompt(request)
        val url = ApiConfig.geminiGenerateContentUrl(MODEL)
        val bodyJson = buildRequestBodyJson(prompt)

        return withContext(Dispatchers.IO) {
            try {
                val (code, responseBody) = executeHttp(url, apiKey, bodyJson)
                ApiUsageRepository.recordModulesCall(code)
                if (code !in 200..299) {
                    Log.w(TAG, "Gemini Flash-Lite HTTP $code")
                    val detail = when (code) {
                        401, 403 -> "Invalid Gemini API key. Check it in Rapcha Settings."
                        429 -> "Rate limit reached. Please try again in a moment."
                        else -> "Could not generate text (HTTP $code). Tap Retry."
                    }
                    return@withContext TextActionResult.Error(detail)
                }
                val parsed = parseResponseText(responseBody)
                if (parsed.isNullOrBlank()) {
                    TextActionResult.Error("No text was returned. Tap Retry.")
                } else {
                    TextActionResult.Success(parsed)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Floating text action failed: ${t.javaClass.simpleName}")
                TextActionResult.Error("Connection error. Check internet and tap Retry.")
            }
        }
    }

    /**
     * Extracts raw delta text from a single SSE `data:` JSON chunk returned by
     * `streamGenerateContent?alt=sse`, without trimming leading/trailing spaces inside chunks.
     */
    internal fun parseStreamChunkText(chunkJson: String): String {
        return try {
            val root = JSONObject(chunkJson)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val parts = candidates.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val partText = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (partText.isNotEmpty()) sb.append(partText)
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Streams a Telescope action in real time from `gemini-3.5-flash-lite` (`streamGenerateContent?alt=sse`),
     * invoking [onPartialText] as chunks arrive so the user sees text immediately instead of waiting on a spinner.
     * No response caching: every invocation (including Retry) fetches a fresh generation.
     */
    suspend fun executeStreaming(
        context: Context,
        request: TextActionRequest,
        onPartialText: (String) -> Unit
    ): TextActionResult {
        // Test seam compatibility: unit tests injecting httpExecutor use the synchronous mock path.
        if (httpExecutor != null) {
            val res = execute(context, request)
            if (res is TextActionResult.Success) {
                onPartialText(res.text)
            }
            return res
        }

        val apiKey = ApiConfig.resolveApiKey(context)
        if (ApiConfig.isPlaceholder(apiKey)) {
            return TextActionResult.Error("Gemini API key is required. Set it in Rapcha Settings.")
        }

        val prompt = buildPrompt(request)
        val url = ApiConfig.geminiStreamGenerateContentUrl(MODEL)
        val bodyJson = buildRequestBodyJson(prompt)

        return withContext(Dispatchers.IO) {
            val accumulated = StringBuilder()
            try {
                val client = ApiConfig.createOkHttpClient()
                    .newBuilder()
                    .callTimeout(30L, TimeUnit.SECONDS)
                    .readTimeout(25L, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val code = resp.code
                    ApiUsageRepository.recordModulesCall(code)
                    if (code !in 200..299) {
                        Log.w(TAG, "Gemini Flash-Lite stream HTTP $code")
                        val detail = when (code) {
                            401, 403 -> "Invalid Gemini API key. Check it in Rapcha Settings."
                            429 -> "Rate limit reached. Please try again in a moment."
                            else -> "Could not generate text (HTTP $code). Tap Retry."
                        }
                        return@withContext TextActionResult.Error(detail)
                    }

                    val source = resp.body?.source()
                        ?: return@withContext TextActionResult.Error("No text was returned. Tap Retry.")

                    source.use { okioSource ->
                        val eventData = StringBuilder()
                        while (true) {
                            val line = okioSource.readUtf8Line() ?: break
                            when {
                                line.startsWith("data:") -> {
                                    val payload = line.removePrefix("data:").trim()
                                    if (payload.isNotEmpty() && payload != "[DONE]") {
                                        if (eventData.isNotEmpty()) eventData.append('\n')
                                        eventData.append(payload)
                                    }
                                }
                                line.isEmpty() -> {
                                    if (eventData.isNotEmpty()) {
                                        val delta = parseStreamChunkText(eventData.toString())
                                        eventData.setLength(0)
                                        if (delta.isNotEmpty()) {
                                            accumulated.append(delta)
                                            val preview = cleanModelOutput(accumulated.toString())
                                            if (preview.isNotEmpty()) {
                                                onPartialText(preview)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Handle a final data event without a trailing blank line.
                        if (eventData.isNotEmpty()) {
                            val delta = parseStreamChunkText(eventData.toString())
                            if (delta.isNotEmpty()) {
                                accumulated.append(delta)
                            }
                        }
                    }
                }

                val finalClean = cleanModelOutput(accumulated.toString())
                if (finalClean.isBlank()) {
                    TextActionResult.Error("No text was returned. Tap Retry.")
                } else {
                    TextActionResult.Success(finalClean)
                }
            } catch (t: Throwable) {
                val partialClean = cleanModelOutput(accumulated.toString())
                if (partialClean.isNotBlank()) {
                    Log.w(TAG, "Stream interrupted after partial text; returning received output")
                    TextActionResult.Success(partialClean)
                } else {
                    Log.w(TAG, "Floating text streaming action failed: ${t.javaClass.simpleName}")
                    TextActionResult.Error("Connection error. Check internet and tap Retry.")
                }
            }
        }
    }

    private fun executeHttp(url: String, apiKey: String, bodyJson: String): Pair<Int, String> {
        httpExecutor?.let { return it(url, apiKey, bodyJson) }
        val client = ApiConfig.createOkHttpClient()
            .newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
