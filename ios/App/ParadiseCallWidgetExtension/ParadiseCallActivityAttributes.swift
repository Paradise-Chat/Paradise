import Foundation
import ActivityKit
import AppIntents

@available(iOS 17.0, *)
public struct ParadiseCallActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable, Sendable {
        public var callerName: String
        public var isMuted: Bool
        public var hasVideo: Bool
        public var isConnected: Bool
        public var connectedAt: Date?

        public init(
            callerName: String,
            isMuted: Bool,
            hasVideo: Bool,
            isConnected: Bool,
            connectedAt: Date?
        ) {
            self.callerName = callerName
            self.isMuted = isMuted
            self.hasVideo = hasVideo
            self.isConnected = isConnected
            self.connectedAt = connectedAt
        }
    }

    public var roomId: String

    public init(roomId: String) {
        self.roomId = roomId
    }
}

@available(iOS 17.0, *)
public struct ToggleParadiseCallMuteIntent: LiveActivityIntent {
    public static var title: LocalizedStringResource = "Toggle Call Mute"
    public static var description = IntentDescription("Mutes or unmutes the active Paradise call.")
    public static var openAppWhenRun: Bool = false

    @Parameter(title: "Room ID")
    public var roomId: String

    public init() {
        self.roomId = ""
    }

    public init(roomId: String) {
        self.roomId = roomId
    }

    @MainActor
    public func perform() async throws -> some IntentResult {
        #if !WIDGET_EXTENSION
        CallKitManager.shared.toggleMuteFromLiveActivity(roomId: roomId)
        #endif
        return .result()
    }
}

@available(iOS 17.0, *)
public struct LeaveParadiseCallIntent: LiveActivityIntent {
    public static var title: LocalizedStringResource = "Leave Call"
    public static var description = IntentDescription("Leaves the active Paradise call.")
    public static var openAppWhenRun: Bool = false

    @Parameter(title: "Room ID")
    public var roomId: String

    public init() {
        self.roomId = ""
    }

    public init(roomId: String) {
        self.roomId = roomId
    }

    @MainActor
    public func perform() async throws -> some IntentResult {
        #if !WIDGET_EXTENSION
        CallKitManager.shared.endCallFromLiveActivity(roomId: roomId)
        #endif
        return .result()
    }
}
