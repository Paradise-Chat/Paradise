import Foundation
import UIKit
import WebKit
import Capacitor

class MyViewController: CAPBridgeViewController {
    private static let mediaCompatScript: String = """
    (function() {
      if (window.__paradiseIosMediaInstalled) return;
      window.__paradiseIosMediaInstalled = true;

      if (navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function') {
        var origGUM = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
        function extractDeviceId(val) {
          if (!val) return null;
          if (typeof val === 'string') return val;
          if (typeof val === 'object') return val.exact || val.ideal || null;
          return null;
        }
        navigator.mediaDevices.getUserMedia = function(constraints) {
          if (!constraints || typeof constraints !== 'object') {
            return origGUM(constraints);
          }
          if (constraints.audio && navigator.audioSession) {
            try { navigator.audioSession.type = 'play-and-record'; } catch (_) {}
          }
          var clean = Object.assign({}, constraints);
          var checkPromise = Promise.resolve(clean);
          if (clean.audio && typeof clean.audio === 'object' && navigator.mediaDevices.enumerateDevices) {
            var reqAudioId = extractDeviceId(clean.audio.deviceId);
            if (reqAudioId && reqAudioId !== 'default') {
              checkPromise = navigator.mediaDevices.enumerateDevices().then(function(devs) {
                var found = devs.some(function(d) {
                  return d.kind === 'audioinput' && d.deviceId === reqAudioId;
                });
                if (!found) {
                  var nextAudio = Object.assign({}, clean.audio);
                  delete nextAudio.deviceId;
                  clean.audio = nextAudio;
                }
                return clean;
              }).catch(function() { return clean; });
            }
          }
          return checkPromise.then(function(c) {
            return origGUM(c).catch(function(err) {
              if (err && (err.name === 'OverconstrainedError' || err.name === 'ConstraintNotSatisfiedError')) {
                var fallback = {};
                if (c.audio) {
                  fallback.audio = { echoCancellation: true, autoGainControl: true, noiseSuppression: false };
                }
                if (c.video) {
                  fallback.video = true;
                }
                return origGUM(fallback);
              }
              throw err;
            });
          });
        };
      }

      var AudioCtx = window.AudioContext || window.webkitAudioContext;
      var proto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
      var srcObjDesc = proto && Object.getOwnPropertyDescriptor(proto, 'srcObject');
      var mutedDesc = proto && Object.getOwnPropertyDescriptor(proto, 'muted');
      var volDesc = proto && Object.getOwnPropertyDescriptor(proto, 'volume');
      if (AudioCtx && srcObjDesc && srcObjDesc.set && mutedDesc && mutedDesc.set) {
        var sharedCtx = null;
        var activeBridges = new Set();
        var lastWallMs = 0;
        var lastCtxTime = -1;
        var stuckTicks = 0;

        function createFreshCtx() {
          var ctx = (typeof AudioCtx === 'function')
            ? (function() { try { return new AudioCtx({ sampleRate: 48000 }); } catch (_) { return new AudioCtx(); } })()
            : null;
          if (ctx) {
            ctx.onstatechange = function() {
              if (sharedCtx === ctx && activeBridges.size > 0 &&
                  (ctx.state === 'suspended' || ctx.state === 'interrupted')) {
                ctx.resume().catch(function() {});
              }
            };
          }
          return ctx;
        }

        function rebuildBridgesOnFreshCtx() {
          var oldCtx = sharedCtx;
          sharedCtx = createFreshCtx();
          lastWallMs = Date.now();
          lastCtxTime = -1;
          stuckTicks = 0;
          if (!sharedCtx) return;
          if (sharedCtx.state === 'suspended' || sharedCtx.state === 'interrupted') {
            sharedCtx.resume().catch(function() {});
          }
          activeBridges.forEach(function(bridge) {
            try { bridge.source.disconnect(); } catch (_) {}
            try { bridge.gain.disconnect(); } catch (_) {}
            try {
              var src = sharedCtx.createMediaStreamSource(bridge.stream);
              var gain = sharedCtx.createGain();
              var muted = Boolean(bridge.el.__paradiseVirtualMuted);
              var vol = (typeof bridge.el.__paradiseVirtualVol === 'number') ? bridge.el.__paradiseVirtualVol : 1;
              gain.gain.value = muted ? 0 : Math.max(0, Math.min(1, vol));
              src.connect(gain);
              gain.connect(sharedCtx.destination);
              bridge.source = src;
              bridge.gain = gain;
            } catch (_) {}
          });
          if (oldCtx && oldCtx !== sharedCtx) {
            try { oldCtx.close(); } catch (_) {}
          }
        }

        function ensureCtx() {
          if (!sharedCtx || sharedCtx.state === 'closed') {
            sharedCtx = createFreshCtx();
            lastWallMs = Date.now();
            lastCtxTime = -1;
            stuckTicks = 0;
          }
          if (sharedCtx && (sharedCtx.state === 'suspended' || sharedCtx.state === 'interrupted')) {
            sharedCtx.resume().catch(function() {});
          }
          return sharedCtx;
        }

        window.__paradiseResumeCallAudio = function() {
          if (sharedCtx && activeBridges.size > 0 &&
              (sharedCtx.state === 'suspended' || sharedCtx.state === 'interrupted')) {
            sharedCtx.resume().catch(function() {});
          }
        };

        function checkSharedAudioWatchdog() {
          if (!sharedCtx || activeBridges.size === 0) {
            lastWallMs = 0;
            lastCtxTime = -1;
            stuckTicks = 0;
            return;
          }
          if (sharedCtx.state === 'suspended' || sharedCtx.state === 'interrupted') {
            sharedCtx.resume().catch(function() {});
            return;
          }
          if (sharedCtx.state === 'running') {
            var nowWall = Date.now();
            var nowT = sharedCtx.currentTime;
            if (lastWallMs > 0 && (nowWall - lastWallMs) >= 1400) {
              if (lastCtxTime >= 0 && nowT === lastCtxTime) {
                stuckTicks++;
                if (stuckTicks === 2) {
                  sharedCtx.suspend().then(function() {
                    if (sharedCtx) return sharedCtx.resume();
                  }).catch(function() {});
                } else if (stuckTicks >= 4) {
                  rebuildBridgesOnFreshCtx();
                  return;
                }
              } else {
                stuckTicks = 0;
              }
            }
            lastWallMs = nowWall;
            lastCtxTime = nowT;
          }
        }

        ['touchstart', 'pointerdown', 'click'].forEach(function(ev) {
          window.addEventListener(ev, window.__paradiseResumeCallAudio, { passive: true, capture: true });
        });
        window.addEventListener('paradise:call-visibility', window.__paradiseResumeCallAudio);
        document.addEventListener('visibilitychange', window.__paradiseResumeCallAudio);
        setInterval(checkSharedAudioWatchdog, 1500);

        Object.defineProperty(proto, 'srcObject', {
          configurable: true,
          enumerable: srcObjDesc.enumerable,
          get: function() {
            return srcObjDesc.get.call(this);
          },
          set: function(stream) {
            srcObjDesc.set.call(this, stream);
            if (this.tagName !== 'AUDIO') return;
            if (!this.getAttribute || this.getAttribute('data-paradise-remote-audio') !== 'true') return;

            if (this.__paradiseAudioBridge) {
              activeBridges.delete(this.__paradiseAudioBridge);
              try { this.__paradiseAudioBridge.source.disconnect(); } catch (_) {}
              try { this.__paradiseAudioBridge.gain.disconnect(); } catch (_) {}
              this.__paradiseAudioBridge = null;
            }

            if (stream && typeof stream.getAudioTracks === 'function' && stream.getAudioTracks().length > 0) {
              try {
                var ctx = ensureCtx();
                var source = ctx.createMediaStreamSource(stream);
                var gain = ctx.createGain();
                var initVol = (typeof this.__paradiseVirtualVol === 'number')
                  ? this.__paradiseVirtualVol
                  : (volDesc && volDesc.get ? volDesc.get.call(this) : 1);
                var initMuted = (typeof this.__paradiseVirtualMuted === 'boolean')
                  ? this.__paradiseVirtualMuted
                  : (mutedDesc && mutedDesc.get ? mutedDesc.get.call(this) : false);
                this.__paradiseVirtualVol = initVol;
                this.__paradiseVirtualMuted = initMuted;
                this.__paradiseWebAudioRouted = true;
                gain.gain.value = initMuted ? 0 : Math.max(0, Math.min(1, initVol));
                source.connect(gain);
                gain.connect(ctx.destination);
                var bridge = { source: source, gain: gain, el: this, stream: stream };
                this.__paradiseAudioBridge = bridge;
                activeBridges.add(bridge);

                if (!this.__paradisePropsHooked) {
                  this.__paradisePropsHooked = true;
                  Object.defineProperty(this, 'muted', {
                    configurable: true,
                    get: function() { return Boolean(this.__paradiseVirtualMuted); },
                    set: function(m) {
                      this.__paradiseVirtualMuted = Boolean(m);
                      mutedDesc.set.call(this, true);
                      if (this.__paradiseAudioBridge) {
                        this.__paradiseAudioBridge.gain.gain.value =
                          this.__paradiseVirtualMuted ? 0 : Math.max(0, Math.min(1, this.__paradiseVirtualVol));
                      }
                    }
                  });
                  Object.defineProperty(this, 'volume', {
                    configurable: true,
                    get: function() { return this.__paradiseVirtualVol; },
                    set: function(v) {
                      var nv = Math.max(0, Math.min(1, Number(v) || 0));
                      this.__paradiseVirtualVol = nv;
                      if (this.__paradiseAudioBridge) {
                        this.__paradiseAudioBridge.gain.gain.value =
                          this.__paradiseVirtualMuted ? 0 : nv;
                      }
                    }
                  });
                }
                mutedDesc.set.call(this, true);
              } catch (_) {}
            }
          }
        });
      }
    })();
    """

    override open func webViewConfiguration(for instanceConfiguration: InstanceConfiguration) -> WKWebViewConfiguration {
        let config = super.webViewConfiguration(for: instanceConfiguration)
        config.allowsInlineMediaPlayback = true
        config.allowsAirPlayForMediaPlayback = true
        config.allowsPictureInPictureMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let userScript = WKUserScript(
            source: Self.mediaCompatScript,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        )
        config.userContentController.addUserScript(userScript)
        return config
    }

    fileprivate static var currentThemeColor = UIColor(
        red: 49.0 / 255.0,
        green: 51.0 / 255.0,
        blue: 56.0 / 255.0,
        alpha: 1.0
    )
    fileprivate static var currentInterfaceStyle: UIUserInterfaceStyle = .dark

    fileprivate func appMainWindow() -> UIWindow? {
        if let w = view.window, w.windowLevel == .normal {
            return w
        }
        if let delegateWin = UIApplication.shared.delegate?.window ?? nil,
           delegateWin.windowLevel == .normal {
            return delegateWin
        }
        return UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow && $0.windowLevel == .normal }
    }

    fileprivate func applyThemeChrome(color: UIColor, style: UIUserInterfaceStyle) {
        Self.currentThemeColor = color
        Self.currentInterfaceStyle = style
        overrideUserInterfaceStyle = style
        statusBarStyle = (style == .light) ? .darkContent : .lightContent
        if let wv = webView {
            wv.backgroundColor = color
            wv.scrollView.backgroundColor = color
            wv.overrideUserInterfaceStyle = style
            if wv.isFirstResponder {
                wv.reloadInputViews()
            }
        }
        if let mainWin = appMainWindow() {
            mainWin.backgroundColor = color
            mainWin.overrideUserInterfaceStyle = style
        }
        setNeedsStatusBarAppearanceUpdate()
    }

    override open func capacitorDidLoad() {
        super.capacitorDidLoad()
        bridge?.registerPluginInstance(ShadowDevicePlugin())
        bridge?.registerPluginInstance(ParadiseProxyPlugin())
        bridge?.registerPluginInstance(ParadiseCallPlugin())
        bridge?.registerPluginInstance(ParadiseUIPlugin())
    }

    override open func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        applyThemeChrome(color: Self.currentThemeColor, style: Self.currentInterfaceStyle)
    }
}

@objc(ParadiseUIPlugin)
public final class ParadiseUIPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ParadiseUIPlugin"
    public let jsName = "ParadiseUI"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "syncTheme", returnType: CAPPluginReturnPromise)
    ]

    fileprivate static func parseCssColor(_ raw: String?) -> UIColor? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else {
            return nil
        }
        if trimmed.hasPrefix("#") {
            let hex = String(trimmed.dropFirst())
            var rgb: UInt64 = 0
            guard Scanner(string: hex).scanHexInt64(&rgb) else { return nil }
            switch hex.count {
            case 3:
                let r = CGFloat((rgb >> 8) & 0xF) / 15.0
                let g = CGFloat((rgb >> 4) & 0xF) / 15.0
                let b = CGFloat(rgb & 0xF) / 15.0
                return UIColor(red: r, green: g, blue: b, alpha: 1.0)
            case 6:
                let r = CGFloat((rgb >> 16) & 0xFF) / 255.0
                let g = CGFloat((rgb >> 8) & 0xFF) / 255.0
                let b = CGFloat(rgb & 0xFF) / 255.0
                return UIColor(red: r, green: g, blue: b, alpha: 1.0)
            case 8:
                let r = CGFloat((rgb >> 24) & 0xFF) / 255.0
                let g = CGFloat((rgb >> 16) & 0xFF) / 255.0
                let b = CGFloat((rgb >> 8) & 0xFF) / 255.0
                let a = CGFloat(rgb & 0xFF) / 255.0
                return UIColor(red: r, green: g, blue: b, alpha: a)
            default:
                return nil
            }
        }
        if trimmed.lowercased().hasPrefix("rgb"),
           let open = trimmed.firstIndex(of: "("),
           let close = trimmed.lastIndex(of: ")"),
           open < close {
            let inner = trimmed[trimmed.index(after: open)..<close]
            let parts = inner.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            if parts.count >= 3,
               let r = Double(parts[0]),
               let g = Double(parts[1]),
               let b = Double(parts[2]) {
                let a = (parts.count >= 4 ? Double(parts[3]) : nil) ?? 1.0
                guard a > 0.01 else { return nil }
                return UIColor(
                    red: CGFloat(max(0.0, min(255.0, r)) / 255.0),
                    green: CGFloat(max(0.0, min(255.0, g)) / 255.0),
                    blue: CGFloat(max(0.0, min(255.0, b)) / 255.0),
                    alpha: 1.0
                )
            }
        }
        return nil
    }

    @objc func syncTheme(_ call: CAPPluginCall) {
        let colorStr = call.getString("color")
        let isDark = call.getBool("dark") ?? true
        let parsedColor = Self.parseCssColor(colorStr) ?? MyViewController.currentThemeColor
        let style: UIUserInterfaceStyle = isDark ? .dark : .light

        DispatchQueue.main.async { [weak self] in
            if let vc = self?.bridge?.viewController as? MyViewController {
                vc.applyThemeChrome(color: parsedColor, style: style)
            } else {
                MyViewController.currentThemeColor = parsedColor
                MyViewController.currentInterfaceStyle = style
                if let wv = self?.bridge?.webView {
                    wv.backgroundColor = parsedColor
                    wv.scrollView.backgroundColor = parsedColor
                    wv.overrideUserInterfaceStyle = style
                }
                if let mainWin = self?.bridge?.viewController?.view.window,
                   mainWin.windowLevel == .normal {
                    mainWin.backgroundColor = parsedColor
                    mainWin.overrideUserInterfaceStyle = style
                }
            }
            call.resolve(["ok": true])
        }
    }
}
