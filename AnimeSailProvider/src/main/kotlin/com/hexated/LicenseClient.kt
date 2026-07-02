package com.hexated

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.security.MessageDigest
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object AnimeSailLicenseClient {
    private const val TAG = "LicenseClient"
    private const val SERVER_URL = "https://zoxxy.eu.org"
    private var PREF_NAME = "cs_premium"
    private const val PREF_KEY = "license_key"

    private var cachedStatus: String? = null
    private var cacheExpiry: Long = 0L
    private var lastSuccessfulCheck: Long = 0L
    private val actionThrottle = mutableMapOf<String, Long>()
    private var licenseBlocked = false
    private var blockMessage = ""
    private var defaultPluginName: String = "AnimeSail"
    private var pluginName: String = "AnimeSail"
    private var appContext: Context? = null
    private var pluginSessionToken: String? = null
    private var pluginSessionPlugin: String? = null
    private var pluginSessionExpiry: Long = 0L

    fun init(context: Context, name: String = "AnimeSail") { 
        appContext = context.applicationContext 
        pluginName = name
        defaultPluginName = name
    }

    fun setLicenseKey(context: Context, key: String) {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(PREF_KEY, key.trim()).apply()
        resetCache()
    }

    fun getLicenseKey(): String? = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)?.getString(PREF_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun getHardwareHash(): String {
        val hwInfo = "${Build.BOARD}${Build.BRAND}${Build.DEVICE}${Build.HARDWARE}${Build.MANUFACTURER}${Build.MODEL}${Build.PRODUCT}"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(hwInfo.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(8)
    }

    private fun getDeviceId(): String {
        val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return "unknown"
        var deviceId = prefs.getString("device_uuid", null)
        if (!deviceId.isNullOrEmpty() && deviceId != "unknown") return deviceId
        var finalAndroidId = "unknown"
        try {
            val aId = Settings.Secure.getString(appContext?.contentResolver, Settings.Secure.ANDROID_ID)
            if (!aId.isNullOrEmpty() && aId != "unknown" && aId.length >= 8) finalAndroidId = aId
        } catch (e: Exception) {}
        deviceId = "$finalAndroidId-${getHardwareHash()}"
        prefs.edit().putString("device_uuid", deviceId).apply()
        return deviceId
    }

    private fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return (if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model").take(100)
    }

    private suspend fun discoverKey(pluginName: String): String? {
        return try {
            val deviceId = getDeviceId()
            val cleanPlugin = pluginName.replace("\"", "")
            val response = app.get("$SERVER_URL/api/discover?device_id=$deviceId&plugin_name=$cleanPlugin").text
            val json = tryParseJson<KeyByIpResponse>(response)
            if (json?.status == "active" && !json.key.isNullOrEmpty()) { json.key } else null
        } catch (e: Exception) { Log.w(TAG, "discoverKey failed: ${e.message}"); null }
    }

    suspend fun checkLicense(pluginName: String, action: String = "OPEN", data: String? = null): Boolean {
        val now = System.currentTimeMillis()
        val throttleKey = "$pluginName|$action"
        val throttleMs = when (action.uppercase()) { "HOME" -> 60_000L; "SEARCH" -> 10_000L; else -> 5_000L }
        if (now - (actionThrottle[throttleKey] ?: 0L) < throttleMs && cachedStatus == "active") return true
        actionThrottle[throttleKey] = now
        if (cachedStatus == "active" && now < cacheExpiry && action.uppercase() != "PLAY") { logActionAsync(pluginName, action, data); return true }
        var key = getLicenseKey()
        if (key.isNullOrEmpty()) key = discoverKey(pluginName)
        if (key.isNullOrEmpty()) { licenseBlocked = true; blockMessage = "Lisensi tidak ditemukan. Pastikan repo URL premium sudah ditambahkan."; return false }
        return try {
            val deviceId = getDeviceId(); val deviceModel = getDeviceModel()
            val jsonPayload = """{"key":"$key","device_id":"$deviceId","device_model":"${deviceModel.replace("\"", "")}","plugin_name":"${pluginName.replace("\"", "")}","action":"${action.replace("\"", "")}","data":"${(data ?: "").replace("\"", "")}"}"""
            val body = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post("$SERVER_URL/api/verify_activity", requestBody = body).text
            val json = tryParseJson<CheckResponse>(response)
            if (json?.status == "active" || json?.status == "success") {
                cachedStatus = "active"; cacheExpiry = 0L; licenseBlocked = false; blockMessage = ""; true
            } else {
                cachedStatus = "error"; licenseBlocked = true; blockMessage = json?.message ?: "Lisensi tidak valid atau perangkat diblokir"
                if (json?.reason == "not_found" || json?.reason == "revoked") appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)?.edit()?.remove(PREF_KEY)?.apply()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "License check error: ${e.message}")
            if (cachedStatus == "active" && now < lastSuccessfulCheck + 600_000L) true else { licenseBlocked = true; blockMessage = "Tidak dapat memverifikasi lisensi."; false }
        }
    }

    suspend fun requireLicense(pluginName: String, action: String = "OPEN", data: String? = null) {
        if (!checkLicense(pluginName, action, data)) throw RuntimeException("[PREMIUM] $blockMessage")
    }

    fun trackActivity(pluginName: String, action: String, data: String = "") { logActionAsync(pluginName, action, data) }

    private fun logActionAsync(pluginName: String, action: String, data: String?) {
        val key = getLicenseKey() ?: return
        val deviceId = getDeviceId(); val deviceModel = getDeviceModel()
        GlobalScope.launch {
            try {
                val jsonPayload = """{"key":"$key","device_id":"$deviceId","device_model":"${deviceModel.replace("\"", "")}","plugin_name":"${pluginName.replace("\"", "\\\"")}","action":"${action.replace("\"", "\\\"")}","data":"${data?.replace("\"", "\\\"") ?: ""}"}"""
                app.post("$SERVER_URL/api/verify_activity", requestBody = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull()))
            } catch (e: Exception) { Log.w(TAG, "logActionAsync failed: ${e.message}") }
        }
    }

    fun isBlocked(): Boolean = licenseBlocked
    fun getBlockMessage(): String = blockMessage

    fun resetCache() {
        cachedStatus = null; cacheExpiry = 0L; licenseBlocked = false; blockMessage = ""
        pluginSessionToken = null; pluginSessionPlugin = null; pluginSessionExpiry = 0L; actionThrottle.clear()
    }

    private suspend fun getPluginSessionToken(pluginName: String): String? {
        val now = System.currentTimeMillis()
        if (pluginSessionPlugin == pluginName && !pluginSessionToken.isNullOrEmpty() && now < pluginSessionExpiry - 15_000L) return pluginSessionToken
        var key = getLicenseKey()
        if (key.isNullOrEmpty()) key = discoverKey(pluginName)
        if (key.isNullOrEmpty()) { licenseBlocked = true; blockMessage = "Lisensi tidak ditemukan."; return null }
        return try {
            val deviceId = getDeviceId(); val deviceModel = getDeviceModel()
            val jsonPayload = """{"key":"$key","device_id":"$deviceId","device_model":"${deviceModel.replace("\"", "")}","plugin_name":"${pluginName.replace("\"", "")}","action":"SESSION","data":""}"""
            val body = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post("$SERVER_URL/api/plugin/session", requestBody = body).text
            val json = tryParseJson<PluginSessionResponse>(response)
            if (json?.status == "ok" && !json.sessionToken.isNullOrEmpty()) {
                pluginSessionToken = json.sessionToken; pluginSessionPlugin = pluginName
                pluginSessionExpiry = now + ((json.expiresIn ?: 300) * 1000L); licenseBlocked = false; blockMessage = ""; json.sessionToken
            } else {
                pluginSessionToken = null; pluginSessionPlugin = null; pluginSessionExpiry = 0L
                licenseBlocked = true; blockMessage = json?.message ?: "Session tidak valid"; null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Plugin session error: ${e.message}")
            if (pluginSessionPlugin == pluginName && !pluginSessionToken.isNullOrEmpty() && now < pluginSessionExpiry + 60_000L) pluginSessionToken else null
        }
    }

    suspend fun getSelectors(pluginName: String): SelectorConfig? {
        val now = System.currentTimeMillis()
        selectorCache[pluginName]?.let { (cfg, expiry) -> if (now < expiry) return cfg }
        val token = getPluginSessionToken(pluginName) ?: run { selectorCache.remove(pluginName); return null }
        return try {
            val jsonPayload = """{"plugin_name":"${pluginName.replace("\"", "")}"}"""
            val body = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post("$SERVER_URL/api/selectors", headers = mapOf("Authorization" to "Bearer $token"), requestBody = body).text
            val json = tryParseJson<SelectorResponse>(response)
            if (json?.status == "ok" && json.selectors != null) {
                val raw = json.selectors
                val cfg = SelectorConfig(
                    playerSelector = raw.serverSelector?.takeIf { it.isNotBlank() } ?: ".mobius > .mirror > option, .mobius option, select.mirror option",
                    playerAttr = raw.valueAttr?.takeIf { it.isNotBlank() } ?: "data-em",
                    useBase64 = raw.encoding?.lowercase() != "plain"
                )
                selectorCache[pluginName] = Pair(cfg, now + CACHE_TTL); licenseBlocked = false; blockMessage = ""; cfg
            } else { licenseBlocked = true; blockMessage = json?.message ?: "Selector tidak tersedia"; selectorCache.remove(pluginName); null }
        } catch (e: Exception) {
            Log.e(TAG, "getSelectors error: ${e.message}")
            selectorCache[pluginName]?.let { (cfg, expiry) -> if (now < expiry + 2 * 60 * 1000L) return cfg }
            null
        }
    }

    fun clearSelectorCache() { selectorCache.clear() }

    private val selectorCache = mutableMapOf<String, Pair<SelectorConfig, Long>>()
    private val CACHE_TTL = 300_000L  // 5 menit

    data class SelectorConfig(val playerSelector: String? = null, val playerAttr: String = "data-em", val useBase64: Boolean = true)
    data class CheckResponse(@com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null, @com.fasterxml.jackson.annotation.JsonProperty("message") val message: String? = null, @com.fasterxml.jackson.annotation.JsonProperty("reason") val reason: String? = null)
    data class KeyByIpResponse(@com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null, @com.fasterxml.jackson.annotation.JsonProperty("key") val key: String? = null)
    data class PluginSessionResponse(@com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null, @com.fasterxml.jackson.annotation.JsonProperty("session_token") val sessionToken: String? = null, @com.fasterxml.jackson.annotation.JsonProperty("expires_in") val expiresIn: Int? = null, @com.fasterxml.jackson.annotation.JsonProperty("message") val message: String? = null, @com.fasterxml.jackson.annotation.JsonProperty("reason") val reason: String? = null)
    data class SelectorResponse(@com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null, @com.fasterxml.jackson.annotation.JsonProperty("selectors") val selectors: RawSelector? = null, @com.fasterxml.jackson.annotation.JsonProperty("message") val message: String? = null)
    data class RawSelector(@com.fasterxml.jackson.annotation.JsonProperty("server_selector") val serverSelector: String? = null, @com.fasterxml.jackson.annotation.JsonProperty("value_attr") val valueAttr: String? = null, @com.fasterxml.jackson.annotation.JsonProperty("encoding") val encoding: String? = null)
}


