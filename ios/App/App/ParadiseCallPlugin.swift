import Foundation
import AVFoundation
import Capacitor

@objc(ParadiseCallPlugin)
public class ParadiseCallPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ParadiseCallPlugin"
    public let jsName = "ParadiseCall"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "requestMediaPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reportConnected", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setMuted", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setVideoEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reportIncomingCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "endCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPendingCallAction", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getVoipToken", returnType: CAPPluginReturnPromise)
    ]

    override public func load() {
        CallKitManager.shared.plugin = self
        CallKitManager.shared.startVoipPushRegistry()
        if let token = CallKitManager.shared.voipPushToken, !token.isEmpty {
            notifyVoipToken(token)
        }
    }

    @objc func requestMediaPermissions(_ call: CAPPluginCall) {
        let wantVideo = call.getBool("video", false)
        let finishWithMic: (Bool) -> Void = { micGranted in
            if !wantVideo {
                call.resolve([
                    "granted": micGranted,
                    "cameraGranted": AVCaptureDevice.authorizationStatus(for: .video) == .authorized
                ])
                return
            }
            AVCaptureDevice.requestAccess(for: .video) { camGranted in
                call.resolve([
                    "granted": micGranted,
                    "cameraGranted": camGranted
                ])
            }
        }
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission(completionHandler: finishWithMic)
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission(finishWithMic)
        }
    }

    private func setWebAudioSessionType(_ sessionType: String) {
        let js = "try{if(navigator.audioSession){navigator.audioSession.type='\(sessionType)';}}catch(_){}"
        bridge?.webView?.evaluateJavaScript(js, completionHandler: nil)
    }

    @objc func startCall(_ call: CAPPluginCall) {
        let roomId = call.getString("roomId") ?? ""
        if roomId.isEmpty {
            return call.reject("Missing roomId")
        }
        let name = call.getString("name") ?? "Paradise Call"
        let hasVideo = call.getBool("hasVideo", false)
        let engine = call.getString("engine")
        DispatchQueue.main.async {
            self.setWebAudioSessionType("play-and-record")
            CallKitManager.shared.startCall(
                roomId: roomId,
                callerName: name,
                hasVideo: hasVideo,
                engine: engine
            ) {
                call.resolve(["ok": true])
            }
        }
    }

    @objc func reportConnected(_ call: CAPPluginCall) {
        let roomId = call.getString("roomId") ?? ""
        DispatchQueue.main.async {
            if !roomId.isEmpty {
                CallKitManager.shared.reportConnected(roomId: roomId)
            }
            call.resolve(["ok": true])
        }
    }

    @objc func setMuted(_ call: CAPPluginCall) {
        let roomId = call.getString("roomId") ?? ""
        let muted = call.getBool("muted", false)
        DispatchQueue.main.async {
            if !roomId.isEmpty {
                CallKitManager.shared.setMuted(roomId: roomId, muted: muted)
            }
            call.resolve(["ok": true])
        }
    }

    @objc func setVideoEnabled(_ call: CAPPluginCall) {
        let roomId = call.getString("roomId") ?? ""
        let enabled = call.getBool("enabled", false)
        DispatchQueue.main.async {
            if !roomId.isEmpty {
                CallKitManager.shared.setVideoEnabled(roomId: roomId, enabled: enabled)
            }
            call.resolve(["ok": true])
        }
    }

    @objc func reportIncomingCall(_ call: CAPPluginCall) {
        let roomId = call.getString("roomId") ?? ""
        if roomId.isEmpty {
            return call.reject("Missing roomId")
        }
        let name = call.getString("name") ?? "Incoming Call"
        let hasVideo = call.getBool("hasVideo", false)
        let engine = call.getString("engine")
        DispatchQueue.main.async {
            _ = CallKitManager.shared.reportIncomingCall(
                roomId: roomId,
                callerName: name,
                hasVideo: hasVideo,
                engine: engine
            )
            call.resolve(["ok": true])
        }
    }

    @objc func endCall(_ call: CAPPluginCall) {
        let roomId = call.getString("roomId") ?? ""
        DispatchQueue.main.async {
            CallKitManager.shared.endCall(roomId: roomId)
            if !CallKitManager.shared.hasActiveCall {
                self.setWebAudioSessionType("auto")
            }
            call.resolve(["ok": true])
        }
    }

    @objc func getPendingCallAction(_ call: CAPPluginCall) {
        if let pending = CallKitManager.shared.consumePendingAction() {
            var out: [String: Any] = [
                "action": pending.action,
                "roomId": pending.roomId,
                "hasVideo": pending.hasVideo
            ]
            if let engine = pending.engine, !engine.isEmpty {
                out["engine"] = engine
            }
            call.resolve(out)
        } else {
            call.resolve(["action": NSNull()])
        }
    }

    @objc func getVoipToken(_ call: CAPPluginCall) {
        if let tok = CallKitManager.shared.voipPushToken, !tok.isEmpty {
            call.resolve(["token": tok])
        } else {
            call.resolve(["token": NSNull()])
        }
    }

    public func notifyAnswered(roomId: String, engine: String?, hasVideo: Bool) {
        DispatchQueue.main.async {
            self.setWebAudioSessionType("play-and-record")
        }
        var data: [String: Any] = [
            "roomId": roomId,
            "hasVideo": hasVideo
        ]
        if let engine = engine, !engine.isEmpty {
            data["engine"] = engine
        }
        notifyListeners("callAnswered", data: data, retainUntilConsumed: true)
    }

    public func notifyDeclined(roomId: String) {
        notifyListeners("callDeclined", data: ["roomId": roomId], retainUntilConsumed: true)
    }

    public func notifyMuted(roomId: String, muted: Bool) {
        notifyListeners("callMuted", data: ["roomId": roomId, "muted": muted], retainUntilConsumed: true)
    }

    public func notifyVoipToken(_ token: String) {
        notifyListeners("voipTokenReceived", data: ["token": token], retainUntilConsumed: true)
    }

    public func notifyAudioActivated() {
        let wakeJs =
            "(function(){try{" +
            "if(window.__paradiseResumeCallAudio)window.__paradiseResumeCallAudio();" +
            "window.dispatchEvent(new CustomEvent('paradise:call-visibility',{detail:{visible:!document.hidden}}));" +
            "document.querySelectorAll('audio,video').forEach(function(el){" +
            "if(el.__paradiseAudioBridge)return;" +
            "if(el.paused&&(el.srcObject||el.autoplay)){el.play().catch(function(){});}" +
            "});}catch(_){}})();"
        let fireWake: () -> Void = { [weak self] in
            guard let self = self else { return }
            self.notifyListeners("callAudioActivated", data: ["ok": true])
            self.bridge?.webView?.evaluateJavaScript(wakeJs, completionHandler: nil)
        }
        DispatchQueue.main.async {
            fireWake()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                fireWake()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.85) {
                fireWake()
            }
        }
    }
}
