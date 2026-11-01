package com.neoplus.ai.calls

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Gemini REST client. history = list of (role "user"|"model", text). */
object Ai {
    fun reply(apiKey: String?, model: String, system: String, history: List<Pair<String, String>>, json: Boolean = false): Result<String> {
        if (apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("No API key. Add one in Security."))
        return runCatching {
            val contents = JSONArray()
            history.forEach { (role, text) ->
                contents.put(JSONObject().put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text))))
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
            if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 30000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                    .getOrDefault(text.take(120))
                error("AI service returned HTTP $code: $msg")
            }
            JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
        }
    }
}
