import UserNotifications
import JavaScriptCore
import Security

class NotificationService: UNNotificationServiceExtension {

    var contentHandler: ((UNNotificationContent) -> Void)?
    var bestAttemptContent: UNMutableNotificationContent?
    let appGroupId = "group.com.gigiaj.paradise"

    private func sanitizeScriptName(_ rawPath: String?) -> String? {
        guard let raw = rawPath?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }
        let lastComponent = (raw as NSString).lastPathComponent
        return (lastComponent.isEmpty || !lastComponent.hasSuffix(".js")) ? nil : lastComponent
    }

    private func resolveSessionForPush(
        state: [String: Any],
        payload: [String: Any]
    ) -> (String, [String: String])? {
        guard let rawSessions = state["shadow_sessions"] as? [String: Any] else {
            return nil
        }

        func toStringMap(_ dict: [String: Any]) -> [String: String] {
            var out: [String: String] = [:]
            for (k, v) in dict {
                out[k] = "\(v)"
            }
            return out
        }

        let accountHint = (payload["account_id"] as? String) ?? (payload["user_id"] as? String)
        let cleanScript = sanitizeScriptName(payload["script"] as? String)
        let cleanEngine = (payload["engine"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)

        if let acc = accountHint?.trimmingCharacters(in: .whitespacesAndNewlines), !acc.isEmpty {
            if let exact = rawSessions[acc] as? [String: Any] {
                return (acc, toStringMap(exact))
            }
            for (k, v) in rawSessions {
                if k.hasPrefix("@\(acc):") || acc.hasPrefix("@\(k):"),
                   let sess = v as? [String: Any] {
                    return (k, toStringMap(sess))
                }
            }
        }

        let activeUserId = state["active_push_user"] as? String
        if let active = activeUserId, !active.isEmpty,
           let activeRaw = rawSessions[active] as? [String: Any] {
            let sessMap = toStringMap(activeRaw)
            let sessScript = sanitizeScriptName(sessMap["notification_script"])
            let sessEngine = sessMap["engine"] ?? ""
            let scriptMatches = (cleanScript == nil || cleanScript == sessScript)
            let engineMatches = (cleanEngine == nil || cleanEngine!.isEmpty || sessEngine.isEmpty || cleanEngine == sessEngine)
            if scriptMatches && engineMatches {
                return (active, sessMap)
            }
        }

        if cleanScript != nil || (cleanEngine != nil && !cleanEngine!.isEmpty) {
            for (k, v) in rawSessions {
                guard let rawSess = v as? [String: Any] else { continue }
                let sessMap = toStringMap(rawSess)
                let sessScript = sanitizeScriptName(sessMap["notification_script"])
                let sessEngine = sessMap["engine"] ?? ""
                if (cleanScript != nil && cleanScript == sessScript) ||
                   (cleanEngine != nil && !cleanEngine!.isEmpty && cleanEngine == sessEngine) {
                    return (k, sessMap)
                }
            }
        }

        if let active = activeUserId, !active.isEmpty,
           let activeRaw = rawSessions[active] as? [String: Any] {
            return (active, toStringMap(activeRaw))
        }

        for (k, v) in rawSessions {
            if let rawSess = v as? [String: Any] {
                return (k, toStringMap(rawSess))
            }
        }

        return nil
    }

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        bestAttemptContent = (request.content.mutableCopy() as? UNMutableNotificationContent)

        guard let bestAttemptContent = bestAttemptContent else { return }

        guard let containerUrl = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupId) else {
            contentHandler(bestAttemptContent)
            return
        }

        let payloadDict = request.content.userInfo as? [String: Any] ?? [:]
        let stateUrl = containerUrl.appendingPathComponent("shadow_state.json")

        guard let stateData = try? Data(contentsOf: stateUrl),
              let state = try? JSONSerialization.jsonObject(with: stateData) as? [String: Any],
              let (resolvedUserId, sessionData) = resolveSessionForPush(state: state, payload: payloadDict) else {
            contentHandler(bestAttemptContent)
            return
        }

        if (state["show_previews"] as? Bool) == false {
            bestAttemptContent.title = "New Message"
            bestAttemptContent.body = "Open Paradise to view."
            contentHandler(bestAttemptContent)
            return
        }

        guard let configuredScript = sanitizeScriptName(payloadDict["script"] as? String)
                ?? sanitizeScriptName(sessionData["notification_script"]) else {
            contentHandler(bestAttemptContent)
            return
        }

        let scriptsDir = containerUrl.appendingPathComponent("scripts")
        let scriptUrl = scriptsDir.appendingPathComponent(configuredScript)

        guard FileManager.default.fileExists(atPath: scriptUrl.path) else {
            contentHandler(bestAttemptContent)
            return
        }

        let safeUserId = resolvedUserId.replacingOccurrences(of: "[^a-zA-Z0-9]", with: "_", options: .regularExpression)
        let storeDir = containerUrl.appendingPathComponent("push-store/\(safeUserId)")

        DispatchQueue.global(qos: .userInitiated).async {
            let (result, debugErr) = self.runPushWorker(
                scriptUrl: scriptUrl,
                payload: payloadDict,
                session: sessionData,
                storeDir: storeDir
            )

            if debugErr != nil {
                contentHandler(bestAttemptContent)
                return
            }

            if let tag = result?["tag"] as? String, !tag.isEmpty {
                bestAttemptContent.threadIdentifier = tag
            }

            if let title = result?["title"] as? String, !title.isEmpty {
                bestAttemptContent.title = title
            }
            if let subtitle = result?["subtitle"] as? String, !subtitle.isEmpty {
                bestAttemptContent.subtitle = subtitle
            }
            if let bodyText = result?["body"] as? String, !bodyText.isEmpty {
                bestAttemptContent.body = bodyText
            }

            if let badge = result?["unread"] as? NSNumber {
                bestAttemptContent.badge = badge
            }

            contentHandler(bestAttemptContent)
        }
    }

    private func runPushWorker(
        scriptUrl: URL,
        payload: [String: Any],
        session: [String: String],
        storeDir: URL
    ) -> ([String: Any]?, String?) {
        guard let vm = JSVirtualMachine(),
              let ctx = JSContext(virtualMachine: vm) else {
            return (nil, "JSContext init failed")
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

        var evalError: String? = nil
        autoreleasepool {
            guard let scriptSource = try? String(contentsOf: scriptUrl, encoding: .utf8) else {
                evalError = "Failed to read script string"
                return
            }
            ctx.evaluateScript(scriptSource, withSourceURL: scriptUrl)
            if let exc = ctx.exception {
                evalError = "JS eval error: \(exc)"
            }
        }

        if let evalError = evalError {
            return (nil, evalError)
        }

        guard let resolveFn = ctx.objectForKeyedSubscript("resolvePushNotification"),
              !resolveFn.isUndefined else {
            return (nil, "resolvePushNotification is undefined")
        }

        guard let resultVal = resolveFn.call(withArguments: [payload, session]) else {
            return (nil, "resolvePushNotification returned nil")
        }

        if let exc = ctx.exception {
            return (nil, "JS call error: \(exc)")
        }

        guard resultVal.isObject,
              let dict = resultVal.toDictionary() as? [String: Any] else {
            return (nil, "Result not an object")
        }

        return (dict, nil)
    }

    override func serviceExtensionTimeWillExpire() {
        if let contentHandler = contentHandler, let bestAttemptContent = bestAttemptContent {
            contentHandler(bestAttemptContent)
        }
    }
}

