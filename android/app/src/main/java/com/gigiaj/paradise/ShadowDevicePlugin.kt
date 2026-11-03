package com.gigiaj.paradise

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@CapacitorPlugin(name = "ShadowDevice")
class ShadowDevicePlugin : Plugin() {

    private fun extractOptionsJson(call: PluginCall): JSONObject {
        val data = call.data ?: return JSONObject()
        return JSONObject(data.toString())
    }

    private fun resolveUserId(options: JSONObject): String? {
        val uid = options.optString("userId", options.optString("user-id", ""))
        return if (uid.isBlank()) null else uid
    }

    private fun resolveScriptName(options: JSONObject, sessionData: JSONObject? = null): String? {
        val fromOptions = options.optString("notificationScript", options.optString("scriptName", ""))
        ShadowPushRuntime.sanitizeScriptName(fromOptions)?.let { return it }
        val fromSession = sessionData?.optString("notification_script", "")
        return ShadowPushRuntime.sanitizeScriptName(fromSession)
    }

    @PluginMethod
    fun syncPushScript(call: PluginCall) {
        val scriptName = call.getString("scriptName")
        val scriptSource = call.getString("scriptSource")
        val cleanName = ShadowPushRuntime.sanitizeScriptName(scriptName)
        if (cleanName == null || scriptSource.isNullOrBlank()) {
            return call.reject("Missing or invalid scriptName or scriptSource")
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ShadowPushRuntime.syncScript(context, cleanName, scriptSource)
                val ret = JSObject().apply {
                    put("status", "script_synced")
                    put("scriptName", cleanName)
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to sync push script", e)
            }
        }
    }

    @PluginMethod
    fun createSleepyShadow(call: PluginCall) {
        val options = extractOptionsJson(call)
        val userId = resolveUserId(options) ?: return call.reject("Missing userId")
        val cleanName = resolveScriptName(options)
            ?: return call.reject("Missing notificationScript")
        val providedSource = options.optString("scriptSource", "")
        val engine = options.optString("engine", "").trim()

        options.remove("scriptSource")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (providedSource.isNotBlank()) {
                    ShadowPushRuntime.syncScript(context, cleanName, providedSource)
                }

                val args = JSONArray().apply { put(options) }
                val res = ShadowPushRuntime.invokeWorkerFunction(
                    context = context,
                    userId = userId,
                    scriptName = cleanName,
                    functionName = "createShadowSession",
                    argsJsonArray = args
                )

                if (!res.optBoolean("ok", false)) {
                    val err = res.optString("error", "Unknown error in createShadowSession")
                    return@launch call.reject(err)
                }

                val rawSession = res.optJSONObject("session")
                    ?: return@launch call.reject("Missing session object from worker")

                val sessionData = JSONObject()
                val keys = rawSession.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    sessionData.put(k, rawSession.opt(k).toString())
                }
                sessionData.put("notification_script", cleanName)
                if (engine.isNotEmpty()) {
                    sessionData.put("engine", engine)
                }

                val resolvedUserId = res.optString("userId", userId).ifBlank { userId }
                ShadowPushRuntime.saveShadowSession(context, resolvedUserId, sessionData)

                val ret = JSObject().apply {
                    put("status", "sleepy_shadow_created")
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to create Sleepy Shadow", e)
            }
        }
    }

    @PluginMethod
    fun activateShadow(call: PluginCall) {
        val options = extractOptionsJson(call)
        val userId = resolveUserId(options) ?: return call.reject("Missing userId")
        val sessionData = ShadowPushRuntime.getShadowSession(context, userId)
            ?: return call.reject("No shadow session found for user: $userId")

        val passedAppId = options.optString("appId", "")
        if (passedAppId.isBlank() || passedAppId == "moi.paradise.ios") {
            options.put("appId", "moi.paradise.android")
        }

        val scriptName = resolveScriptName(options, sessionData)
            ?: return call.reject("No notification_script configured for session: $userId")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val args = JSONArray().apply {
                    put(options)
                    put(sessionData)
                }
                val res = ShadowPushRuntime.invokeWorkerFunction(
                    context = context,
                    userId = userId,
                    scriptName = scriptName,
                    functionName = "activatePushSession",
                    argsJsonArray = args
                )

                if (!res.optBoolean("ok", false)) {
                    val err = res.optString("error", "Failed to activate push session")
                    return@launch call.reject(err)
                }

                ShadowPushRuntime.setActiveUser(context, userId)
                val ret = JSObject().apply {
                    put("status", "shadow_activated")
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to activate Shadow Device", e)
            }
        }
    }

    @PluginMethod
    fun deactivateShadow(call: PluginCall) {
        val options = extractOptionsJson(call)
        val userId = resolveUserId(options) ?: return call.reject("Missing userId")
        val sessionData = ShadowPushRuntime.getShadowSession(context, userId)

        if (sessionData == null) {
            ShadowPushRuntime.setActiveUser(context, null)
            val ret = JSObject().apply { put("status", "shadow_deactivated") }
            return call.resolve(ret)
        }

        val passedAppId = options.optString("appId", "")
        if (passedAppId.isBlank() || passedAppId == "moi.paradise.ios") {
            options.put("appId", "moi.paradise.android")
        }

        val scriptName = resolveScriptName(options, sessionData)
            ?: return call.reject("No notification_script configured for session: $userId")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val args = JSONArray().apply {
                    put(options)
                    put(sessionData)
                }
                ShadowPushRuntime.invokeWorkerFunction(
                    context = context,
                    userId = userId,
                    scriptName = scriptName,
                    functionName = "deactivatePushSession",
                    argsJsonArray = args
                )
                ShadowPushRuntime.setActiveUser(context, null)
                val ret = JSObject().apply { put("status", "shadow_deactivated") }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to deactivate Shadow Device", e)
            }
        }
    }

    @PluginMethod
    fun verifyShadow(call: PluginCall) {
        val options = extractOptionsJson(call)
        val userId = resolveUserId(options) ?: return call.reject("Missing userId")
        val sessionData = ShadowPushRuntime.getShadowSession(context, userId)
            ?: return call.reject("No shadow session found for user: $userId")

        val scriptName = resolveScriptName(options, sessionData)
            ?: return call.reject("No notification_script configured for session: $userId")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val args = JSONArray().apply {
                    put(options)
                    put(sessionData)
                }
                val res = ShadowPushRuntime.invokeWorkerFunction(
                    context = context,
                    userId = userId,
                    scriptName = scriptName,
                    functionName = "verifyPushSession",
                    argsJsonArray = args
                )

                if (!res.optBoolean("ok", false)) {
                    val err = res.optString("error", "Failed to verify shadow session")
                    return@launch call.reject(err)
                }

                sessionData.put("is_verified", "true")
                ShadowPushRuntime.saveShadowSession(context, userId, sessionData)

                val ret = JSObject().apply { put("status", "shadow_verified") }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to verify Shadow Device", e)
            }
        }
    }

    @PluginMethod
    fun importRoomKeys(call: PluginCall) {
        val options = extractOptionsJson(call)
        val userId = resolveUserId(options) ?: return call.reject("Missing userId")
        val exportedKeysJson = options.optString("exportedRoomKeysJson", "")
        if (exportedKeysJson.isBlank()) {
            return call.reject("Missing exportedRoomKeysJson")
        }

        val sessionData = ShadowPushRuntime.getShadowSession(context, userId)
        val scriptName = resolveScriptName(options, sessionData)
            ?: return call.reject("No notification_script configured for session: $userId")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val args = JSONArray().apply { put(exportedKeysJson) }
                ShadowPushRuntime.invokeWorkerFunction(
                    context = context,
                    userId = userId,
                    scriptName = scriptName,
                    functionName = "importRoomKeys",
                    argsJsonArray = args
                )
                val ret = JSObject().apply { put("status", "keys_imported") }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to import room keys", e)
            }
        }
    }

    @PluginMethod
    fun getShadowStatus(call: PluginCall) {
        val options = extractOptionsJson(call)
        val userId = resolveUserId(options) ?: return call.reject("Missing userId")
        val sessionData = ShadowPushRuntime.getShadowSession(context, userId)
        val isVerified = sessionData?.optString("is_verified") == "true"
        val ret = JSObject().apply { put("isVerified", isVerified) }
        call.resolve(ret)
    }

    @PluginMethod
    fun getActivePushUser(call: PluginCall) {
        val activeUser = ShadowPushRuntime.getActiveUser(context)
        val ret = JSObject()
        if (activeUser != null) {
            ret.put("activeUser", activeUser)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun setPreviewPreferences(call: PluginCall) {
        val isEnabled = call.getBoolean("enabled", true) ?: true
        ShadowPushRuntime.setShowPreviews(context, isEnabled)
        call.resolve()
    }
}

