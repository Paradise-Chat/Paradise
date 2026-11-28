import Foundation
import UIKit
import WebKit
import Network
import Security
import Capacitor

private let paradiseSafeRedirectHeaders: Set<String> = [
    "accept",
    "accept-language",
    "accept-encoding",
    "user-agent",
    "range",
    "if-range"
]

private func paradiseExactOrigin(of url: URL) -> String? {
    guard let scheme = url.scheme?.lowercased(),
          (scheme == "http" || scheme == "https"),
          let host = url.host?.lowercased(), !host.isEmpty else {
        return nil
    }
    let defaultPort = (scheme == "https") ? 443 : 80
    let port = url.port ?? defaultPort
    return (port == defaultPort) ? "\(scheme)://\(host)" : "\(scheme)://\(host):\(port)"
}

private final class ParadiseRedirectGuard: NSObject, URLSessionTaskDelegate {
    private let cookieLock = NSLock()
    private var redirectCookiesByTask: [Int: [HTTPCookie]] = [:]

    func takeRedirectCookies(for taskIdentifier: Int) -> [HTTPCookie] {
        cookieLock.lock()
        let cookies = redirectCookiesByTask.removeValue(forKey: taskIdentifier) ?? []
        cookieLock.unlock()
        return cookies
    }

    private func mergeCookieHeader(existing: String?, cookies: [HTTPCookie]) -> String? {
        var pairs: [String: String] = [:]
        var order: [String] = []
        if let existing = existing, !existing.isEmpty {
            for part in existing.components(separatedBy: ";") {
                if let eq = part.firstIndex(of: "=") {
                    let name = String(part[..<eq]).trimmingCharacters(in: .whitespaces)
                    let val = String(part[part.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
                    if !name.isEmpty {
                        if pairs[name] == nil { order.append(name) }
                        pairs[name] = val
                    }
                }
            }
        }
        for c in cookies where !c.name.isEmpty {
            if pairs[c.name] == nil { order.append(c.name) }
            pairs[c.name] = c.value
        }
        guard !order.isEmpty else { return nil }
        return order.compactMap { name in pairs[name].map { "\(name)=\($0)" } }.joined(separator: "; ")
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let nextUrl = request.url,
              let nextScheme = nextUrl.scheme?.lowercased(),
              nextScheme == "http" || nextScheme == "https" else {
            completionHandler(nil)
            return
        }

        let currentUrl = response.url ?? task.currentRequest?.url ?? task.originalRequest?.url
        let currentOrigin = currentUrl.flatMap(paradiseExactOrigin(of:))
        let nextOrigin = paradiseExactOrigin(of: nextUrl)
        let sameOrigin = (currentOrigin != nil && currentOrigin == nextOrigin)

        let existingHeaders = request.allHTTPHeaderFields ?? [:]
        let hasSensitiveHeaders = existingHeaders.keys.contains { key in
            let lower = key.lowercased()
            return !paradiseSafeRedirectHeaders.contains(lower) &&
                lower != "content-type" &&
                lower != "referer" &&
                lower != "origin"
        }

        if currentUrl?.scheme?.lowercased() == "https",
           nextScheme == "http",
           hasSensitiveHeaders {
            completionHandler(nil)
            return
        }

        var sanitized = request
        sanitized.httpShouldHandleCookies = false
        if sameOrigin, let currUrl = currentUrl {
            var strFields: [String: String] = [:]
            for (k, v) in response.allHeaderFields {
                strFields[String(describing: k)] = String(describing: v)
            }
            let hopCookies = HTTPCookie.cookies(withResponseHeaderFields: strFields, for: currUrl)
            if !hopCookies.isEmpty {
                cookieLock.lock()
                var arr = redirectCookiesByTask[task.taskIdentifier] ?? []
                arr.append(contentsOf: hopCookies)
                redirectCookiesByTask[task.taskIdentifier] = arr
                cookieLock.unlock()
                let existingCookie = existingHeaders.first(where: { $0.key.lowercased() == "cookie" })?.value
                if let merged = mergeCookieHeader(existing: existingCookie, cookies: hopCookies) {
                    sanitized.setValue(merged, forHTTPHeaderField: "Cookie")
                }
            }
        }
        if !sameOrigin {
            if (response.statusCode == 307 || response.statusCode == 308) &&
                (sanitized.httpBody != nil || sanitized.httpBodyStream != nil) {
                completionHandler(nil)
                return
            }
            for key in existingHeaders.keys {
                if !paradiseSafeRedirectHeaders.contains(key.lowercased()) {
                    sanitized.setValue(nil, forHTTPHeaderField: key)
                }
            }
        }
        completionHandler(sanitized)
    }
}

private final class ParadiseAuthSheetController: UIViewController, WKNavigationDelegate {
    let webView: WKWebView
    var onDismissed: (() -> Void)?
    var onCaptured: ((String, String) -> Void)?
    var callbackPrefix: String?
    var closeOnPrefix: String?
    var redirectMatchPrefix: String?
    var redirectTargetUrl: URL?

    private var urlObservation: NSKeyValueObservation?
    private var didRedirect = false
    private var didCapture = false

    init(initialTitle: String) {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config)
        view.translatesAutoresizingMaskIntoConstraints = false
        view.isOpaque = false
        let bg = UIColor(red: 30.0 / 255.0, green: 31.0 / 255.0, blue: 34.0 / 255.0, alpha: 1.0)
        view.backgroundColor = bg
        view.scrollView.backgroundColor = bg
        self.webView = view
        super.init(nibName: nil, bundle: nil)
        self.title = initialTitle
        self.webView.navigationDelegate = self
        self.urlObservation = self.webView.observe(\.url, options: [.new]) { [weak self] _, change in
            guard let self = self, let newUrl = change.newValue ?? nil else { return }
            _ = self.handleNavigationUrl(newUrl, isPolicyCheck: false)
        }
    }

    deinit {
        urlObservation?.invalidate()
        urlObservation = nil
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let bg = UIColor(red: 30.0 / 255.0, green: 31.0 / 255.0, blue: 34.0 / 255.0, alpha: 1.0)
        view.backgroundColor = bg

        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "Close",
            style: .done,
            target: self,
            action: #selector(handleCloseTapped)
        )

        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isBeingDismissed || navigationController?.isBeingDismissed == true {
            webView.stopLoading()
            onDismissed?()
        }
    }

    @objc private func handleCloseTapped() {
        webView.stopLoading()
        dismiss(animated: true) { [weak self] in
            self?.onDismissed?()
        }
    }

    private func handleNavigationUrl(_ targetUrl: URL, isPolicyCheck: Bool) -> Bool {
        let urlStr = targetUrl.absoluteString
        if !didRedirect,
           let matchPfx = redirectMatchPrefix,
           let redirectTo = redirectTargetUrl,
           urlStr.hasPrefix(matchPfx) {
            didRedirect = true
            DispatchQueue.main.async { [weak self] in
                self?.webView.load(URLRequest(url: redirectTo))
            }
            return isPolicyCheck
        }

        if !didCapture, let cbPfx = callbackPrefix, urlStr.hasPrefix(cbPfx) {
            didCapture = true
            webView.stopLoading()
            onCaptured?("callback", urlStr)
            return true
        }

        if !didCapture, let closePfx = closeOnPrefix, urlStr.hasPrefix(closePfx) {
            didCapture = true
            webView.stopLoading()
            onCaptured?("completed", urlStr)
            return true
        }

        return false
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let targetUrl = navigationAction.request.url,
              let scheme = targetUrl.scheme?.lowercased() else {
            decisionHandler(.cancel)
            return
        }
        if scheme == "http" || scheme == "https" {
            if handleNavigationUrl(targetUrl, isPolicyCheck: true) {
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        } else if scheme == "about" || scheme == "blob" {
            decisionHandler(.allow)
        } else {
            UIApplication.shared.open(targetUrl, options: [:], completionHandler: nil)
            decisionHandler(.cancel)
        }
    }
}

@objc(ParadiseProxyPlugin)
public class ParadiseProxyPlugin: CAPPlugin, CAPBridgedPlugin {

    public let identifier = "ParadiseProxyPlugin"
    public let jsName = "ParadiseProxy"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "isSupported", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "request", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "prepareStream", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearStream", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "prepareFrame", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearFrame", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "prepareUpload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openAuthWindow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "closeAuthWindow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openExternal", returnType: CAPPluginReturnPromise)
    ]

    private static let defaultDesktopUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15"

    private let redirectGuard = ParadiseRedirectGuard()
    private lazy var urlSession: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpShouldSetCookies = false
        cfg.httpCookieAcceptPolicy = .never
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.timeoutIntervalForRequest = 30
        cfg.timeoutIntervalForResource = 90
        cfg.httpAdditionalHeaders = [
            "User-Agent": Self.defaultDesktopUserAgent,
            "Accept-Language": "en-US,en;q=0.9"
        ]
        return URLSession(configuration: cfg, delegate: redirectGuard, delegateQueue: nil)
    }()

    private enum RouteKind {
        case stream
        case frame
        case embed
        case upload
    }

    private struct RouteGrant {
        let kind: RouteKind
        let allowedOrigin: String
        let headers: [String: String]
        let createdAt: TimeInterval
    }

    private static let maxRouteGrants = 256
    private static let forbiddenFramingHeaders: Set<String> = [
        "content-length",
        "transfer-encoding",
        "host"
    ]

    private let rulesLock = NSLock()
    private var streamHeaderRules: [String: [String: String]] = [:]
    private var frameHeaderRules: [String: [String: String]] = [:]
    private var routeGrants: [String: RouteGrant] = [:]
    private let streamQueue = DispatchQueue(label: "com.gigiaj.paradise.stream-proxy", qos: .userInitiated)
    private var streamListener: NWListener?
    private var streamPort: UInt16 = 0

    private weak var activeAuthNav: UINavigationController?
    private weak var activeAuthController: ParadiseAuthSheetController?
    private var pendingAuthCall: CAPPluginCall?
    private var pendingAuthMode: String = "window"

    private func newGrantToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    private func registerRouteGrantLocked(kind: RouteKind, origin: String, headers: [String: String]) -> String {
        if routeGrants.count >= Self.maxRouteGrants,
           let oldestKey = routeGrants.min(by: { $0.value.createdAt < $1.value.createdAt })?.key {
            routeGrants.removeValue(forKey: oldestKey)
        }
        let token = newGrantToken()
        routeGrants[token] = RouteGrant(
            kind: kind,
            allowedOrigin: origin,
            headers: headers,
            createdAt: Date().timeIntervalSince1970
        )
        return token
    }

    private func sanitizeHeaderValue(_ raw: String) -> String {
        return raw.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "")
    }

    private func sanitizeHeaderDict(_ rawHdrs: [String: Any]) -> [String: String] {
        var out: [String: String] = [:]
        for (rawKey, rawVal) in rawHdrs {
            if rawVal is NSNull { continue }
            let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
            let val = String(describing: rawVal)
            if key.isEmpty || key.contains("\r") || key.contains("\n") || val.contains("\r") || val.contains("\n") {
                continue
            }
            let lower = key.lowercased()
            if Self.forbiddenFramingHeaders.contains(lower) { continue }
            for existing in out.keys where existing.lowercased() == lower {
                out.removeValue(forKey: existing)
            }
            out[key] = val
        }
        return out
    }

    private func allowedCorsOrigin(_ clientOrigin: String?) -> String? {
        guard let raw = clientOrigin?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty,
              !raw.contains("\r"),
              !raw.contains("\n") else {
            return nil
        }
        let lower = raw.lowercased()
        let loopback = streamPort > 0 ? "http://localhost:\(streamPort)" : nil
        if lower == "capacitor://localhost" ||
           lower == "https://localhost" ||
           lower == "http://localhost" ||
           (loopback != nil && lower == loopback) {
            return raw
        }
        return nil
    }

    @objc func isSupported(_ call: CAPPluginCall) {
        call.resolve([
            "supported": true,
            "provider": "capacitor"
        ])
    }

    @objc func request(_ call: CAPPluginCall) {
        let rawUrl = (call.getString("url") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = validateHttpUrl(rawUrl) else {
            call.reject("Invalid or unsupported URL")
            return
        }

        let method = (call.getString("method") ?? "GET")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        let headersObj = call.getObject("headers") ?? [:]
        let bodyStr = call.getString("body")
        let bodyEncoding = (call.getString("bodyEncoding") ?? "utf8").lowercased()

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = method
        urlRequest.httpShouldHandleCookies = false

        for (key, val) in sanitizeHeaderDict(headersObj) {
            urlRequest.setValue(val, forHTTPHeaderField: key)
        }

        if let bodyStr = bodyStr, method != "GET" && method != "HEAD" {
            if bodyEncoding == "base64" {
                let cleaned = bodyStr.components(separatedBy: .whitespacesAndNewlines).joined()
                guard let decoded = Data(base64Encoded: cleaned) else {
                    call.reject("Invalid base64 request body")
                    return
                }
                urlRequest.httpBody = decoded
            } else {
                urlRequest.httpBody = bodyStr.data(using: .utf8)
            }
        }

        var taskRef: URLSessionDataTask?
        let task = urlSession.dataTask(with: urlRequest) { data, response, error in
            if let tid = taskRef?.taskIdentifier {
                _ = self.redirectGuard.takeRedirectCookies(for: tid)
            }
            if let error = error {
                call.reject(error.localizedDescription, nil, error)
                return
            }

            let httpResp = response as? HTTPURLResponse
            let status = httpResp?.statusCode ?? 200
            let finalUrl = httpResp?.url?.absoluteString ?? url.absoluteString

            var outHeaders: [String: String] = [:]
            if let fields = httpResp?.allHeaderFields {
                for (k, v) in fields {
                    let lower = String(describing: k).lowercased()
                    outHeaders[lower] = String(describing: v)
                }
            }

            let payloadData = (method == "HEAD") ? Data() : (data ?? Data())
            call.resolve([
                "ok": (200...299).contains(status),
                "status": status,
                "url": finalUrl,
                "headers": outHeaders,
                "data": payloadData.base64EncodedString()
            ])
        }
        taskRef = task
        task.resume()
    }

    @objc func openExternal(_ call: CAPPluginCall) {
        let rawUrl = (call.getString("url") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = validateHttpUrl(rawUrl) else {
            call.reject("Invalid or unsupported external URL")
            return
        }

        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:]) { success in
                if success {
                    call.resolve(["ok": true])
                } else {
                    call.reject("Failed to open external URL")
                }
            }
        }
    }

    private func settlePendingAuthCall(_ payload: [String: Any]) {
        if let pending = self.pendingAuthCall {
            self.pendingAuthCall = nil
            pending.resolve(payload)
        }
    }

    @objc func openAuthWindow(_ call: CAPPluginCall) {
        let rawUrl = (call.getString("url") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = validateHttpUrl(rawUrl) else {
            call.reject("Invalid or unsupported auth URL")
            return
        }

        let title = call.getString("title") ?? "Sign In"
        let external = call.getBool("external") ?? false

        let rawCb = (call.getString("callbackPrefix") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let callbackPrefix = validateHttpUrl(rawCb) != nil ? rawCb : nil

        let rawClose = (call.getString("closeOnPrefix") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let closeOnPrefix = validateHttpUrl(rawClose) != nil ? rawClose : nil

        let redirectObj = call.getObject("redirectOnPrefix")
        let rawRedirMatch = (redirectObj?["matchPrefix"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let rawRedirTarget = (redirectObj?["targetUrl"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let validRedirMatch = validateHttpUrl(rawRedirMatch) != nil ? rawRedirMatch : nil
        let validRedirTarget = validateHttpUrl(rawRedirTarget)
        let hasRedirectRule = (validRedirMatch != nil && validRedirTarget != nil)
        let awaitingNav = (callbackPrefix != nil || closeOnPrefix != nil)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            if external {
                UIApplication.shared.open(url, options: [:]) { success in
                    if success {
                        call.resolve(["ok": true, "mode": "external"])
                    } else {
                        call.reject("Failed to open external browser")
                    }
                }
                return
            }

            if !awaitingNav && !hasRedirectRule,
               let existingAuth = self.activeAuthController,
               let existingNav = self.activeAuthNav,
               existingNav.presentingViewController != nil {
                existingAuth.title = title
                existingAuth.webView.load(URLRequest(url: url))
                call.resolve(["ok": true, "mode": "window"])
                return
            }

            let presentNewSheet = { [weak self] in
                guard let self = self else { return }
                guard let presenter = self.topPresenterViewController() else {
                    call.reject("Unable to locate presenting view controller")
                    return
                }

                let authVC = ParadiseAuthSheetController(initialTitle: title)
                authVC.callbackPrefix = callbackPrefix
                authVC.closeOnPrefix = closeOnPrefix
                if hasRedirectRule {
                    authVC.redirectMatchPrefix = validRedirMatch
                    authVC.redirectTargetUrl = validRedirTarget
                }

                if awaitingNav {
                    self.pendingAuthCall = call
                    self.pendingAuthMode = callbackPrefix != nil ? "callback" : "completed"
                }

                authVC.onCaptured = { [weak self] mode, capturedUrl in
                    DispatchQueue.main.async {
                        guard let self = self else { return }
                        let navToDismiss = self.activeAuthNav
                        self.activeAuthController = nil
                        self.activeAuthNav = nil
                        let finish = {
                            self.settlePendingAuthCall([
                                "ok": true,
                                "mode": mode,
                                "callbackUrl": capturedUrl
                            ])
                        }
                        if let nav = navToDismiss {
                            nav.dismiss(animated: true, completion: finish)
                        } else {
                            finish()
                        }
                    }
                }

                authVC.onDismissed = { [weak self] in
                    guard let self = self else { return }
                    self.activeAuthController = nil
                    self.activeAuthNav = nil
                    self.settlePendingAuthCall([
                        "ok": false,
                        "mode": self.pendingAuthMode,
                        "cancelled": true
                    ])
                }

                let nav = UINavigationController(rootViewController: authVC)
                nav.modalPresentationStyle = .pageSheet
                let navBarAppearance = UINavigationBarAppearance()
                navBarAppearance.configureWithOpaqueBackground()
                navBarAppearance.backgroundColor = UIColor(
                    red: 30.0 / 255.0,
                    green: 31.0 / 255.0,
                    blue: 34.0 / 255.0,
                    alpha: 1.0
                )
                navBarAppearance.titleTextAttributes = [.foregroundColor: UIColor.white]
                nav.navigationBar.standardAppearance = navBarAppearance
                nav.navigationBar.scrollEdgeAppearance = navBarAppearance
                nav.navigationBar.tintColor = UIColor(
                    red: 148.0 / 255.0,
                    green: 155.0 / 255.0,
                    blue: 164.0 / 255.0,
                    alpha: 1.0
                )

                self.activeAuthController = authVC
                self.activeAuthNav = nav

                presenter.present(nav, animated: true) {
                    authVC.webView.load(URLRequest(url: url))
                    if !awaitingNav {
                        call.resolve(["ok": true, "mode": "window"])
                    }
                }
            }

            if let existingNav = self.activeAuthNav {
                self.activeAuthController?.onDismissed = nil
                self.activeAuthController?.onCaptured = nil
                self.activeAuthController?.webView.stopLoading()
                self.activeAuthController = nil
                self.activeAuthNav = nil
                self.settlePendingAuthCall([
                    "ok": false,
                    "mode": self.pendingAuthMode,
                    "cancelled": true
                ])
                existingNav.dismiss(animated: false, completion: presentNewSheet)
            } else {
                presentNewSheet()
            }
        }
    }

    @objc func closeAuthWindow(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                call.resolve(["ok": true])
                return
            }
            self.settlePendingAuthCall([
                "ok": false,
                "mode": self.pendingAuthMode,
                "cancelled": true
            ])
            if let nav = self.activeAuthNav {
                self.activeAuthController?.webView.stopLoading()
                self.activeAuthController = nil
                self.activeAuthNav = nil
                nav.dismiss(animated: true) {
                    call.resolve(["ok": true])
                }
            } else {
                call.resolve(["ok": true])
            }
        }
    }

    @objc func prepareStream(_ call: CAPPluginCall) {
        let rawTarget = (call.getString("url") ?? call.getString("urlOrHost") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let validUrl = validateHttpUrl(rawTarget),
              let origin = paradiseExactOrigin(of: validUrl) else {
            call.resolve(["ok": false, "url": rawTarget])
            return
        }

        let rawHdrs = call.getObject("headers") ?? call.getObject("requestHeaders") ?? [:]
        let hdrMap = sanitizeHeaderDict(rawHdrs)

        rulesLock.lock()
        streamHeaderRules[origin] = hdrMap
        let grantToken = registerRouteGrantLocked(kind: .stream, origin: origin, headers: hdrMap)
        rulesLock.unlock()

        if let port = ensureStreamListener(),
           var comps = URLComponents() as URLComponents? {
            comps.scheme = "http"
            comps.host = "localhost"
            comps.port = Int(port)
            comps.path = "/stream/\(grantToken)"
            comps.queryItems = [URLQueryItem(name: "url", value: validUrl.absoluteString)]
            if let loopbackUrl = comps.url?.absoluteString {
                call.resolve(["ok": true, "url": loopbackUrl])
                return
            }
        }

        call.resolve(["ok": true, "url": rawTarget])
    }

    @objc func clearStream(_ call: CAPPluginCall) {
        let target = (call.getString("urlOrHost") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        rulesLock.lock()
        clearOriginRulesLocked(rules: &streamHeaderRules, kinds: [.stream], target: target)
        rulesLock.unlock()
        call.resolve(["ok": true])
    }

    @objc func prepareFrame(_ call: CAPPluginCall) {
        let rawTarget = (call.getString("url") ?? call.getString("urlOrHost") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let validUrl = validateHttpUrl(rawTarget),
              let origin = paradiseExactOrigin(of: validUrl) else {
            call.resolve(["ok": false, "url": rawTarget])
            return
        }

        let isEmbedRelay = call.getBool("embed") ?? false
        let rawHdrs = call.getObject("headers") ?? call.getObject("requestHeaders") ?? [:]
        let hdrMap = sanitizeHeaderDict(rawHdrs)

        rulesLock.lock()
        frameHeaderRules[origin] = hdrMap
        let kind: RouteKind = isEmbedRelay ? .embed : .frame
        let grantToken = registerRouteGrantLocked(kind: kind, origin: origin, headers: hdrMap)
        rulesLock.unlock()

        if let port = ensureStreamListener(),
           var comps = URLComponents() as URLComponents? {
            comps.scheme = "http"
            comps.host = "localhost"
            comps.port = Int(port)
            comps.path = isEmbedRelay ? "/embed/\(grantToken)" : "/frame/\(grantToken)"
            comps.queryItems = [URLQueryItem(name: "url", value: validUrl.absoluteString)]
            if let loopbackUrl = comps.url?.absoluteString {
                call.resolve([
                    "ok": true,
                    "url": loopbackUrl,
                    "mode": isEmbedRelay ? "embed-relay" : "loopback"
                ])
                return
            }
        }

        call.resolve(["ok": true, "url": rawTarget, "mode": "headers"])
    }

    @objc func clearFrame(_ call: CAPPluginCall) {
        let target = (call.getString("urlOrHost") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        rulesLock.lock()
        clearOriginRulesLocked(rules: &frameHeaderRules, kinds: [.frame, .embed], target: target)
        rulesLock.unlock()
        call.resolve(["ok": true])
    }

    @objc func prepareUpload(_ call: CAPPluginCall) {
        let rawTarget = (call.getString("url") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let validUrl = validateHttpUrl(rawTarget),
              let origin = paradiseExactOrigin(of: validUrl) else {
            call.resolve(["ok": false, "url": rawTarget])
            return
        }

        let rawHdrs = call.getObject("headers") ?? [:]
        let hdrMap = sanitizeHeaderDict(rawHdrs)

        rulesLock.lock()
        let grantToken = registerRouteGrantLocked(kind: .upload, origin: origin, headers: hdrMap)
        rulesLock.unlock()

        if let port = ensureStreamListener(),
           var comps = URLComponents() as URLComponents? {
            comps.scheme = "http"
            comps.host = "localhost"
            comps.port = Int(port)
            comps.path = "/upload/\(grantToken)"
            comps.queryItems = [URLQueryItem(name: "url", value: validUrl.absoluteString)]
            if let loopbackUrl = comps.url?.absoluteString {
                call.resolve([
                    "ok": true,
                    "url": loopbackUrl,
                    "mode": "loopback-upload"
                ])
                return
            }
        }

        call.resolve(["ok": false, "url": rawTarget])
    }

    private func clearOriginRulesLocked(
        rules: inout [String: [String: String]],
        kinds: Set<RouteKind>,
        target: String
    ) {
        if target.isEmpty {
            rules.removeAll()
            routeGrants = routeGrants.filter { !kinds.contains($0.value.kind) }
            return
        }
        if let parsed = validateHttpUrl(target),
           let origin = paradiseExactOrigin(of: parsed) {
            rules.removeValue(forKey: origin)
            routeGrants = routeGrants.filter {
                !(kinds.contains($0.value.kind) && $0.value.allowedOrigin == origin)
            }
            return
        }
        let hostLower = target
            .components(separatedBy: "/").first?
            .components(separatedBy: ":").first?
            .lowercased() ?? ""
        if hostLower.isEmpty || hostLower == "*" { return }
        let matchedOrigins = Set(rules.keys.filter { key in
            URL(string: key)?.host?.lowercased() == hostLower
        })
        for origin in matchedOrigins {
            rules.removeValue(forKey: origin)
        }
        routeGrants = routeGrants.filter { _, grant in
            if !kinds.contains(grant.kind) { return true }
            if matchedOrigins.contains(grant.allowedOrigin) { return false }
            return URL(string: grant.allowedOrigin)?.host?.lowercased() != hostLower
        }
    }

    private func rewriteM3u8Playlist(
        _ rawText: String,
        baseUrl: URL,
        port: UInt16,
        sourceGrantToken: String,
        sourceOrigin: String
    ) -> Data {
        let lines = rawText.components(separatedBy: "\n")
        var childTokensByOrigin: [String: String] = [:]
        let rewritten = lines.map { rawLine -> String in
            let trimmed = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
            let s = trimmed.trimmingCharacters(in: .whitespaces)
            if s.isEmpty || s.hasPrefix("#") {
                return trimmed
            }
            guard let resolved = URL(string: s, relativeTo: baseUrl)?.absoluteURL,
                  let segOrigin = paradiseExactOrigin(of: resolved) else {
                return trimmed
            }
            let token: String
            if segOrigin == sourceOrigin {
                token = sourceGrantToken
            } else if let existing = childTokensByOrigin[segOrigin] {
                token = existing
            } else {
                rulesLock.lock()
                let segHdrs = streamHeaderRules[segOrigin] ?? [:]
                let minted = registerRouteGrantLocked(kind: .stream, origin: segOrigin, headers: segHdrs)
                rulesLock.unlock()
                childTokensByOrigin[segOrigin] = minted
                token = minted
            }
            var comps = URLComponents()
            comps.scheme = "http"
            comps.host = "localhost"
            comps.port = Int(port)
            comps.path = "/stream/\(token)"
            comps.queryItems = [URLQueryItem(name: "url", value: resolved.absoluteString)]
            return comps.url?.absoluteString ?? trimmed
        }.joined(separator: "\n")
        return Data(rewritten.utf8)
    }

    private func injectHtmlBaseHref(
        _ htmlData: Data,
        effectiveUrl: String,
        frameGrantToken: String
    ) -> Data {
        guard var html = String(data: htmlData, encoding: .utf8) else {
            return htmlData
        }
        html = html.replacingOccurrences(
            of: "<meta[^>]+http-equiv\\s*=\\s*['\"]?(?:content-security-policy|x-frame-options)['\"]?[^>]*>",
            with: "",
            options: [.regularExpression, .caseInsensitive]
        )
        let safeHref = effectiveUrl
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
        let jsBase = effectiveUrl
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "</", with: "<\\/")
        let hasBase = html.range(of: "<base\\s", options: [.regularExpression, .caseInsensitive]) != nil
        let baseTag = hasBase ? "" : "<base href=\"\(safeHref)\">"
        let shimScript =
            "<script>(function(){" +
            "var B=\"\(jsBase)\",BO=new URL(B).origin,P=\"/proxy/\(frameGrantToken)?url=\";" +
            "function abs(u){try{return new URL(String(u),B).href;}catch(_){return String(u||'');}}" +
            "function need(u){try{var p=new URL(u,B);return(p.protocol==='http:'||p.protocol==='https:')&&p.origin===BO&&p.origin!==location.origin;}catch(_){return false;}}" +
            "try{var hp=History.prototype.pushState,hr=History.prototype.replaceState;" +
            "History.prototype.pushState=function(s,t,u){try{return hp.call(this,s,t,u);}catch(_){}};" +
            "History.prototype.replaceState=function(s,t,u){try{return hr.call(this,s,t,u);}catch(_){}};}" +
            "catch(_){}" +
            "if(window.fetch){var nf=window.fetch.bind(window);window.fetch=function(i,o){try{" +
            "var r=abs(typeof i==='string'?i:(i&&i.url?i.url:String(i)));" +
            "if(need(r)){var no=Object.assign({},o||{});" +
            "if(typeof i==='object'&&i&&!(i instanceof URL)){if(!no.method&&i.method)no.method=i.method;if(!no.headers&&i.headers)no.headers=i.headers;}" +
            "no.credentials='same-origin';return nf(P+encodeURIComponent(r),no);}" +
            "}catch(_){}return nf(i,o);};}" +
            "if(window.XMLHttpRequest){var xo=XMLHttpRequest.prototype.open;" +
            "XMLHttpRequest.prototype.open=function(m,u){var a=Array.prototype.slice.call(arguments);" +
            "try{var r=abs(u);if(need(r))a[1]=P+encodeURIComponent(r);}catch(_){}return xo.apply(this,a);};}" +
            "})();</script>"
        let injection = baseTag + shimScript
        if let headRange = html.range(of: "<head[^>]*>", options: [.regularExpression, .caseInsensitive]) {
            var updated = html
            updated.insert(contentsOf: injection, at: headRange.upperBound)
            return Data(updated.utf8)
        }
        return Data((injection + html).utf8)
    }

    private func sendFinalResponse(_ conn: NWConnection, _ packet: Data) {
        conn.send(
            content: packet,
            contentContext: .finalMessage,
            isComplete: true,
            completion: .contentProcessed { _ in }
        )
    }

    private func responseBlocksFraming(_ httpResp: HTTPURLResponse) -> Bool {
        for (k, v) in httpResp.allHeaderFields {
            let keyLower = String(describing: k).lowercased()
            let valLower = String(describing: v).lowercased()
            if keyLower == "x-frame-options" {
                if valLower.contains("deny") || valLower.contains("sameorigin") {
                    return true
                }
            } else if keyLower == "content-security-policy" {
                for directive in valLower.components(separatedBy: ";") {
                    let d = directive.trimmingCharacters(in: .whitespacesAndNewlines)
                    if d.hasPrefix("frame-ancestors") {
                        let tokens = d.split(whereSeparator: { $0.isWhitespace }).dropFirst()
                        if !tokens.contains("*") && !tokens.contains("http:") {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private func extractResponseCookies(_ httpResp: HTTPURLResponse, url: URL) -> [HTTPCookie] {
        var strFields: [String: String] = [:]
        for (k, v) in httpResp.allHeaderFields {
            strFields[String(describing: k)] = String(describing: v)
        }
        return HTTPCookie.cookies(withResponseHeaderFields: strFields, for: url)
    }

    private func responseHasRestrictiveFrameCookies(_ httpResp: HTTPURLResponse, cookies: [HTTPCookie]) -> Bool {
        if !cookies.isEmpty {
            for (k, v) in httpResp.allHeaderFields {
                if String(describing: k).lowercased() == "set-cookie" {
                    let lower = String(describing: v).lowercased()
                    if !lower.contains("samesite=none") {
                        return true
                    }
                }
            }
            for c in cookies {
                let policy = c.sameSitePolicy?.rawValue.lowercased()
                if policy != "none" {
                    return true
                }
            }
        }
        return false
    }

    private func formatLoopbackSetCookie(_ cookie: HTTPCookie) -> String {
        var attrs: [String] = ["\(cookie.name)=\(cookie.value)", "Path=/", "SameSite=Lax"]
        if cookie.isHTTPOnly {
            attrs.append("HttpOnly")
        }
        if let exp = cookie.expiresDate {
            let maxAge = Int(exp.timeIntervalSinceNow)
            attrs.append("Max-Age=\(maxAge)")
        } else {
            attrs.append("Max-Age=604800")
        }
        return sanitizeHeaderValue(attrs.joined(separator: "; "))
    }

    private func buildEmbedRelayHtml(_ targetUrl: String) -> Data {
        let safeSrc = targetUrl
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
        let html =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>*{margin:0;padding:0;box-sizing:border-box}html,body,iframe{width:100%;height:100%;border:0;background:#000;overflow:hidden;display:block}</style>" +
            "</head><body>" +
            "<iframe id=\"f\" src=\"\(safeSrc)\" referrerpolicy=\"strict-origin-when-cross-origin\" playsinline " +
            "allow=\"accelerometer; autoplay; clipboard-write; encrypted-media; fullscreen; gyroscope; picture-in-picture; web-share\" allowfullscreen></iframe>" +
            "<script>(function(){var f=document.getElementById('f'),ready=false,q=[];" +
            "f.addEventListener('load',function(){ready=true;while(q.length){try{f.contentWindow.postMessage(q.shift(),'*');}catch(_){}}});" +
            "window.addEventListener('message',function(e){try{" +
            "if(f&&e.source===f.contentWindow){window.parent.postMessage(e.data,'*');}" +
            "else if(e.source===window.parent&&f&&f.contentWindow){if(!ready&&q.length<16)q.push(e.data);f.contentWindow.postMessage(e.data,'*');}" +
            "}catch(_){}});})();</script></body></html>"
        return Data(html.utf8)
    }

    private func ensureStreamListener() -> UInt16? {
        rulesLock.lock()
        if streamListener != nil && streamPort > 0 {
            let existingPort = streamPort
            rulesLock.unlock()
            return existingPort
        }
        rulesLock.unlock()

        let params = NWParameters.tcp
        params.acceptLocalOnly = true
        guard let listener = try? NWListener(using: params) else {
            return nil
        }

        let readySem = DispatchSemaphore(value: 0)
        var assignedPort: UInt16?

        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                if assignedPort == nil {
                    assignedPort = listener.port?.rawValue
                    readySem.signal()
                }
            case .failed, .cancelled:
                if assignedPort == nil {
                    readySem.signal()
                }
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] conn in
            self?.handleStreamConnection(conn)
        }

        listener.start(queue: streamQueue)
        _ = readySem.wait(timeout: .now() + 2.0)

        guard let port = assignedPort, port > 0 else {
            listener.cancel()
            return nil
        }

        rulesLock.lock()
        streamListener = listener
        streamPort = port
        rulesLock.unlock()
        return port
    }

    private func handleStreamConnection(_ conn: NWConnection) {
        conn.start(queue: streamQueue)
        var headerBuf = Data()
        let sepData = Data("\r\n\r\n".utf8)

        func processParsedRequest(headerSep: Range<Data.Index>) {
            guard let rawHeaders = String(data: headerBuf.subdata(in: 0..<headerSep.lowerBound), encoding: .utf8) else {
                conn.cancel()
                return
            }

            let initialBody = headerBuf.subdata(in: headerSep.upperBound..<headerBuf.count)
            let lines = rawHeaders.components(separatedBy: "\r\n")
            guard let reqLine = lines.first else {
                conn.cancel()
                return
            }
            let parts = reqLine.split(separator: " ")
            guard parts.count >= 2 else {
                conn.cancel()
                return
            }

            let method = String(parts[0]).uppercased()
            let pathAndQuery = String(parts[1])

            var reqHeaders: [String: String] = [:]
            for line in lines.dropFirst() {
                if line.isEmpty { break }
                if let colon = line.firstIndex(of: ":") {
                    let k = line[..<colon].trimmingCharacters(in: .whitespaces)
                    let v = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                    reqHeaders[k.lowercased()] = v
                }
            }
            let corsOrigin = self.allowedCorsOrigin(reqHeaders["origin"])

            if method == "OPTIONS" {
                var preflightLines = ["HTTP/1.1 204 No Content"]
                if let corsOrigin = corsOrigin {
                    preflightLines.append("Access-Control-Allow-Origin: \(self.sanitizeHeaderValue(corsOrigin))")
                    preflightLines.append("Vary: Origin")
                }
                preflightLines.append(contentsOf: [
                    "Access-Control-Allow-Methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS",
                    "Access-Control-Allow-Headers: Range, If-Range, Accept, Content-Type",
                    "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type, X-Paradise-Final-Url",
                    "Cross-Origin-Resource-Policy: cross-origin",
                    "Referrer-Policy: no-referrer",
                    "Connection: close",
                    "",
                    ""
                ])
                self.sendFinalResponse(conn, Data(preflightLines.joined(separator: "\r\n").utf8))
                return
            }

            guard let urlComps = URLComponents(string: "http://localhost" + pathAndQuery) else {
                let forbidden = "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n"
                self.sendFinalResponse(conn, Data(forbidden.utf8))
                return
            }

            let pathSegments = urlComps.path.split(separator: "/", omittingEmptySubsequences: true)
            let routeType = pathSegments.count == 2 ? String(pathSegments[0]) : ""
            let grantToken = pathSegments.count == 2 ? String(pathSegments[1]) : ""

            self.rulesLock.lock()
            let grant = !grantToken.isEmpty ? self.routeGrants[grantToken] : nil
            let isUploadRoute = (routeType == "upload" && grant?.kind == .upload)
            if isUploadRoute {
                self.routeGrants.removeValue(forKey: grantToken)
            }
            self.rulesLock.unlock()

            let isStreamRoute = (routeType == "stream" && grant?.kind == .stream)
            let isFrameRoute = (routeType == "frame" && grant?.kind == .frame)
            let isEmbedRoute = (routeType == "embed" && grant?.kind == .embed)
            let isProxyRoute = (routeType == "proxy" && grant?.kind == .frame)
            let hasReqBody = (method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE")
            let allowedMethod = (method == "GET" || method == "HEAD" ||
                (isFrameRoute && method == "POST") ||
                (isProxyRoute && hasReqBody) ||
                (isUploadRoute && (method == "POST" || method == "PUT" || method == "PATCH")))

            guard let activeGrant = grant,
                  (isStreamRoute || isFrameRoute || isEmbedRoute || isProxyRoute || isUploadRoute),
                  allowedMethod,
                  let targetStr = urlComps.queryItems?.first(where: { $0.name == "url" })?.value,
                  let upstreamUrl = self.validateHttpUrl(targetStr),
                  let upstreamOrigin = paradiseExactOrigin(of: upstreamUrl),
                  upstreamOrigin == activeGrant.allowedOrigin else {
                let forbidden = "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n"
                self.sendFinalResponse(conn, Data(forbidden.utf8))
                return
            }

            if isUploadRoute {
                let maxUploadBytes = 100 * 1024 * 1024
                let rawLen = Int(reqHeaders["content-length"] ?? "0") ?? 0
                if rawLen < 0 || rawLen > maxUploadBytes {
                    let tooLarge = "HTTP/1.1 413 Payload Too Large\r\nConnection: close\r\n\r\n"
                    self.sendFinalResponse(conn, Data(tooLarge.utf8))
                    return
                }
                let tempUrl = FileManager.default.temporaryDirectory
                    .appendingPathComponent("paradise-upload-\(UUID().uuidString).bin")
                FileManager.default.createFile(atPath: tempUrl.path, contents: nil, attributes: nil)
                guard let fileHandle = try? FileHandle(forWritingTo: tempUrl) else {
                    try? FileManager.default.removeItem(at: tempUrl)
                    let failResp = "HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n"
                    self.sendFinalResponse(conn, Data(failResp.utf8))
                    return
                }
                var writtenBytes = 0
                if !initialBody.isEmpty {
                    let slice = initialBody.count > rawLen && rawLen > 0
                        ? initialBody.prefix(rawLen)
                        : initialBody
                    fileHandle.write(slice)
                    writtenBytes += slice.count
                }
                let startFileUpload: () -> Void = { [weak self] in
                    fileHandle.closeFile()
                    guard let self = self else {
                        try? FileManager.default.removeItem(at: tempUrl)
                        conn.cancel()
                        return
                    }
                    var upstreamReq = URLRequest(url: upstreamUrl)
                    upstreamReq.httpMethod = method
                    upstreamReq.httpShouldHandleCookies = false
                    for hdrKey in ["accept", "content-type"] {
                        if let val = reqHeaders[hdrKey] {
                            let cleanVal = self.sanitizeHeaderValue(val)
                            if !cleanVal.isEmpty {
                                upstreamReq.setValue(cleanVal, forHTTPHeaderField: hdrKey)
                            }
                        }
                    }
                    for (k, v) in activeGrant.headers {
                        upstreamReq.setValue(v, forHTTPHeaderField: k)
                    }
                    var taskRef: URLSessionUploadTask?
                    let task = self.urlSession.uploadTask(with: upstreamReq, fromFile: tempUrl) { bodyData, resp, err in
                        try? FileManager.default.removeItem(at: tempUrl)
                        if let tid = taskRef?.taskIdentifier {
                            _ = self.redirectGuard.takeRedirectCookies(for: tid)
                        }
                        guard err == nil, let httpResp = resp as? HTTPURLResponse else {
                            let badGateway = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
                            self.sendFinalResponse(conn, Data(badGateway.utf8))
                            return
                        }
                        let status = httpResp.statusCode
                        let reason = self.sanitizeHeaderValue(HTTPURLResponse.localizedString(forStatusCode: status))
                        let effectiveUrlStr = self.sanitizeHeaderValue((httpResp.url ?? upstreamUrl).absoluteString)
                        let outBody = bodyData ?? Data()
                        var headerLines = ["HTTP/1.1 \(status) \(reason.capitalized)"]
                        for (k, v) in httpResp.allHeaderFields {
                            let keyStr = self.sanitizeHeaderValue(String(describing: k))
                            let valStr = self.sanitizeHeaderValue(String(describing: v))
                            let lower = keyStr.lowercased()
                            if (lower == "content-type" || lower == "cache-control") && !keyStr.isEmpty {
                                headerLines.append("\(keyStr): \(valStr)")
                            }
                        }
                        headerLines.append("Content-Length: \(outBody.count)")
                        headerLines.append("X-Paradise-Final-Url: \(effectiveUrlStr)")
                        if let corsOrigin = corsOrigin {
                            headerLines.append("Access-Control-Allow-Origin: \(self.sanitizeHeaderValue(corsOrigin))")
                            headerLines.append("Vary: Origin")
                        }
                        headerLines.append("Access-Control-Allow-Methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS")
                        headerLines.append("Access-Control-Expose-Headers: Content-Length, Content-Type, X-Paradise-Final-Url")
                        headerLines.append("Cross-Origin-Resource-Policy: cross-origin")
                        headerLines.append("Referrer-Policy: no-referrer")
                        headerLines.append("Connection: close")
                        headerLines.append("")
                        headerLines.append("")
                        var packet = Data(headerLines.joined(separator: "\r\n").utf8)
                        packet.append(outBody)
                        self.sendFinalResponse(conn, packet)
                    }
                    taskRef = task
                    task.resume()
                }
                if rawLen > writtenBytes {
                    func streamRemainingToFile() {
                        let rem = rawLen - writtenBytes
                        if rem <= 0 {
                            startFileUpload()
                            return
                        }
                        conn.receive(minimumIncompleteLength: 1, maximumLength: min(rem, 64 * 1024)) { chunk, _, isComplete, err in
                            if let chunk = chunk, !chunk.isEmpty {
                                fileHandle.write(chunk)
                                writtenBytes += chunk.count
                            }
                            if let _ = err {
                                fileHandle.closeFile()
                                try? FileManager.default.removeItem(at: tempUrl)
                                conn.cancel()
                            } else if isComplete || writtenBytes >= rawLen {
                                startFileUpload()
                            } else {
                                streamRemainingToFile()
                            }
                        }
                    }
                    streamRemainingToFile()
                } else {
                    startFileUpload()
                }
                return
            }

            if isEmbedRoute {
                let relayBody = self.buildEmbedRelayHtml(upstreamUrl.absoluteString)
                let headerLines = [
                    "HTTP/1.1 200 OK",
                    "Content-Type: text/html; charset=utf-8",
                    "Content-Length: \(relayBody.count)",
                    "Cache-Control: no-store",
                    "Cross-Origin-Resource-Policy: cross-origin",
                    "Referrer-Policy: no-referrer",
                    "Connection: close",
                    "",
                    ""
                ].joined(separator: "\r\n")
                var packet = Data(headerLines.utf8)
                if method != "HEAD" {
                    packet.append(relayBody)
                }
                self.sendFinalResponse(conn, packet)
                return
            }

            let expectedLen = min(max(Int(reqHeaders["content-length"] ?? "0") ?? 0, 0), 8 * 1024 * 1024)
            let dispatchUpstream: (Data?) -> Void = { [weak self] postBody in
                guard let self = self else {
                    conn.cancel()
                    return
                }
                var upstreamReq = URLRequest(url: upstreamUrl)
                upstreamReq.httpMethod = method
                upstreamReq.httpShouldHandleCookies = false
                if isStreamRoute {
                    upstreamReq.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
                }
                for hdrKey in ["range", "if-range", "accept", "content-type"] {
                    if let val = reqHeaders[hdrKey] {
                        let cleanVal = self.sanitizeHeaderValue(val)
                        if !cleanVal.isEmpty {
                            upstreamReq.setValue(cleanVal, forHTTPHeaderField: hdrKey)
                        }
                    }
                }
                let isInteractiveFrameReq = isFrameRoute || isProxyRoute
                if isInteractiveFrameReq {
                    if let cookieVal = reqHeaders["cookie"] {
                        let cleanCookie = self.sanitizeHeaderValue(cookieVal)
                        if !cleanCookie.isEmpty {
                            upstreamReq.setValue(cleanCookie, forHTTPHeaderField: "Cookie")
                        }
                    }
                    upstreamReq.setValue("\(upstreamOrigin)/", forHTTPHeaderField: "Referer")
                    upstreamReq.setValue("same-origin", forHTTPHeaderField: "Sec-Fetch-Site")
                    if isFrameRoute {
                        upstreamReq.setValue("document", forHTTPHeaderField: "Sec-Fetch-Dest")
                        upstreamReq.setValue("navigate", forHTTPHeaderField: "Sec-Fetch-Mode")
                    } else {
                        upstreamReq.setValue(upstreamOrigin, forHTTPHeaderField: "Origin")
                        upstreamReq.setValue("empty", forHTTPHeaderField: "Sec-Fetch-Dest")
                        upstreamReq.setValue("cors", forHTTPHeaderField: "Sec-Fetch-Mode")
                    }
                }
                if hasReqBody, let postBody = postBody {
                    upstreamReq.httpBody = postBody
                }

                for (k, v) in activeGrant.headers {
                    upstreamReq.setValue(v, forHTTPHeaderField: k)
                }

                let activePort = self.streamPort
                var taskRef: URLSessionDataTask?
                let task = self.urlSession.dataTask(with: upstreamReq) { bodyData, resp, err in
                    let redirectCookies = taskRef.map { self.redirectGuard.takeRedirectCookies(for: $0.taskIdentifier) } ?? []
                    guard err == nil, let httpResp = resp as? HTTPURLResponse else {
                        let badGateway = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
                        self.sendFinalResponse(conn, Data(badGateway.utf8))
                        return
                    }

                    let status = httpResp.statusCode
                    let reason = self.sanitizeHeaderValue(HTTPURLResponse.localizedString(forStatusCode: status))
                    let effectiveUrl = httpResp.url ?? upstreamUrl
                    let effectiveUrlStr = self.sanitizeHeaderValue(effectiveUrl.absoluteString)
                    let mime = (httpResp.mimeType ?? "").lowercased()
                    let isM3u8 = isStreamRoute && (
                        mime.contains("mpegurl") || effectiveUrl.path.lowercased().hasSuffix(".m3u8")
                    )
                    let isHtmlFrame = isFrameRoute && mime.contains("text/html")
                    let allResponseCookies: [HTTPCookie] = isInteractiveFrameReq
                        ? (redirectCookies + self.extractResponseCookies(httpResp, url: effectiveUrl))
                        : []

                    var outBody = (method == "HEAD") ? Data() : (bodyData ?? Data())
                    if method != "HEAD" {
                        if isM3u8, activePort > 0, let rawStr = String(data: outBody, encoding: .utf8) {
                            outBody = self.rewriteM3u8Playlist(
                                rawStr,
                                baseUrl: effectiveUrl,
                                port: activePort,
                                sourceGrantToken: grantToken,
                                sourceOrigin: activeGrant.allowedOrigin
                            )
                        } else if isHtmlFrame {
                            if method == "GET" &&
                                (200...299).contains(status) &&
                                !self.responseBlocksFraming(httpResp) &&
                                !self.responseHasRestrictiveFrameCookies(httpResp, cookies: allResponseCookies) {
                                outBody = self.buildEmbedRelayHtml(effectiveUrlStr)
                            } else {
                                outBody = self.injectHtmlBaseHref(
                                    outBody,
                                    effectiveUrl: effectiveUrlStr,
                                    frameGrantToken: grantToken
                                )
                            }
                        }
                    }

                    var headerLines = ["HTTP/1.1 \(status) \(reason.capitalized)"]
                    let allowed = Set([
                        "content-type",
                        "content-range",
                        "accept-ranges",
                        "etag",
                        "last-modified",
                        "cache-control"
                    ])
                    for (k, v) in httpResp.allHeaderFields {
                        let keyStr = self.sanitizeHeaderValue(String(describing: k))
                        let valStr = self.sanitizeHeaderValue(String(describing: v))
                        let lower = keyStr.lowercased()
                        if allowed.contains(lower) && !keyStr.isEmpty {
                            headerLines.append("\(keyStr): \(valStr)")
                        }
                    }
                    if isInteractiveFrameReq && !allResponseCookies.isEmpty {
                        for cookie in allResponseCookies {
                            let setCookieVal = self.formatLoopbackSetCookie(cookie)
                            if !setCookieVal.isEmpty {
                                headerLines.append("Set-Cookie: \(setCookieVal)")
                            }
                        }
                    }
                    if method != "HEAD" {
                        headerLines.append("Content-Length: \(outBody.count)")
                    }
                    headerLines.append("X-Paradise-Final-Url: \(effectiveUrlStr)")
                    if let corsOrigin = corsOrigin {
                        headerLines.append("Access-Control-Allow-Origin: \(self.sanitizeHeaderValue(corsOrigin))")
                        headerLines.append("Vary: Origin")
                    }
                    headerLines.append("Access-Control-Allow-Methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS")
                    headerLines.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type, X-Paradise-Final-Url")
                    headerLines.append("Cross-Origin-Resource-Policy: cross-origin")
                    headerLines.append("Referrer-Policy: no-referrer")
                    headerLines.append("Connection: close")
                    headerLines.append("")
                    headerLines.append("")

                    var packet = Data(headerLines.joined(separator: "\r\n").utf8)
                    if method != "HEAD" {
                        packet.append(outBody)
                    }

                    self.sendFinalResponse(conn, packet)
                }
                taskRef = task
                task.resume()
            }

            if hasReqBody && expectedLen > initialBody.count {
                var accBody = initialBody
                func readRemaining() {
                    let rem = expectedLen - accBody.count
                    if rem <= 0 {
                        dispatchUpstream(accBody)
                        return
                    }
                    conn.receive(minimumIncompleteLength: 1, maximumLength: min(rem, 64 * 1024)) { chunk, _, isComplete, err in
                        if let chunk = chunk, !chunk.isEmpty {
                            accBody.append(chunk)
                        }
                        if err != nil || isComplete || accBody.count >= expectedLen {
                            dispatchUpstream(accBody)
                        } else {
                            readRemaining()
                        }
                    }
                }
                readRemaining()
            } else {
                dispatchUpstream(hasReqBody ? initialBody : nil)
            }
        }

        func receiveHeaderChunk() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { chunk, _, isComplete, error in
                if let chunk = chunk, !chunk.isEmpty {
                    headerBuf.append(chunk)
                }
                if let sep = headerBuf.range(of: sepData) {
                    processParsedRequest(headerSep: sep)
                } else if error != nil || isComplete || headerBuf.count >= 64 * 1024 {
                    if !isComplete {
                        conn.cancel()
                    }
                } else {
                    receiveHeaderChunk()
                }
            }
        }

        receiveHeaderChunk()
    }

    private func topPresenterViewController() -> UIViewController? {
        var current: UIViewController? = bridge?.viewController
        while let presented = current?.presentedViewController {
            current = presented
        }
        return current
    }

    private func validateHttpUrl(_ raw: String) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              trimmed != "*",
              let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host, !host.isEmpty else {
            return nil
        }
        return url
    }
}
