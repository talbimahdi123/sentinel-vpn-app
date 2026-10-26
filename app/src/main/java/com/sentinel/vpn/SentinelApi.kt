package com.sentinel.vpn

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SentinelApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class Session(val token: String, val socks5: String, val model: String, val mac: String)

    fun fetchSession(deviceId: String, uid: String): Session? {
        return try {
            val url = "http://127.0.0.1:9092/session?device_id=$deviceId&player_uid=$uid"
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject
            val token = json.getAsJsonObject("auth").get("session_token").asString
            val socks5 = json.getAsJsonObject("proxy").get("socks5").asString
            val identity = json.getAsJsonObject("identity")
            val model = identity.get("model").asString
            val mac = identity.get("mac_wifi").asString
            Session(token, socks5, model, mac)
        } catch (e: Exception) {
            null
        }
    }

    fun checkHealth(): Boolean {
        return try {
            val req = Request.Builder().url("http://127.0.0.1:9092/health").build()
            client.newCall(req).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun requestRotation() {
        try {
            val req = Request.Builder().url("http://127.0.0.1:9092/rotate").post(okhttp3.RequestBody.create(null, "")).build()
            client.newCall(req).execute()
        } catch (_: Exception) {}
    }
}
