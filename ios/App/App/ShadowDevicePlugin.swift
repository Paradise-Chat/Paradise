import Foundation
import Capacitor
import JavaScriptCore
import Security

@objc(ShadowDevicePlugin)
public class ShadowDevicePlugin: CAPPlugin, CAPBridgedPlugin {

    public let identifier = "ShadowDevicePlugin"
    public let jsName = "ShadowDevice"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "syncPushScript", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createSleepyShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "activateShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deactivateShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "verifyShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "importRoomKeys", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getShadowStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getActivePushUser", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPreviewPreferences", returnType: CAPPluginReturnPromise)
    ]

    let appGroupId = "group.com.gigiaj.paradise"
    private let stateLock = NSLock()

    private func extractOptions(_ call: CAPPluginCall) -> [String: Any] {
        var out: [String: Any] = [:]
        for (key, value) in call.options {
            if let k = key as? String, !(value is NSNull) {
                out[k] = value
            }
        }
        return out
    }

    private func sanitizeScriptName(_ rawPath: String?) -> String? {
        guard let raw = rawPath?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }
        let lastComponent = (raw as NSString).lastPathComponent
        return (lastComponent.isEmpty || !lastComponent.hasSuffix(".js")) ? nil : lastComponent
    }

    private func resolveScriptName(options: [String: Any], sessionData: [String: String]? = nil) -> String? {
        if let fromOptions = sanitizeScriptName((options["notificationScript"] as? String) ?? (options["scriptName"] as? String)) {
            return fromOptions
        }
        return sanitizeScriptName(sessionData?["notification_script"])
    }

    private func getContainerUrl() throws -> URL {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupId) else {
            throw NSError(domain: "ShadowDevice", code: 1, userInfo: [NSLocalizedDescriptionKey: "App Group container not found"])
        }
        return url
    }

    private func getStateFileUrl() throws -> URL {
        return try getContainerUrl().appendingPathComponent("shadow_state.json")
    }

    private func readSharedState() -> [String: Any] {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard let url = try? getStateFileUrl(),
              let data = try? Data(contentsOf: url),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return dict
    }

    private func writeSharedState(_ state: [String: Any]) {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard let url = try? getStateFileUrl(),
              let data = try? JSONSerialization.data(withJSONObject: state, options: [.prettyPrinted]) else {
            return
        }
        try? data.write(to: url, options: [.atomic])
    }

    private func getShadowSession(userId: String) -> [String: String]? {
        let state = readSharedState()
        guard let sessions = state["shadow_sessions"] as? [String: Any],
              let rawUserSession = sessions[userId] as? [String: Any] else {
            return nil
        }
        var out: [String: String] = [:]
        for (k, v) in rawUserSession {
            out[k] = "\(v)"
        }
        return out
    }

    private func saveShadowSession(userId: String, sessionData: [String: String]) {
        var state = readSharedState()
        var sessions = state["shadow_sessions"] as? [String: [String: String]] ?? [:]
        sessions[userId] = sessionData
        state["shadow_sessions"] = sessions
        writeSharedState(state)
    }

    private func setActiveUser(_ userId: String?) {
        var state = readSharedState()
        if let uid = userId {
            state["active_push_user"] = uid
        } else {
            state.removeValue(forKey: "active_push_user")
        }
        writeSharedState(state)
    }

    private func stripSourceMap(_ source: String) -> String {
        if let range = source.range(of: "//# sourceMappingURL=", options: .backwards) {
            return String(source[..<range.lowerBound])
        }
        return source
    }

    private func syncScriptToAppGroup(scriptName: String, scriptSource: String? = nil) throws -> String {
        let containerUrl = try getContainerUrl()
        guard let cleanName = sanitizeScriptName(scriptName) else {
            throw NSError(domain: "ShadowDevice", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid script name: \(scriptName)"])
        }
        let destUrl = containerUrl.appendingPathComponent("scripts/\(cleanName)")
        try? FileManager.default.createDirectory(at: destUrl.deletingLastPathComponent(), withIntermediateDirectories: true)

        if let providedSource = scriptSource, !providedSource.isEmpty {
            let cleanSource = stripSourceMap(providedSource)
            try cleanSource.write(to: destUrl, atomically: true, encoding: .utf8)
            return cleanSource
        }

        if let existing = try? String(contentsOf: destUrl, encoding: .utf8), !existing.isEmpty {
            return existing
        }

        throw NSError(domain: "ShadowDevice", code: 2, userInfo: [NSLocalizedDescriptionKey: "Could not locate \(cleanName) in App Group"])
    }

    private func invokeWorkerFunction(
        userId: String,
        scriptName: String,
        functionName: String,
        arguments: [Any]
    ) throws -> [String: Any] {
        let containerUrl = try getContainerUrl()
        guard let cleanScriptName = sanitizeScriptName(scriptName) else {
            throw NSError(domain: "ShadowDevice", code: 2, userInfo: [NSLocalizedDescriptionKey: "Missing or invalid notification script"])
        }
        let scriptSource = try syncScriptToAppGroup(scriptName: cleanScriptName)
        let safeUserId = userId.replacingOccurrences(of: "[^a-zA-Z0-9]", with: "_", options: .regularExpression)
        let storeDir = containerUrl.appendingPathComponent("push-store/\(safeUserId)")

        guard let vm = JSVirtualMachine(),
              let ctx = JSContext(virtualMachine: vm) else {
            throw NSError(domain: "ShadowDevice", code: 3, userInfo: [NSLocalizedDescriptionKey: "Failed to create JSContext"])
        }

        let getRandomBytes: @convention(block) (Int) -> [UInt8] = { count in
            guard count > 0 else { return [] }
            var bytes = [UInt8](repeating: 0, count: count)
            _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
            return bytes
        }

        let httpSync: @convention(block) (String, String, [String: String], String?) -> [String: Any] = { method, urlStr, headers, body in
            guard let url = URL(string: urlStr) else {
                return ["ok": false, "status": 0, "body": "Invalid URL: \(urlStr)"]
            }
            var req = URLRequest(url: url, timeoutInterval: 10.0)
            req.httpMethod = method
            for (k, v) in headers {
                req.setValue(v, forHTTPHeaderField: k)
            }
            if method != "GET" && method != "HEAD",
               let b = body, !b.isEmpty, b != "null", b != "undefined" {
                req.httpBody = b.data(using: .utf8)
            }

            let sem = DispatchSemaphore(value: 0)
            var responseDict: [String: Any] = ["ok": false, "status": 0, "body": "Timeout"]

            URLSession.shared.dataTask(with: req) { data, response, error in
                if let httpResp = response as? HTTPURLResponse {
                    let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                    responseDict = [
                        "ok": (200...299).contains(httpResp.statusCode),
                        "status": httpResp.statusCode,
                        "body": text
                    ]
                } else if let error = error {
                    responseDict = [
                        "ok": false,
                        "status": 0,
                        "body": error.localizedDescription
                    ]
                }
                sem.signal()
            }.resume()

            _ = sem.wait(timeout: .now() + 12.0)
            return responseDict
        }

        let storageGet: @convention(block) (String) -> String? = { relPath in
            let clean = relPath.replacingOccurrences(of: "..", with: "_")
            let url = storeDir.appendingPathComponent(clean)
            return try? String(contentsOf: url, encoding: .utf8)
        }

        let storageSet: @convention(block) (String, String) -> Bool = { relPath, content in
            let clean = relPath.replacingOccurrences(of: "..", with: "_")
            let url = storeDir.appendingPathComponent(clean)
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            do {
                try content.write(to: url, atomically: true, encoding: .utf8)
                return true
            } catch {
                return false
            }
        }

        ctx.setObject(getRandomBytes, forKeyedSubscript: "__nativeGetRandomBytes" as NSString)
        ctx.setObject(httpSync, forKeyedSubscript: "__hostHttpSync" as NSString)
        ctx.setObject(storageGet, forKeyedSubscript: "__hostStorageGet" as NSString)
        ctx.setObject(storageSet, forKeyedSubscript: "__hostStorageSet" as NSString)

        ctx.evaluateScript(scriptSource)

        if let exception = ctx.exception {
            throw NSError(domain: "ShadowDevice", code: 4, userInfo: [NSLocalizedDescriptionKey: "JS evaluation error: \(exception)"])
        }

        guard let fnVal = ctx.objectForKeyedSubscript(functionName),
              !fnVal.isUndefined,
              let resVal = fnVal.call(withArguments: arguments) else {
            throw NSError(domain: "ShadowDevice", code: 5, userInfo: [NSLocalizedDescriptionKey: "Function \(functionName) missing or failed"])
        }

        if let exception = ctx.exception {
            throw NSError(domain: "ShadowDevice", code: 6, userInfo: [NSLocalizedDescriptionKey: "JS execution error: \(exception)"])
        }

        return (resVal.toDictionary() as? [String: Any]) ?? [:]
    }

    @objc func syncPushScript(_ call: CAPPluginCall) {
        let options = extractOptions(call)
        guard let rawName = options["scriptName"] as? String,
              let cleanName = sanitizeScriptName(rawName),
              let scriptSource = options["scriptSource"] as? String else {
            return call.reject("Missing or invalid scriptName or scriptSource")
        }

        DispatchQueue.global(qos: .utility).async {
            do {
                _ = try self.syncScriptToAppGroup(scriptName: cleanName, scriptSource: scriptSource)
                call.resolve(["status": "script_synced", "scriptName": cleanName])
            } catch {
                call.reject("Failed to sync push script", nil, error)
            }
        }
    }

    @objc func createSleepyShadow(_ call: CAPPluginCall) {
        let options = extractOptions(call)
        guard let userId = (options["userId"] as? String) ?? (options["user-id"] as? String) else {
            return call.reject("Missing userId")
        }

        guard let cleanName = resolveScriptName(options: options) else {
            return call.reject("Missing notificationScript")
        }

        if let providedSource = options["scriptSource"] as? String, !providedSource.isEmpty {
            _ = try? syncScriptToAppGroup(scriptName: cleanName, scriptSource: providedSource)
        }

        let engine = (options["engine"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        var workerArgs = options
        workerArgs.removeValue(forKey: "scriptSource")

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let res = try self.invokeWorkerFunction(
                    userId: userId,
                    scriptName: cleanName,
                    functionName: "createShadowSession",
                    arguments: [workerArgs]
                )

                guard (res["ok"] as? Bool) == true,
                      let rawSession = res["session"] as? [String: Any] else {
                    let errMsg = (res["error"] as? String) ?? "Unknown error in createShadowSession"
                    return call.reject(errMsg)
                }

                var stringSession: [String: String] = [:]
                for (k, v) in rawSession {
                    stringSession[k] = "\(v)"
                }
                stringSession["notification_script"] = cleanName
                if !engine.isEmpty {
                    stringSession["engine"] = engine
                }

                let resolvedUserId = (res["userId"] as? String) ?? userId
                self.saveShadowSession(userId: resolvedUserId, sessionData: stringSession)
                call.resolve(["status": "sleepy_shadow_created"])
            } catch {
                call.reject("Failed to create Sleepy Shadow", nil, error)
            }
        }
    }

    @objc func activateShadow(_ call: CAPPluginCall) {
        let options = extractOptions(call)
        guard let userId = (options["userId"] as? String) ?? (options["user-id"] as? String) else {
            return call.reject("Missing userId")
        }

        guard let sessionData = getShadowSession(userId: userId) else {
            return call.reject("No shadow session found for user: \(userId)")
        }

        guard let scriptName = resolveScriptName(options: options, sessionData: sessionData) else {
            return call.reject("No notification_script configured for user: \(userId)")
        }

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let res = try self.invokeWorkerFunction(
                    userId: userId,
                    scriptName: scriptName,
                    functionName: "activatePushSession",
                    arguments: [options, sessionData]
                )

                guard (res["ok"] as? Bool) == true else {
                    let errMsg = (res["error"] as? String) ?? "Failed to activate push session"
                    return call.reject(errMsg)
                }

                self.setActiveUser(userId)
                call.resolve(["status": "shadow_activated"])
            } catch {
                call.reject("Failed to activate Shadow Device", nil, error)
            }
        }
    }

    @objc func deactivateShadow(_ call: CAPPluginCall) {
        let options = extractOptions(call)
        guard let userId = (options["userId"] as? String) ?? (options["user-id"] as? String) else {
            return call.reject("Missing userId")
        }

        guard let sessionData = getShadowSession(userId: userId) else {
            setActiveUser(nil)
            return call.resolve(["status": "shadow_deactivated"])
        }

        guard let scriptName = resolveScriptName(options: options, sessionData: sessionData) else {
            setActiveUser(nil)
            return call.resolve(["status": "shadow_deactivated"])
        }

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                _ = try self.invokeWorkerFunction(
                    userId: userId,
                    scriptName: scriptName,
                    functionName: "deactivatePushSession",
                    arguments: [options, sessionData]
                )
                self.setActiveUser(nil)
                call.resolve(["status": "shadow_deactivated"])
            } catch {
                call.reject("Failed to deactivate Shadow Device", nil, error)
            }
        }
    }

    @objc func verifyShadow(_ call: CAPPluginCall) {
        let options = extractOptions(call)
        guard let userId = (options["userId"] as? String) ?? (options["user-id"] as? String) else {
            return call.reject("Missing userId")
        }

        guard var sessionData = getShadowSession(userId: userId) else {
            return call.reject("No shadow session found for user: \(userId)")
        }

        guard let scriptName = resolveScriptName(options: options, sessionData: sessionData) else {
            return call.reject("No notification_script configured for user: \(userId)")
        }

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let res = try self.invokeWorkerFunction(
                    userId: userId,
                    scriptName: scriptName,
                    functionName: "verifyPushSession",
                    arguments: [options, sessionData]
                )

                guard (res["ok"] as? Bool) == true else {
                    let errMsg = (res["error"] as? String) ?? "Failed to verify shadow session"
                    return call.reject(errMsg)
                }

                sessionData["is_verified"] = "true"
                self.saveShadowSession(userId: userId, sessionData: sessionData)
                call.resolve(["status": "shadow_verified"])
            } catch {
                call.reject("Failed to verify Shadow Device", nil, error)
            }
        }
    }

    @objc func importRoomKeys(_ call: CAPPluginCall) {
        let options = extractOptions(call)
        guard let userId = (options["userId"] as? String) ?? (options["user-id"] as? String),
              let exportedKeysJson = options["exportedRoomKeysJson"] as? String else {
            return call.reject("Missing userId or exportedRoomKeysJson")
        }

        let sessionData = getShadowSession(userId: userId)
        guard let scriptName = resolveScriptName(options: options, sessionData: sessionData) else {
            return call.reject("No notification_script configured for user: \(userId)")
        }

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                _ = try self.invokeWorkerFunction(
                    userId: userId,
                    scriptName: scriptName,
                    functionName: "importRoomKeys",
                    arguments: [exportedKeysJson]
                )
                call.resolve(["status": "keys_imported"])
            } catch {
                call.reject("Failed to import room keys", nil, error)
            }
        }
    }

    @objc func getShadowStatus(_ call: CAPPluginCall) {
        let options = extractOptions(call)
        guard let userId = (options["userId"] as? String) ?? (options["user-id"] as? String) else {
            return call.reject("Missing userId")
        }

        if let sessionData = getShadowSession(userId: userId),
           sessionData["is_verified"] == "true" {
            call.resolve(["isVerified": true])
        } else {
            call.resolve(["isVerified": false])
        }
    }

    @objc func getActivePushUser(_ call: CAPPluginCall) {
        let state = readSharedState()
        if let activePushUser = state["active_push_user"] as? String {
            call.resolve(["activeUser": activePushUser])
        } else {
            call.resolve([:])
        }
    }

    @objc func setPreviewPreferences(_ call: CAPPluginCall) {
        let isEnabled = call.getBool("enabled", true)
        var state = readSharedState()
        state["show_previews"] = isEnabled
        writeSharedState(state)
        call.resolve()
    }
}

