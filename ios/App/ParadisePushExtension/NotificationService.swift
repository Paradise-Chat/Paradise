import UserNotifications
import JavaScriptCore
import Security

class NotificationService: UNNotificationServiceExtension {

    var contentHandler: ((UNNotificationContent) -> Void)?
    var bestAttemptContent: UNMutableNotificationContent?
    let appGroupId = "group.com.gigiaj.paradise"

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        bestAttemptContent = (request.content.mutableCopy() as? UNMutableNotificationContent)

        guard let bestAttemptContent = bestAttemptContent else { return }

        let sharedDefaults = UserDefaults(suiteName: appGroupId)!

        let showPreviews = sharedDefaults.object(forKey: "show_previews") as? Bool ?? true

        if !showPreviews {
            bestAttemptContent.title = "New Message"
            bestAttemptContent.body = "Open the app to view."
            contentHandler(bestAttemptContent)
            return
        }


        let userInfo = request.content.userInfo
        guard let roomId = userInfo["room_id"] as? String,
              let eventId = userInfo["event_id"] as? String else {
            contentHandler(bestAttemptContent)
            return
        }


        guard let activeUserId = sharedDefaults.string(forKey: "active_push_user"),
              let sessions = sharedDefaults.dictionary(forKey: "shadow_sessions") as? [String: [String: String]],
              let sessionData = sessions[activeUserId],
              let homeserverUrl = sessionData["homeserver_url"] else {
            contentHandler(bestAttemptContent)
            return
        }

        let ssStr = sessionData["sliding_sync_version"] ?? "NONE"

        Task {
            do {
                guard let containerUrl = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupId) else { throw NSError() }

                let safeUserId = activeUserId.replacingOccurrences(of: "[^a-zA-Z0-9]", with: "_", options: .regularExpression)
                let dataPath = containerUrl.appendingPathComponent("matrix-shadow/\(safeUserId)/data").path
                let cachePath = containerUrl.appendingPathComponent("matrix-shadow/\(safeUserId)/cache").path

                let ssBuilder: SlidingSyncVersionBuilder = (ssStr == "NATIVE") ? .native : .none

                let client = try await ClientBuilder()
                    .homeserverUrl(url: homeserverUrl)
                    .sessionPaths(dataPath: dataPath, cachePath: cachePath)
                    .systemIsMemoryConstrained()
                    .slidingSyncVersionBuilder(versionBuilder: ssBuilder)
                    .build()

                try await restoreShadowSession(client: client, sessionData: sessionData)

                let pushClient = try await client.notificationClient(processSetup: .multipleProcesses)
                let status = try await pushClient.getNotification(roomId: roomId, eventId: eventId)

                switch status {
                case .event(let item):

                    let senderName = item.senderInfo.displayName ?? "Unknown Sender"

                    let rawRoom = item.roomInfo.displayName
                    let roomName = rawRoom.isEmpty ? "Unknown Room" : rawRoom

                    var messageBody = "New activity in \(roomName)"

                    switch item.event {
                    case .timeline(let timelineEvent):
                        do {
                            let content = try timelineEvent.content()
                            switch content {
                            case .messageLike(let messageLike):
                                switch messageLike {
                                case .roomMessage(let messageType, _):
                                    switch messageType {
                                    case .text(let textMsg): messageBody = textMsg.body
                                    case .image: messageBody = "Sent an image"
                                    case .video: messageBody = "Sent a video"
                                    case .audio: messageBody = "Sent an audio clip"
                                    case .file: messageBody = "Sent a file"
                                    case .emote(let emoteMsg): messageBody = "* \(senderName) \(emoteMsg.body)"
                                    case .notice(let noticeMsg): messageBody = noticeMsg.body
                                    default: messageBody = "Sent a message"
                                    }
                                case .reactionContent: messageBody = "Reacted to a message"
                                case .sticker: messageBody = "Sent a sticker"
                                case .callInvite: messageBody = "Incoming call"
                                default: messageBody = "Unhandled messageLike: \(String(describing: messageLike))"
                                }
                            case .state:
                                messageBody = "Room settings changed"
                            @unknown default:
                                messageBody = "Unknown content: \(String(describing: content))"
                            }
                        } catch {
                            messageBody = "Decryption error: \(error.localizedDescription)"
                        }
                    case .invite:
                        messageBody = "Invited you to the room"
                    @unknown default:
                        messageBody = "Unknown event type: \(String(describing: item.event))"
                    }

                    bestAttemptContent.title = senderName
                    bestAttemptContent.subtitle = roomName
                    bestAttemptContent.body = messageBody

                case .eventNotFound:
                    bestAttemptContent.body = "Open Paradise to view."
                case .eventFilteredOut, .eventRedacted:
                    break
                @unknown default:
                    break
                }

                contentHandler(bestAttemptContent)

                } catch {
                print("Paradise iOS Decryption Failed: \(error)")
                bestAttemptContent.body = "Open Paradise to view."
                contentHandler(bestAttemptContent)
            }
        }
    }

    private func restoreShadowSession(client: Client, sessionData: [String: String]) async throws {
        guard let accessToken = sessionData["access_token"],
              let userId = sessionData["user_id"],
              let deviceId = sessionData["device_id"],
              let homeserverUrl = sessionData["homeserver_url"] else {
            throw NSError(domain: "ShadowDevice", code: 2, userInfo: nil)
        }

        let slidingSyncStr = sessionData["sliding_sync_version"] ?? "NONE"
        let ssVersion: SlidingSyncVersion = (slidingSyncStr == "NATIVE") ? .native : .none

        let session = Session(
            accessToken: accessToken, refreshToken: nil, userId: userId,
            deviceId: deviceId, homeserverUrl: homeserverUrl, oidcData: nil,
            slidingSyncVersion: ssVersion
        )
        try await client.restoreSession(session: session)
    }

    override func serviceExtensionTimeWillExpire() {
        if let contentHandler = contentHandler, let bestAttemptContent = bestAttemptContent {
            bestAttemptContent.body = "Open Paradise to view (Encrypted)"
            contentHandler(bestAttemptContent)
        }
    }
}
