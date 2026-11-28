import Foundation
import CallKit
import AVFoundation
import PushKit
import ActivityKit
import UIKit
import Capacitor

@objc public class CallKitManager: NSObject, CXProviderDelegate, PKPushRegistryDelegate {
    @objc public static let shared = CallKitManager()

    public struct CallRecord {
        public var uuid: UUID
        public var roomId: String
        public var callerName: String
        public var engine: String?
        public var hasVideo: Bool
        public var isMuted: Bool
        public var isConnected: Bool
        public var isStartFulfilled: Bool
        public var hasCallKitSession: Bool
        public var connectedAt: Date?
    }

    public struct PendingCallAction {
        public let action: String
        public let roomId: String
        public let engine: String?
        public let hasVideo: Bool
    }

    private var provider: CXProvider!
    private let callController = CXCallController()
    private var voipRegistry: PKPushRegistry?
    private let stateLock = NSLock()
    private let audioQueue = DispatchQueue(label: "com.gigiaj.paradise.call-audio", qos: .userInitiated)

    private var callsByRoom: [String: CallRecord] = [:]
    private var roomByUUID: [UUID: String] = [:]
    private var suppressedMuteUUIDs: Set<UUID> = []
    private var handoffCallUUIDs: Set<UUID> = []
    private var pendingDeactivationCallbacks: [() -> Void] = []
    private var pendingAction: PendingCallAction?
    private var backgroundTaskId: UIBackgroundTaskIdentifier = .invalid
    private var liveActivitiesByRoom: [String: Any] = [:]
    private var audioSessionConfigured = false
    private var pendingActivationCallbacks: [() -> Void] = []

    public private(set) var voipPushToken: String?
    public weak var plugin: ParadiseCallPlugin?

    public var hasActiveCall: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return !callsByRoom.isEmpty
    }

    private override init() {
        super.init()
        let config = CXProviderConfiguration()
        config.supportsVideo = true
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.includesCallsInRecents = false
        config.supportedHandleTypes = [.generic]

        provider = CXProvider(configuration: config)
        provider.setDelegate(self, queue: .main)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioSessionInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
    }

    @objc private func handleAudioSessionInterruption(_ notification: Notification) {
        guard hasActiveCall,
              let info = notification.userInfo,
              let typeVal = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeVal) else {
            return
        }
        if type == .ended {
            plugin?.notifyAudioActivated()
        }
    }

    @objc private func handleAudioRouteChange(_ notification: Notification) {
        guard hasActiveCall else { return }
        plugin?.notifyAudioActivated()
    }

    @objc public func startVoipPushRegistry() {
        cleanupOrphanedLiveActivities()
        if voipRegistry != nil { return }
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
        voipRegistry = registry
    }

    @objc public func beginTransitionBackgroundTask() {
        DispatchQueue.main.async {
            if self.backgroundTaskId != .invalid { return }
            self.backgroundTaskId = UIApplication.shared.beginBackgroundTask(withName: "ParadiseCallTransition") {
                if self.backgroundTaskId != .invalid {
                    UIApplication.shared.endBackgroundTask(self.backgroundTaskId)
                    self.backgroundTaskId = .invalid
                }
            }
        }
    }

    @objc public func endTransitionBackgroundTask() {
        DispatchQueue.main.async {
            if self.backgroundTaskId != .invalid {
                UIApplication.shared.endBackgroundTask(self.backgroundTaskId)
                self.backgroundTaskId = .invalid
            }
        }
    }

    private func cleanupOrphanedLiveActivities() {
        if #available(iOS 17.0, *) {
            if hasActiveCall { return }
            Task { @MainActor in
                for activity in Activity<ParadiseCallActivityAttributes>.activities {
                    await activity.end(nil, dismissalPolicy: .immediate)
                }
            }
        }
    }

    @objc public func refreshActiveLiveActivities() {
        stateLock.lock()
        let snapshots = Array(callsByRoom.values)
        stateLock.unlock()

        for snap in snapshots {
            startOrUpdateLiveActivity(for: snap)
        }
    }

    private func startOrUpdateLiveActivity(for rec: CallRecord, delayMs: UInt64 = 0) {
        if #available(iOS 17.0, *) {
            Task { @MainActor in
                if delayMs > 0 {
                    try? await Task.sleep(nanoseconds: delayMs * 1_000_000)
                }

                self.stateLock.lock()
                let latestRec = self.callsByRoom[rec.roomId]
                let cachedAny = self.liveActivitiesByRoom[rec.roomId]
                self.stateLock.unlock()

                guard let currentRec = latestRec else { return }

                guard ActivityAuthorizationInfo().areActivitiesEnabled else {
                    CAPLog.print("⚡️ [CallKitManager] Live Activities are disabled in iOS Settings")
                    return
                }

                let state = ParadiseCallActivityAttributes.ContentState(
                    callerName: currentRec.callerName,
                    isMuted: currentRec.isMuted,
                    hasVideo: currentRec.hasVideo,
                    isConnected: currentRec.isConnected,
                    connectedAt: currentRec.connectedAt
                )
                let content = ActivityContent(state: state, staleDate: nil)

                let existing = (cachedAny as? Activity<ParadiseCallActivityAttributes>)
                    ?? Activity<ParadiseCallActivityAttributes>.activities.first(where: {
                        $0.attributes.roomId == currentRec.roomId
                    })

                if let existing = existing {
                    self.stateLock.lock()
                    self.liveActivitiesByRoom[currentRec.roomId] = existing
                    self.stateLock.unlock()
                    await existing.update(content)
                    return
                }

                do {
                    let attrs = ParadiseCallActivityAttributes(roomId: currentRec.roomId)
                    let activity = try Activity.request(
                        attributes: attrs,
                        content: content,
                        pushType: nil
                    )
                    self.stateLock.lock()
                    self.liveActivitiesByRoom[currentRec.roomId] = activity
                    self.stateLock.unlock()
                    CAPLog.print("⚡️ [CallKitManager] Started Live Activity id=\(activity.id) for room=\(currentRec.roomId)")
                } catch {
                    CAPLog.print("⚡️ [CallKitManager] Live Activity start warning: \(error.localizedDescription)")
                }
            }
        }
    }

    private func endLiveActivity(roomId: String?) {
        if #available(iOS 17.0, *) {
            stateLock.lock()
            let targetActivity: Activity<ParadiseCallActivityAttributes>?
            if let rId = roomId, !rId.isEmpty {
                targetActivity = liveActivitiesByRoom.removeValue(forKey: rId) as? Activity<ParadiseCallActivityAttributes>
            } else {
                targetActivity = nil
                liveActivitiesByRoom.removeAll()
            }
            stateLock.unlock()

            Task { @MainActor in
                if let act = targetActivity {
                    await act.end(nil, dismissalPolicy: .immediate)
                }
                for act in Activity<ParadiseCallActivityAttributes>.activities {
                    if roomId == nil || roomId!.isEmpty || act.attributes.roomId == roomId {
                        await act.end(nil, dismissalPolicy: .immediate)
                    }
                }
            }
        }
    }

    public func toggleMuteFromLiveActivity(roomId: String) {
        stateLock.lock()
        let targetRoom = roomId.isEmpty ? callsByRoom.keys.first : roomId
        guard let rId = targetRoom, let rec = callsByRoom[rId] else {
            stateLock.unlock()
            return
        }
        let nextMuted = !rec.isMuted
        stateLock.unlock()

        setMuted(roomId: rId, muted: nextMuted)
        plugin?.notifyMuted(roomId: rId, muted: nextMuted)
    }

    public func endCallFromLiveActivity(roomId: String) {
        stateLock.lock()
        let targetRoom = roomId.isEmpty ? callsByRoom.keys.first : roomId
        stateLock.unlock()

        if let rId = targetRoom, !rId.isEmpty {
            deliverOrQueueDecline(roomId: rId)
            endCall(roomId: rId)
        } else {
            endLiveActivity(roomId: nil)
        }
    }

    private func flushPendingActivationCallbacks() {
        stateLock.lock()
        let callbacks = pendingActivationCallbacks
        pendingActivationCallbacks.removeAll()
        stateLock.unlock()
        for cb in callbacks {
            cb()
        }
    }

    private func configureAudioSessionIfNeeded(completion: (() -> Void)? = nil) {
        audioQueue.async { [weak self] in
            guard let self = self else {
                completion?()
                return
            }
            self.stateLock.lock()
            let alreadyConfigured = self.audioSessionConfigured
            if !alreadyConfigured {
                self.audioSessionConfigured = true
            }
            self.stateLock.unlock()

            if !alreadyConfigured {
                let session = AVAudioSession.sharedInstance()
                do {
                    let options: AVAudioSession.CategoryOptions = [
                        .allowBluetooth,
                        .allowBluetoothA2DP,
                        .defaultToSpeaker
                    ]
                    try session.setCategory(
                        .playAndRecord,
                        mode: .videoChat,
                        options: options
                    )
                    try session.setPreferredSampleRate(48000)
                    try session.setPreferredIOBufferDuration(0.01)
                } catch {
                    CAPLog.print("⚡️ [CallKitManager] AVAudioSession configure warning: \(error.localizedDescription)")
                }
            }
            completion?()
        }
    }

    public func consumePendingAction() -> PendingCallAction? {
        stateLock.lock()
        defer { stateLock.unlock() }
        let out = pendingAction
        pendingAction = nil
        return out
    }

    private func deliverOrQueueAnswer(roomId: String, engine: String?, hasVideo: Bool) {
        if let livePlugin = plugin, livePlugin.hasListeners("callAnswered") {
            livePlugin.notifyAnswered(roomId: roomId, engine: engine, hasVideo: hasVideo)
        } else {
            stateLock.lock()
            pendingAction = PendingCallAction(
                action: "answer",
                roomId: roomId,
                engine: engine,
                hasVideo: hasVideo
            )
            stateLock.unlock()
        }
    }

    private func deliverOrQueueDecline(roomId: String) {
        if let livePlugin = plugin, livePlugin.hasListeners("callDeclined") {
            livePlugin.notifyDeclined(roomId: roomId)
        } else {
            stateLock.lock()
            pendingAction = PendingCallAction(
                action: "decline",
                roomId: roomId,
                engine: nil,
                hasVideo: false
            )
            stateLock.unlock()
        }
    }

    private func flushPendingDeactivationCallbacks() {
        stateLock.lock()
        let callbacks = pendingDeactivationCallbacks
        pendingDeactivationCallbacks.removeAll()
        stateLock.unlock()
        for cb in callbacks {
            cb()
        }
    }

    public func startCall(
        roomId: String,
        callerName: String,
        hasVideo: Bool,
        engine: String?,
        completion: (() -> Void)? = nil
    ) {
        guard !roomId.isEmpty else {
            completion?()
            return
        }

        stateLock.lock()
        if let existing = callsByRoom[roomId] {
            stateLock.unlock()
            updateCallMetadata(
                uuid: existing.uuid,
                roomId: roomId,
                callerName: callerName,
                hasVideo: hasVideo,
                engine: engine
            )
            completion?()
            return
        }
        let uuid = UUID()
        let safeCaller = callerName.isEmpty ? "Paradise Call" : callerName
        let rec = CallRecord(
            uuid: uuid,
            roomId: roomId,
            callerName: safeCaller,
            engine: engine,
            hasVideo: hasVideo,
            isMuted: false,
            isConnected: false,
            isStartFulfilled: true,
            hasCallKitSession: false,
            connectedAt: nil
        )
        callsByRoom[roomId] = rec
        roomByUUID[uuid] = roomId
        stateLock.unlock()

        startOrUpdateLiveActivity(for: rec)
        completion?()
    }

    @objc public func reportConnected(roomId: String) {
        stateLock.lock()
        guard var rec = callsByRoom[roomId] else {
            stateLock.unlock()
            return
        }
        let wasConnected = rec.isConnected
        let now = rec.connectedAt ?? Date()
        rec.isConnected = true
        rec.connectedAt = now
        let startFulfilled = rec.isStartFulfilled
        let hasCK = rec.hasCallKitSession
        let uuid = rec.uuid
        callsByRoom[roomId] = rec
        let snapshot = rec
        stateLock.unlock()

        if hasCK && !wasConnected && startFulfilled {
            provider.reportOutgoingCall(with: uuid, connectedAt: now)
        }
        startOrUpdateLiveActivity(for: snapshot)
        endTransitionBackgroundTask()
    }

    @objc public func setMuted(roomId: String, muted: Bool) {
        stateLock.lock()
        guard var rec = callsByRoom[roomId] else {
            stateLock.unlock()
            return
        }
        let changed = (rec.isMuted != muted)
        let hasCK = rec.hasCallKitSession
        rec.isMuted = muted
        callsByRoom[roomId] = rec
        let uuid = rec.uuid
        let snapshot = rec
        if changed && hasCK {
            suppressedMuteUUIDs.insert(uuid)
        }
        stateLock.unlock()

        startOrUpdateLiveActivity(for: snapshot)

        guard changed && hasCK else { return }
        let action = CXSetMutedCallAction(call: uuid, muted: muted)
        callController.request(CXTransaction(action: action)) { error in
            if error != nil {
                self.stateLock.lock()
                self.suppressedMuteUUIDs.remove(uuid)
                self.stateLock.unlock()
            }
        }
    }

    @objc public func setVideoEnabled(roomId: String, enabled: Bool) {
        stateLock.lock()
        guard var rec = callsByRoom[roomId] else {
            stateLock.unlock()
            return
        }
        let hasCK = rec.hasCallKitSession
        rec.hasVideo = enabled
        callsByRoom[roomId] = rec
        let uuid = rec.uuid
        let snapshot = rec
        stateLock.unlock()

        if hasCK {
            let update = CXCallUpdate()
            update.hasVideo = enabled
            provider.reportCall(with: uuid, updated: update)
        }
        startOrUpdateLiveActivity(for: snapshot)
    }

    @discardableResult
    public func reportIncomingCall(
        roomId: String,
        callerName: String,
        hasVideo: Bool,
        engine: String?,
        completion: ((UUID, Error?) -> Void)? = nil
    ) -> UUID {
        stateLock.lock()
        if let existing = callsByRoom[roomId] {
            stateLock.unlock()
            updateCallMetadata(
                uuid: existing.uuid,
                roomId: roomId,
                callerName: callerName,
                hasVideo: hasVideo,
                engine: engine
            )
            completion?(existing.uuid, nil)
            return existing.uuid
        }

        let uuid = UUID()
        let safeCaller = callerName.isEmpty ? "Incoming Call" : callerName
        let rec = CallRecord(
            uuid: uuid,
            roomId: roomId,
            callerName: safeCaller,
            engine: engine,
            hasVideo: hasVideo,
            isMuted: false,
            isConnected: false,
            isStartFulfilled: true,
            hasCallKitSession: true,
            connectedAt: nil
        )
        callsByRoom[roomId] = rec
        roomByUUID[uuid] = roomId
        stateLock.unlock()

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: safeCaller)
        update.localizedCallerName = safeCaller
        update.hasVideo = hasVideo
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false

        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            if let error = error {
                print("[CallKitManager] reportNewIncomingCall failed: \(error.localizedDescription)")
                self.stateLock.lock()
                self.callsByRoom.removeValue(forKey: roomId)
                self.roomByUUID.removeValue(forKey: uuid)
                self.stateLock.unlock()
            }
            completion?(uuid, error)
        }
        return uuid
    }

    public func updateCallMetadata(
        uuid: UUID,
        roomId: String,
        callerName: String,
        hasVideo: Bool,
        engine: String?
    ) {
        let safeCaller = callerName.isEmpty ? "Paradise Call" : callerName
        stateLock.lock()
        if let oldRoom = roomByUUID[uuid], oldRoom != roomId {
            callsByRoom.removeValue(forKey: oldRoom)
        }
        var rec = callsByRoom[roomId] ?? CallRecord(
            uuid: uuid,
            roomId: roomId,
            callerName: safeCaller,
            engine: engine,
            hasVideo: hasVideo,
            isMuted: false,
            isConnected: false,
            isStartFulfilled: true,
            hasCallKitSession: false,
            connectedAt: nil
        )
        rec.roomId = roomId
        rec.callerName = safeCaller
        rec.hasVideo = hasVideo
        if let engine = engine, !engine.isEmpty {
            rec.engine = engine
        }
        callsByRoom[roomId] = rec
        roomByUUID[uuid] = roomId
        let snapshot = rec
        stateLock.unlock()

        if snapshot.hasCallKitSession {
            let update = CXCallUpdate()
            update.remoteHandle = CXHandle(type: .generic, value: safeCaller)
            update.localizedCallerName = safeCaller
            update.hasVideo = hasVideo
            provider.reportCall(with: uuid, updated: update)
        }

        if snapshot.isConnected {
            startOrUpdateLiveActivity(for: snapshot)
        }
    }

    @objc public func endCall(roomId: String) {
        stateLock.lock()
        let rec: CallRecord?
        if roomId.isEmpty {
            rec = callsByRoom.values.first
        } else {
            rec = callsByRoom[roomId]
        }
        guard let target = rec else {
            stateLock.unlock()
            endLiveActivity(roomId: roomId.isEmpty ? nil : roomId)
            return
        }
        let hadCK = target.hasCallKitSession
        callsByRoom.removeValue(forKey: target.roomId)
        roomByUUID.removeValue(forKey: target.uuid)
        suppressedMuteUUIDs.remove(target.uuid)
        handoffCallUUIDs.remove(target.uuid)
        stateLock.unlock()

        endLiveActivity(roomId: target.roomId)

        guard hadCK else { return }
        let action = CXEndCallAction(call: target.uuid)
        callController.request(CXTransaction(action: action)) { error in
            if error != nil {
                self.provider.reportCall(with: target.uuid, endedAt: Date(), reason: .remoteEnded)
            }
        }
    }

    public func providerDidReset(_ provider: CXProvider) {
        stateLock.lock()
        callsByRoom.removeAll()
        roomByUUID.removeAll()
        suppressedMuteUUIDs.removeAll()
        handoffCallUUIDs.removeAll()
        audioSessionConfigured = false
        stateLock.unlock()
        flushPendingActivationCallbacks()
        flushPendingDeactivationCallbacks()
        endLiveActivity(roomId: nil)
        endTransitionBackgroundTask()
    }

    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        stateLock.lock()
        let roomId = roomByUUID[action.callUUID]
        var alreadyConnectedAt: Date? = nil
        var snapshot: CallRecord? = nil
        if let rId = roomId, var rec = callsByRoom[rId] {
            rec.isStartFulfilled = true
            if rec.isConnected {
                alreadyConnectedAt = rec.connectedAt ?? Date()
            }
            callsByRoom[rId] = rec
            snapshot = rec
        }
        stateLock.unlock()

        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())
        action.fulfill()

        if let connectedAt = alreadyConnectedAt {
            provider.reportOutgoingCall(with: action.callUUID, connectedAt: connectedAt)
        }
        if let snap = snapshot {
            startOrUpdateLiveActivity(for: snap, delayMs: 150)
        }
    }

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        let now = Date()
        let answeredUUID = action.callUUID
        stateLock.lock()
        guard let roomId = roomByUUID[answeredUUID],
              var rec = callsByRoom[roomId] else {
            stateLock.unlock()
            action.fail()
            return
        }
        rec.isConnected = true
        rec.isStartFulfilled = true
        rec.hasCallKitSession = false
        rec.connectedAt = now
        callsByRoom[roomId] = rec
        handoffCallUUIDs.insert(answeredUUID)
        let engine = rec.engine
        let hasVideo = rec.hasVideo
        let snapshot = rec
        stateLock.unlock()

        beginTransitionBackgroundTask()
        startOrUpdateLiveActivity(for: snapshot)
        action.fulfill()

        var didDeliver = false
        let deliverOnce: () -> Void = { [weak self] in
            DispatchQueue.main.async {
                guard !didDeliver, let self = self else { return }
                didDeliver = true
                self.deliverOrQueueAnswer(roomId: roomId, engine: engine, hasVideo: hasVideo)
            }
        }
        stateLock.lock()
        pendingDeactivationCallbacks.append(deliverOnce)
        stateLock.unlock()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
            deliverOnce()
        }

        let releaseAction = CXEndCallAction(call: answeredUUID)
        callController.request(CXTransaction(action: releaseAction)) { error in
            if error != nil {
                self.provider.reportCall(with: answeredUUID, endedAt: Date(), reason: .answeredElsewhere)
                deliverOnce()
            }
        }
    }

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        stateLock.lock()
        let isHandoff = handoffCallUUIDs.remove(action.callUUID) != nil
        if isHandoff {
            stateLock.unlock()
            action.fulfill()
            return
        }
        let roomId = roomByUUID.removeValue(forKey: action.callUUID)
        if let rId = roomId {
            callsByRoom.removeValue(forKey: rId)
        }
        suppressedMuteUUIDs.remove(action.callUUID)
        if callsByRoom.isEmpty {
            audioSessionConfigured = false
        }
        stateLock.unlock()

        if let rId = roomId {
            endLiveActivity(roomId: rId)
            deliverOrQueueDecline(roomId: rId)
        } else {
            endLiveActivity(roomId: nil)
        }
        action.fulfill()
        endTransitionBackgroundTask()
    }

    public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        stateLock.lock()
        let wasSuppressed = suppressedMuteUUIDs.remove(action.callUUID) != nil
        let roomId = roomByUUID[action.callUUID]
        var updatedSnapshot: CallRecord? = nil
        if let rId = roomId, var rec = callsByRoom[rId] {
            rec.isMuted = action.isMuted
            callsByRoom[rId] = rec
            updatedSnapshot = rec
        }
        stateLock.unlock()

        if let snap = updatedSnapshot {
            startOrUpdateLiveActivity(for: snap)
        }
        if !wasSuppressed, let rId = roomId {
            plugin?.notifyMuted(roomId: rId, muted: action.isMuted)
        }
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        flushPendingActivationCallbacks()
    }

    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        stateLock.lock()
        audioSessionConfigured = false
        stateLock.unlock()
        flushPendingDeactivationCallbacks()
        endTransitionBackgroundTask()
    }

    public func pushRegistry(
        _ registry: PKPushRegistry,
        didUpdate pushCredentials: PKPushCredentials,
        for type: PKPushType
    ) {
        guard type == .voIP else { return }
        let token = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        voipPushToken = token
        plugin?.notifyVoipToken(token)
    }

    public func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        if type == .voIP {
            voipPushToken = nil
        }
    }

    public func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        guard type == .voIP else {
            completion()
            return
        }

        var payloadDict: [String: Any] = [:]
        for (k, v) in payload.dictionaryPayload {
            if let keyStr = k as? String {
                payloadDict[keyStr] = v
            }
        }

        let initialRoomId = (payloadDict["room_id"] as? String)
            ?? (payloadDict["channel_id"] as? String)
            ?? UUID().uuidString
        let initialTitle = (payloadDict["sender_display_name"] as? String)
            ?? (payloadDict["title"] as? String)
            ?? "Incoming Call"
        let initialEngine = payloadDict["engine"] as? String
        let initialVideo = (payloadDict["has_video"] as? Bool) ?? false
        let initialAction = payloadDict["call_action"] as? String

        if initialAction == "hangup" || initialAction == "cancel" {
            if hasActiveCall {
                endCall(roomId: initialRoomId)
                completion()
            } else {
                _ = reportIncomingCall(
                    roomId: initialRoomId,
                    callerName: initialTitle,
                    hasVideo: false,
                    engine: initialEngine
                ) { reportedUUID, _ in
                    self.provider.reportCall(with: reportedUUID, endedAt: Date(), reason: .unanswered)
                    self.endCall(roomId: initialRoomId)
                    completion()
                }
            }
            return
        }

        let callUUID = reportIncomingCall(
            roomId: initialRoomId,
            callerName: initialTitle,
            hasVideo: initialVideo,
            engine: initialEngine
        ) { _, _ in
            completion()
        }

        DispatchQueue.global(qos: .userInitiated).async {
            guard let resolved = ShadowDevicePlugin.evaluatePushNotification(payload: payloadDict) else {
                return
            }
            DispatchQueue.main.async {
                let action = (resolved["call_action"] as? String) ?? "invite"
                let finalRoom = (resolved["room_id"] as? String)
                    ?? (resolved["tag"] as? String)
                    ?? initialRoomId
                if action == "hangup" || action == "cancel" || (resolved["ignore"] as? Bool) == true {
                    self.endCall(roomId: finalRoom)
                    return
                }
                let finalName = (resolved["title"] as? String) ?? initialTitle
                let finalEngine = (resolved["engine"] as? String) ?? initialEngine
                let finalVideo = (resolved["has_video"] as? Bool) ?? initialVideo
                self.updateCallMetadata(
                    uuid: callUUID,
                    roomId: finalRoom,
                    callerName: finalName,
                    hasVideo: finalVideo,
                    engine: finalEngine
                )
            }
        }
    }
}
