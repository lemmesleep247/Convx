/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ai

import android.content.Context
import com.convx.music.constants.AiProviderKey
import com.convx.music.constants.OpenRouterApiKey
import com.convx.music.constants.OpenRouterBaseUrlKey
import com.convx.music.constants.OpenRouterModelKey
import com.convx.music.utils.dataStore
import com.convx.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Sends chat-completions requests using the configured provider URL, model, and API key. */
object AiChatClient {
    private val client = OkHttpClient()

    /** Returns the assistant response, or null if the request fails. */
    suspend fun complete(
        context: Context,
        systemPrompt: String,
        userPrompt: String,
        onLog: suspend (String) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val provider = context.dataStore.get(AiProviderKey, "OpenRouter")
        if (provider == "Puter") {
            onLog("Puter is not implemented yet.")
            return@withContext null
        }

        val apiKey = context.dataStore.get(OpenRouterApiKey, "")
        val baseUrl = context.dataStore.get(OpenRouterBaseUrlKey, "https://openrouter.ai/api/v1/chat/completions")
        val model = context.dataStore.get(OpenRouterModelKey, "google/gemini-3.1-flash-lite")
        onLog("Using $provider / $model")

        if (apiKey.isEmpty()) {
            onLog("API Key is missing. Please set it in Settings.")
            return@withContext null
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body.string().take(300)
                onLog("AI request failed: ${response.code} $errorBody")
                return@withContext null
            }
            val responseString = response.body.string()
            val responseJson = JSONObject(responseString)
            val choices = responseJson.optJSONArray("choices") ?: return@withContext null
            choices.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        } catch (e: Exception) {
            onLog("Network error: ${e.message}")
            null
        }
    }
}
