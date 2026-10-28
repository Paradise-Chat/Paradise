import Foundation
import Capacitor
import JavaScriptCore
import Security

@objc(ShadowDevicePlugin)
public class ShadowDevicePlugin: CAPPlugin, CAPBridgedPlugin {
    
    public let identifier = "ShadowDevicePlugin"
    public let jsName = "ShadowDevice"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "createSleepyShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "activateShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deactivateShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "verifyShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getShadowStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getActivePushUser", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPreviewPreferences", returnType: CAPPluginReturnPromise)
    ]

    let appGroupId = "group.com.gigiaj.paradise"

    private func buildMatrixClient(homeserverUrl: String, ssStr: String, userId: String) async throws -> Client {
        guard let containerUrl = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupId) else {
            throw NSError(domain: "ShadowDevice", code: 1, userInfo: [NSLocalizedDescriptionKey: "App Group container not found!"])
        }
        
        let safeUserId = userId.replacingOccurrences(of: "[^a-zA-Z0-9]", with: "_", options: .regularExpression)
        
        let dataPath = containerUrl.appendingPathComponent("matrix-shadow/\(safeUserId)/data").path
        let cachePath = containerUrl.appendingPathComponent("matrix-shadow/\(safeUserId)/cache").path

        let ssBuilder: SlidingSyncVersionBuilder
        switch ssStr {
        case "NATIVE": ssBuilder = .native
        case "NONE": ssBuilder = .none
        default: ssBuilder = .discoverNative
        }
        
        return try await ClientBuilder()
            .homeserverUrl(url: homeserverUrl)
            .sessionPaths(dataPath: dataPath, cachePath: cachePath)
            .systemIsMemoryConstrained()
            .slidingSyncVersionBuilder(versionBuilder: ssBuilder)
            .build()
    }
    

    private func getShadowSession(userId: String) -> [String: String]? {
        let sharedDefaults = UserDefaults(suiteName: appGroupId)!
        let sessions = sharedDefaults.dictionary(forKey: "shadow_sessions") as? [String: [String: String]] ?? [:]
        return sessions[userId]
    }

    private func restoreShadowSession(client: Client, sessionData: [String: String]) async throws {
        guard let accessToken = sessionData["access_token"],
              let userId = sessionData["user_id"],
              let deviceId = sessionData["device_id"],
              let homeserverUrl = sessionData["homeserver_url"] else {
            throw NSError(domain: "ShadowDevice", code: 2, userInfo: [NSLocalizedDescriptionKey: "Missing session data in dictionary"])
        }
        
        let slidingSyncStr = sessionData["sliding_sync_version"] ?? "NONE"
        let ssVersion: SlidingSyncVersion = (slidingSyncStr == "NATIVE") ? .native : .none
        
        let session = Session(
            accessToken: accessToken,
            refreshToken: nil,
            userId: userId,
            deviceId: deviceId,
            homeserverUrl: homeserverUrl,
            oidcData: nil,
            slidingSyncVersion: ssVersion
        )
        
        try await client.restoreSession(session: session)
    }

    @objc func createSleepyShadow(_ call: CAPPluginCall) {
        let sharedDefaults = UserDefaults(suiteName: appGroupId)!
        
        guard let homeserver = call.getString("homeserverUrl"),
              let username = call.getString("username"),
              let password = call.getString("password"),
              let userId = call.getString("userId")

              else {
                    return call.reject("Missing arguments")
              }

        Task {
            do {
                let client = try await buildMatrixClient(homeserverUrl: homeserver, ssStr: "DISCOVER", userId: userId)
                try await client.login(username: username, password: password, initialDeviceName: "Paradise Background Sync", deviceId: nil)
                
                let session = try client.session()
                let ssName = (session.slidingSyncVersion == .native) ? "NATIVE" : "NONE"
                
                var sessions = sharedDefaults.dictionary(forKey: "shadow_sessions") as? [String: [String: String]] ?? [:]
                sessions[session.userId] = [
                    "access_token": session.accessToken,
                    "user_id": session.userId,
                    "device_id": session.deviceId,
                    "homeserver_url": homeserver,
                    "sliding_sync_version": ssName
                ]
                sharedDefaults.set(sessions, forKey: "shadow_sessions")
                
                call.resolve(["status": "sleepy_shadow_created"])
            } catch {
                call.reject("Failed to create Sleepy Shadow", nil, error)
            }
        }
    }


    @objc func activateShadow(_ call: CAPPluginCall) {

        let sharedDefaults = UserDefaults(suiteName: appGroupId)!

        guard let pushToken = call.getString("pushToken"),
              let pushUrl = call.getString("pushUrl"),
              let userId = call.getString("userId") else {
            return call.reject("Missing arguments")
        }
        
        guard let sessionData = getShadowSession(userId: userId) else {
            return call.reject("No shadow session found for user: \(userId)")
        }
        
        let homeserver = sessionData["homeserver_url"]!
        let ssStr = sessionData["sliding_sync_version"] ?? "NONE"


        Task {
            do {
                let client = try await buildMatrixClient(homeserverUrl: homeserver, ssStr: ssStr, userId: userId)
                try await restoreShadowSession(client: client, sessionData: sessionData)
                
                let payloadDict: [String: Any] = [
                    "aps": [
                        "mutable-content": 1,
                        "alert": [
                            "loc-key": "Notification",
                            "loc-args": []
                        ]
                    ]
                ]
                
                let payloadData = try JSONSerialization.data(withJSONObject: payloadDict)
                guard let payloadString = String(data: payloadData, encoding: .utf8) else {
                    return call.reject("Failed to encode payload string")
                }
                
                let identifiers = PusherIdentifiers(pushkey: pushToken, appId: "moi.paradise.ios")
                let httpData = HttpPusherData(url: pushUrl, format: .eventIdOnly, defaultPayload: payloadString)

                try await client.setPusher(
                    identifiers: identifiers,
                    kind: PusherKind.http(data: httpData),
                    appDisplayName: "Paradise",
                    deviceDisplayName: "iOS Device",
                    profileTag: "primary",
                    lang: "en"
                )

                sharedDefaults.set(userId, forKey: "active_push_user")

                call.resolve(["status": "shadow_activated"])
            } catch {
                call.reject("Failed to activate Shadow Device", nil, error)
            }
        }
    }
    
    @objc func deactivateShadow(_ call: CAPPluginCall) {
        let sharedDefaults = UserDefaults(suiteName: appGroupId)!


        guard let pushToken = call.getString("pushToken"),
              let userId = call.getString("userId") else {
            return call.reject("Missing arguments")
        }
        
        guard let sessionData = getShadowSession(userId: userId) else {
            return call.reject("No shadow session found for user: \(userId)")
        }

        let homeserver = sessionData["homeserver_url"]!
        let ssStr = sessionData["sliding_sync_version"] ?? "NONE"
        
        Task {
            do {
                let client = try await buildMatrixClient(homeserverUrl: homeserver, ssStr: ssStr, userId: userId)
                try await restoreShadowSession(client: client, sessionData: sessionData)
                
                let identifiers = PusherIdentifiers(pushkey: pushToken, appId: "moi.paradise.ios")
                try await client.deletePusher(identifiers: identifiers)

                sharedDefaults.removeObject(forKey: "active_push_user")

                call.resolve(["status": "shadow_deactivated"])
            } catch {
                call.reject("Failed to deactivate Shadow Device", nil, error)
            }
         }
        
        }


        @objc func verifyShadow(_ call: CAPPluginCall) {
        let sharedDefaults = UserDefaults(suiteName: appGroupId)!

        guard let userId = call.getString("userId"),
              let recoveryKey = call.getString("recoveryKey") else {
            return call.reject("Missing arguments")
        }

        guard var sessionData = getShadowSession(userId: userId) else {
            return call.reject("No shadow session found for user: \(userId)")
        }

        let homeserver = sessionData["homeserver_url"]!
        let ssStr = sessionData["sliding_sync_version"] ?? "NONE"

        Task {
            do {
                let client = try await buildMatrixClient(homeserverUrl: homeserver, ssStr: ssStr, userId: userId)
                try await restoreShadowSession(client: client, sessionData: sessionData)
                
                let encryption = client.encryption()
                try await encryption.recover(recoveryKey: recoveryKey) 

                sessionData["is_verified"] = "true"
                var sessions = sharedDefaults.dictionary(forKey: "shadow_sessions") as? [String: [String: String]] ?? [:]
                sessions[userId] = sessionData
                sharedDefaults.set(sessions, forKey: "shadow_sessions")

                call.resolve(["status": "shadow_verified"])
            } catch {
                call.reject("Failed to verify Shadow Device", nil, error)
            }
        }
    }

    @objc func getShadowStatus(_ call: CAPPluginCall) {
        guard let userId = call.getString("userId") else {
            return call.reject("Missing arguments")
        }
        
        if let sessionData = getShadowSession(userId: userId),
           let isVerified = sessionData["is_verified"], isVerified == "true" {
            call.resolve(["isVerified": true])
        } else {
            call.resolve(["isVerified": false])
        }
    }

  @objc func getActivePushUser(_ call: CAPPluginCall) {
        let sharedDefaults = UserDefaults(suiteName: appGroupId)!

        if let activePushUser = sharedDefaults.string(forKey: "active_push_user") {
            call.resolve(["activeUser": activePushUser])
        } else {
            call.resolve([:])
        }
    }


  @objc func setPreviewPreferences(_ call: CAPPluginCall) {
        let sharedDefaults = UserDefaults(suiteName: appGroupId)!
        let isEnabled = call.getBool("enabled", true)
        sharedDefaults.set(isEnabled, forKey: "show_previews")
        call.resolve()
    }
}

