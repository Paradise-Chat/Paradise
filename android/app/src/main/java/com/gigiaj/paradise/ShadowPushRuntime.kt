package com.gigiaj.paradise

import android.content.Context
import app.cash.quickjs.QuickJs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

object ShadowPushRuntime {

    private val stateLock = Any()
    private val secureRandom = SecureRandom()

    interface JsHostBridge {
        fun getRandomBytesHex(count: Int): String
        fun httpSync(method: String, urlStr: String, headersJson: String, body: String?): String
        fun storageGet(relPath: String): String?
        fun storageSet(relPath: String, content: String): Boolean
    }

    fun sanitizeScriptName(rawPath: String?): String? {
        if (rawPath.isNullOrBlank()) return null
        val name = File(rawPath.trim()).name
        return if (name.isBlank() || !name.endsWith(".js")) null else name
    }

    private fun stripSourceMap(source: String): String {
        val marker = "//# sourceMappingURL="
        val idx = source.lastIndexOf(marker)
        return if (idx >= 0) source.substring(0, idx) else source
    }

    private fun getStateFile(context: Context): File {
        return File(context.applicationContext.filesDir, "shadow_state.json")
    }

    fun readSharedState(context: Context): JSONObject {
        synchronized(stateLock) {
            val file = getStateFile(context)
            if (!file.exists()) return JSONObject()
            return try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                JSONObject()
            }
        }
    }

    fun writeSharedState(context: Context, state: JSONObject) {
        synchronized(stateLock) {
            val file = getStateFile(context)
            val tmpFile = File(file.parentFile, "shadow_state.json.tmp")
            tmpFile.writeText(state.toString(), Charsets.UTF_8)
            tmpFile.renameTo(file)
        }
    }

    fun getShadowSession(context: Context, userId: String): JSONObject? {
        val state = readSharedState(context)
        val sessions = state.optJSONObject("shadow_sessions") ?: return null
        sessions.optJSONObject(userId)?.let { return it }
        val keys = sessions.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("@$userId:") || userId.startsWith("@$k:")) {
                return sessions.optJSONObject(k)
            }
        }
        val firstKey = sessions.keys().asSequence().firstOrNull() ?: return null
        return sessions.optJSONObject(firstKey)
    }

    fun resolveSessionForPush(
        context: Context,
        accountId: String?,
        incomingScript: String?,
        incomingEngine: String?
    ): Pair<String, JSONObject>? {
        val state = readSharedState(context)
        val sessions = state.optJSONObject("shadow_sessions") ?: return null
        val cleanScript = sanitizeScriptName(incomingScript)
        val cleanEngine = incomingEngine?.trim()?.takeIf { it.isNotEmpty() }

        if (!accountId.isNullOrBlank()) {
            sessions.optJSONObject(accountId)?.let { return accountId to it }
            val keys = sessions.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("@$accountId:") || accountId.startsWith("@$k:")) {
                    sessions.optJSONObject(k)?.let { return k to it }
                }
            }
        }

        val activeUser = state.optString("active_push_user", "")
        if (activeUser.isNotBlank()) {
            val activeSession = sessions.optJSONObject(activeUser)
            if (activeSession != null) {
                val sessScript = sanitizeScriptName(activeSession.optString("notification_script", ""))
                val sessEngine = activeSession.optString("engine", "").trim()
                val scriptMatches = cleanScript == null || cleanScript == sessScript
                val engineMatches = cleanEngine == null || sessEngine.isEmpty() || cleanEngine == sessEngine
                if (scriptMatches && engineMatches) {
                    return activeUser to activeSession
                }
            }
        }

        if (cleanScript != null || cleanEngine != null) {
            val keys = sessions.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val sess = sessions.optJSONObject(k) ?: continue
                val sessScript = sanitizeScriptName(sess.optString("notification_script", ""))
                val sessEngine = sess.optString("engine", "").trim()
                if ((cleanScript != null && cleanScript == sessScript) ||
                    (cleanEngine != null && cleanEngine == sessEngine)
                ) {
                    return k to sess
                }
            }
        }

        if (activeUser.isNotBlank()) {
            sessions.optJSONObject(activeUser)?.let { return activeUser to it }
        }

        val firstKey = sessions.keys().asSequence().firstOrNull() ?: return null
        val firstSession = sessions.optJSONObject(firstKey) ?: return null
        return firstKey to firstSession
    }

    fun saveShadowSession(context: Context, userId: String, sessionData: JSONObject) {
        val state = readSharedState(context)
        val sessions = state.optJSONObject("shadow_sessions") ?: JSONObject()
        sessions.put(userId, sessionData)
        state.put("shadow_sessions", sessions)
        if (state.optString("active_push_user", "").isBlank()) {
            state.put("active_push_user", userId)
        }
        writeSharedState(context, state)
    }

    fun setActiveUser(context: Context, userId: String?) {
        val state = readSharedState(context)
        if (userId != null) {
            state.put("active_push_user", userId)
        } else {
            state.remove("active_push_user")
        }
        writeSharedState(context, state)
    }

    fun getActiveUser(context: Context): String? {
        val state = readSharedState(context)
        val uid = state.optString("active_push_user", "")
        if (uid.isNotBlank()) return uid
        val sessions = state.optJSONObject("shadow_sessions") ?: return null
        return sessions.keys().asSequence().firstOrNull()
    }

    fun setShowPreviews(context: Context, enabled: Boolean) {
        val state = readSharedState(context)
        state.put("show_previews", enabled)
        writeSharedState(context, state)
    }

    fun getShowPreviews(context: Context): Boolean {
        val state = readSharedState(context)
        return state.optBoolean("show_previews", true)
    }

    fun syncScript(context: Context, scriptName: String, scriptSource: String? = null): String {
        val appCtx = context.applicationContext
        val cleanName = sanitizeScriptName(scriptName)
            ?: throw IllegalArgumentException("Invalid notification script name: $scriptName")
        val scriptsDir = File(appCtx.filesDir, "scripts")
        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        val destFile = File(scriptsDir, cleanName)

        if (!scriptSource.isNullOrBlank()) {
            val clean = stripSourceMap(scriptSource)
            destFile.writeText(clean, Charsets.UTF_8)
            return clean
        }

        val assetCandidates = listOf(
            "public/$cleanName",
            "public/push-workers/$cleanName",
            cleanName
        )

        for (assetPath in assetCandidates) {
            try {
                val bundled = appCtx.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (bundled.isNotBlank()) {
                    val clean = stripSourceMap(bundled)
                    destFile.writeText(clean, Charsets.UTF_8)
                    return clean
                }
            } catch (_: Exception) {
            }
        }

        if (destFile.exists()) {
            val existing = destFile.readText(Charsets.UTF_8)
            if (existing.isNotBlank()) return existing
        }

        throw IllegalStateException("Could not locate notification script: $cleanName")
    }

    fun invokeWorkerFunction(
        context: Context,
        userId: String,
        scriptName: String,
        functionName: String,
        argsJsonArray: JSONArray
    ): JSONObject {
        val appCtx = context.applicationContext
        val cleanScriptName = sanitizeScriptName(scriptName)
            ?: throw IllegalArgumentException("Missing or invalid notification script")
        val scriptSource = syncScript(appCtx, cleanScriptName, null)
        val safeUserId = userId.replace(Regex("[^a-zA-Z0-9]"), "_")
        val storeDir = File(appCtx.filesDir, "push-store/$safeUserId")
        if (!storeDir.exists()) storeDir.mkdirs()

        val bridge = object : JsHostBridge {
            override fun getRandomBytesHex(count: Int): String {
                if (count <= 0) return ""
                val bytes = ByteArray(count)
                secureRandom.nextBytes(bytes)
                val sb = StringBuilder(count * 2)
                for (b in bytes) {
                    sb.append(String.format("%02x", b))
                }
                return sb.toString()
            }

            override fun httpSync(method: String, urlStr: String, headersJson: String, body: String?): String {
                var conn: HttpURLConnection? = null
                return try {
                    val url = URL(urlStr)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = 10000
                        readTimeout = 12000
                        doInput = true
                    }

                    val headersObj = try {
                        JSONObject(headersJson)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        conn.setRequestProperty(k, headersObj.optString(k, ""))
                    }

                    if (method != "GET" && method != "HEAD" && !body.isNullOrEmpty() && body != "null" && body != "undefined") {
                        conn.doOutput = true
                        conn.outputStream.use { os ->
                            os.write(body.toByteArray(Charsets.UTF_8))
                        }
                    }

                    val status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    val respText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                    JSONObject().apply {
                        put("ok", status in 200..299)
                        put("status", status)
                        put("body", respText)
                    }.toString()
                } catch (e: Exception) {
                    JSONObject().apply {
                        put("ok", false)
                        put("status", 0)
                        put("body", e.message ?: "HTTP failure")
                    }.toString()
                } finally {
                    conn?.disconnect()
                }
            }

            override fun storageGet(relPath: String): String? {
                val clean = relPath.replace("..", "_")
                val file = File(storeDir, clean)
                if (!file.exists()) return null
                return try {
                    file.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    null
                }
            }

            override fun storageSet(relPath: String, content: String): Boolean {
                val clean = relPath.replace("..", "_")
                val file = File(storeDir, clean)
                return try {
                    file.parentFile?.mkdirs()
                    file.writeText(content, Charsets.UTF_8)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        val bootstrapJs = """
            globalThis.__nativeGetRandomBytes = function(count) {
              var hex = __hostBridge.getRandomBytesHex(count);
              var out = new Uint8Array(count);
              for (var i = 0; i < count; i++) {
                out[i] = parseInt(hex.substr(i * 2, 2), 16);
              }
              return out;
            };
            globalThis.__hostStorageGet = function(k) {
              return __hostBridge.storageGet(String(k));
            };
            globalThis.__hostStorageSet = function(k, v) {
              return __hostBridge.storageSet(String(k), String(v));
            };
            globalThis.__hostHttpSync = function(method, url, headers, body) {
              var hJson = headers ? JSON.stringify(headers) : "{}";
              var bStr = (body === null || body === undefined) ? "" : String(body);
              var raw = __hostBridge.httpSync(String(method), String(url), hJson, bStr);
              return JSON.parse(raw);
            };
        """.trimIndent()

        val serializedArgs = JSONObject.quote(argsJsonArray.toString())
        val invokeJs = """
            (function() {
              var fn = globalThis["$functionName"];
              if (typeof fn !== "function") {
                return JSON.stringify({ ok: false, error: "Missing function $functionName" });
              }
              var args = JSON.parse($serializedArgs);
              var res = fn.apply(globalThis, args);
              return JSON.stringify(res || {});
            })();
        """.trimIndent()

        return QuickJs.create().use { qjs ->
            qjs.set("__hostBridge", JsHostBridge::class.java, bridge)
            qjs.evaluate(bootstrapJs, "bootstrap.js")
            qjs.evaluate(scriptSource, cleanScriptName)
            val resultStr = qjs.evaluate(invokeJs, "invoke.js") as? String ?: "{}"
            JSONObject(resultStr)
        }
    }
}

