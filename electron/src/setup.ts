import type { CapacitorElectronConfig } from "@capacitor-community/electron";
import {
    CapElectronEventEmitter,
    CapacitorSplashScreen,
    setupCapacitorElectronPlugins,
} from "@capacitor-community/electron";
import chokidar from "chokidar";
import { execFile, execFileSync } from "child_process";
import type { MenuItemConstructorOptions } from "electron";
import {
    app,
    BrowserWindow,
    desktopCapturer,
    dialog,
    ipcMain,
    Menu,
    MenuItem,
    nativeImage,
    Notification,
    session,
    shell,
    Tray,
} from "electron";
import electronIsDev from "electron-is-dev";
import electronServe from "electron-serve";
import windowStateKeeper from "electron-window-state";
import { existsSync, readFileSync } from "fs";
import { join } from "path";
import { promisify } from "util";

const execFileAsync = promisify(execFile);
const SINK_NAME = "paradise_share";
const EXEC_OPTS = { maxBuffer: 10 * 1024 * 1024 };
const HTTP_AND_WS_URL_FILTER = {
    urls: ["http://*/*", "https://*/*", "ws://*/*", "wss://*/*"],
};

interface AudioAppCandidate {
    label: string;
    appNameLower: string;
    nodeNameLower: string;
    binaryLower: string;
    pid: number;
}

export interface DynamicTrayItem {
    id?: string;
    label?: string;
    type?: "normal" | "separator" | "checkbox";
    checked?: boolean;
    enabled?: boolean;
}

export interface ProxyRequestOptions {
    url: string;
    method?: string;
    headers?: Record<string, string>;
    body?: string | Uint8Array | ArrayBuffer;
    bodyEncoding?: "utf8" | "base64";
    responseType?: "text" | "json" | "base64";
}

export interface PrepareStreamOptions {
    url?: string;
    urlOrHost?: string;
    headers?: Record<string, string>;
    requestHeaders?: Record<string, string>;
}

export interface PrepareWebSocketOptions {
    url?: string;
    urlOrHost?: string;
    origin?: string;
    headers?: Record<string, string>;
    requestHeaders?: Record<string, string>;
}

let pipewireInterval: NodeJS.Timeout | null = null;
let baselineSourceOutputIds = new Set<number>();
let selectedAudioTarget: AudioAppCandidate | "ALL" | "NONE" = "NONE";
let isScreenShareActive = false;

const streamHeaderRules = new Map<string, Record<string, string>>();
const frameHeaderRules = new Map<string, Record<string, string>>();
const wsHeaderRules = new Map<string, Record<string, string>>();
const trustedFrameHostIds = new Set<number>();
let proxyIpcInitialized = false;
let activeCustomScheme = "capacitor-electron";
let webRequestInstalled = false;
let activeAuthWindow: BrowserWindow | null = null;
let lastMainWebContentsId: number | null = null;
let mainWebContentsId: number | null = null;

const AUTH_PARTITION = "paradise-auth";
const PROXY_FETCH_PARTITION = "paradise-proxy-fetch";
const MAX_PROXY_REDIRECTS = 6;

const SAFE_REDIRECT_HEADERS = new Set([
    "accept",
    "accept-language",
    "accept-encoding",
    "user-agent",
    "range",
    "if-range",
]);

const STREAM_ALLOWED_RESOURCE_TYPES = new Set([
    "media",
    "image",
    "xhr",
    "fetch",
    "other",
]);

const FORBIDDEN_FRAMING_HEADERS = new Set([
    "content-length",
    "transfer-encoding",
    "host",
]);

export function isTrustedAppUrl(
    rawUrl: string | undefined | null,
    scheme: string = activeCustomScheme
): boolean {
    if (!rawUrl) return false;
    try {
        const parsed = new URL(rawUrl);
        return parsed.protocol === `${scheme}:`;
    } catch {
        return false;
    }
}

export function assertTrustedIpcSender(
    event: Electron.IpcMainInvokeEvent
): void {
    const senderFrame = event.senderFrame;
    if (!senderFrame || senderFrame.parent !== null) {
        throw new Error("Unauthorized IPC sender frame");
    }
    if (!isTrustedAppUrl(senderFrame.url, activeCustomScheme)) {
        throw new Error("Unauthorized IPC sender origin");
    }
    if (mainWebContentsId !== null && event.sender.id !== mainWebContentsId) {
        throw new Error("Unauthorized IPC sender window");
    }
}

function extractExactOrigin(urlStr: string): string | null {
    const trimmed = String(urlStr || "").trim();
    if (!trimmed || trimmed === "*") return null;
    try {
        const parsed = new URL(trimmed);
        if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
            return null;
        }
        return parsed.origin.toLowerCase();
    } catch {
        return null;
    }
}

function extractWsOrigin(urlStr: string): string | null {
    const trimmed = String(urlStr || "").trim();
    if (!trimmed || trimmed === "*") return null;
    try {
        const parsed = new URL(trimmed);
        if (
            parsed.protocol !== "ws:" &&
            parsed.protocol !== "wss:" &&
            parsed.protocol !== "http:" &&
            parsed.protocol !== "https:"
        ) {
            return null;
        }
        const httpProto =
            parsed.protocol === "ws:" || parsed.protocol === "http:"
                ? "http:"
                : "https:";
        return `${httpProto}//${parsed.host}`.toLowerCase();
    } catch {
        return null;
    }
}

function deleteOriginRulesForTarget(
    map: Map<string, Record<string, string>>,
    target: string
): void {
    const trimmed = String(target || "").trim();
    if (!trimmed) {
        map.clear();
        return;
    }
    const exactOrigin = extractExactOrigin(trimmed) || extractWsOrigin(trimmed);
    if (exactOrigin) {
        map.delete(exactOrigin);
        return;
    }
    const hostLower = trimmed.split("/")[0].split(":")[0].toLowerCase();
    if (!hostLower || hostLower === "*") return;
    for (const origin of Array.from(map.keys())) {
        try {
            if (new URL(origin).hostname.toLowerCase() === hostLower) {
                map.delete(origin);
            }
        } catch {}
    }
}

function sanitizeHeaderRecord(
    rawHeaders: Record<string, string> | undefined,
    targetProtocol?: string
): Record<string, string> {
    const clean: Record<string, string> = {};
    for (const [rawKey, rawVal] of Object.entries(rawHeaders ?? {})) {
        if (rawVal === undefined || rawVal === null) continue;
        const key = String(rawKey).trim();
        const val = String(rawVal);
        if (!key || /[\r\n]/.test(key) || /[\r\n]/.test(val)) continue;
        const lower = key.toLowerCase();
        if (FORBIDDEN_FRAMING_HEADERS.has(lower)) continue;
        if (
            lower === "referer" &&
            targetProtocol === "http:" &&
            /^https:/i.test(val.trim())
        ) {
            continue;
        }
        for (const existing of Object.keys(clean)) {
            if (existing.toLowerCase() === lower) {
                delete clean[existing];
            }
        }
        clean[key] = val;
    }
    return clean;
}

function hasCredentialOrCustomHeaders(
    headers: Record<string, string>
): boolean {
    for (const key of Object.keys(headers)) {
        const lower = key.toLowerCase();
        if (
            !SAFE_REDIRECT_HEADERS.has(lower) &&
            lower !== "content-type" &&
            lower !== "referer" &&
            lower !== "origin"
        ) {
            return true;
        }
    }
    return false;
}

function stripCrossOriginHeaders(
    headers: Record<string, string>
): Record<string, string> {
    const kept: Record<string, string> = {};
    for (const [key, val] of Object.entries(headers)) {
        if (SAFE_REDIRECT_HEADERS.has(key.toLowerCase())) {
            kept[key] = val;
        }
    }
    return kept;
}

function getProxyFetchSession(): Electron.Session {
    return session.fromPartition(PROXY_FETCH_PARTITION, { cache: false });
}

async function clearAuthPartitionData(): Promise<void> {
    try {
        const authSession = session.fromPartition(AUTH_PARTITION, {
            cache: false,
        });
        await authSession.clearStorageData();
        await authSession.clearCache();
    } catch {
    }
}

function stripHeaderCaseInsensitive(
    headers: Record<string, string | string[]>,
    targetKey: string
): void {
    const lower = targetKey.toLowerCase();
    for (const key of Object.keys(headers)) {
        if (key.toLowerCase() === lower) {
            delete headers[key];
        }
    }
}

function stripFrameAncestorsFromCsp(
    headers: Record<string, string | string[]>,
    targetKey: string
): void {
    const lower = targetKey.toLowerCase();
    for (const key of Object.keys(headers)) {
        if (key.toLowerCase() !== lower) continue;
        const raw = headers[key];
        const values = Array.isArray(raw) ? raw : [String(raw ?? "")];
        const cleaned = values
            .map((policy) =>
                policy
                    .split(";")
                    .map((d) => d.trim())
                    .filter(
                        (d) =>
                            d.length > 0 &&
                            !/^frame-ancestors(\s|$)/i.test(d)
                    )
                    .join("; ")
            )
            .filter((policy) => policy.length > 0);
        if (cleaned.length > 0) {
            headers[key] = cleaned;
        } else {
            delete headers[key];
        }
    }
}

function rewriteSetCookieSameSiteNone(
    headers: Record<string, string | string[]>,
    isHttps: boolean
): boolean {
    let modified = false;
    for (const key of Object.keys(headers)) {
        if (key.toLowerCase() !== "set-cookie") continue;
        const raw = headers[key];
        const values = Array.isArray(raw) ? raw : [String(raw ?? "")];
        headers[key] = values.map((cookie) => {
            let next = cookie;
            if (/;\s*SameSite\s*=\s*(?:Strict|Lax)\b/i.test(next)) {
                next = next.replace(
                    /;\s*SameSite\s*=\s*(?:Strict|Lax)\b/gi,
                    "; SameSite=None"
                );
            } else if (!/;\s*SameSite\s*=/i.test(next)) {
                next = `${next}; SameSite=None`;
            }
            if (isHttps && !/;\s*Secure\b/i.test(next)) {
                next = `${next}; Secure`;
            }
            if (!/;\s*(?:Max-Age|Expires)\s*=/i.test(next)) {
                next = `${next}; Max-Age=604800`;
            }
            if (next !== cookie) modified = true;
            return next;
        });
    }
    return modified;
}

export function setupParadiseProxyIpc(): void {
    if (proxyIpcInitialized) return;
    proxyIpcInitialized = true;

    ipcMain.handle(
        "paradise-proxy:prepare-stream",
        (event, opts: PrepareStreamOptions) => {
            assertTrustedIpcSender(event);
            const rawTarget = String(opts?.url || opts?.urlOrHost || "").trim();
            const origin = extractExactOrigin(rawTarget);
            if (!origin) {
                return { ok: false, url: rawTarget };
            }
            const parsed = new URL(rawTarget);
            const hdrs = sanitizeHeaderRecord(
                opts?.headers ?? opts?.requestHeaders,
                parsed.protocol
            );
            streamHeaderRules.set(origin, hdrs);
            return { ok: true, url: opts?.url || rawTarget };
        }
    );

    ipcMain.handle(
        "paradise-proxy:clear-stream",
        (event, urlOrHost?: string | { urlOrHost?: string }) => {
            assertTrustedIpcSender(event);
            const target =
                typeof urlOrHost === "string"
                    ? urlOrHost
                    : urlOrHost?.urlOrHost;
            if (!target) {
                streamHeaderRules.clear();
                return { ok: true };
            }
            deleteOriginRulesForTarget(streamHeaderRules, target);
            return { ok: true };
        }
    );

    ipcMain.handle(
        "paradise-proxy:prepare-frame",
        (event, opts: PrepareStreamOptions) => {
            assertTrustedIpcSender(event);
            const rawTarget = String(opts?.url || opts?.urlOrHost || "").trim();
            const origin = extractExactOrigin(rawTarget);
            if (!origin) {
                return { ok: false, url: rawTarget };
            }
            const parsed = new URL(rawTarget);
            const hdrs = sanitizeHeaderRecord(
                opts?.headers ?? opts?.requestHeaders,
                parsed.protocol
            );
            frameHeaderRules.set(origin, hdrs);
            return { ok: true, url: opts?.url || rawTarget, mode: "headers" };
        }
    );

    ipcMain.handle(
        "paradise-proxy:clear-frame",
        (event, urlOrHost?: string | { urlOrHost?: string }) => {
            assertTrustedIpcSender(event);
            const target =
                typeof urlOrHost === "string"
                    ? urlOrHost
                    : urlOrHost?.urlOrHost;
            if (!target) {
                frameHeaderRules.clear();
                return { ok: true };
            }
            deleteOriginRulesForTarget(frameHeaderRules, target);
            return { ok: true };
        }
    );

    ipcMain.handle(
        "paradise-proxy:prepare-websocket",
        (event, opts: PrepareWebSocketOptions) => {
            assertTrustedIpcSender(event);
            const rawTarget = String(opts?.url || opts?.urlOrHost || "").trim();
            const originKey = extractWsOrigin(rawTarget);
            if (!originKey) {
                return { ok: false, url: rawTarget };
            }
            const parsed = new URL(rawTarget);
            const rawHdrs: Record<string, string> = {
                ...(opts?.headers ?? opts?.requestHeaders ?? {}),
            };
            if (opts?.origin && !/[\r\n]/.test(String(opts.origin))) {
                rawHdrs["Origin"] = String(opts.origin).trim();
            }
            const hdrs = sanitizeHeaderRecord(rawHdrs, parsed.protocol);
            wsHeaderRules.set(originKey, hdrs);
            return { ok: true, url: opts?.url || rawTarget };
        }
    );

    ipcMain.handle(
        "paradise-proxy:clear-websocket",
        (event, urlOrHost?: string | { urlOrHost?: string }) => {
            assertTrustedIpcSender(event);
            const target =
                typeof urlOrHost === "string"
                    ? urlOrHost
                    : urlOrHost?.urlOrHost;
            if (!target) {
                wsHeaderRules.clear();
                return { ok: true };
            }
            deleteOriginRulesForTarget(wsHeaderRules, target);
            return { ok: true };
        }
    );

    ipcMain.handle(
        "paradise-proxy:request",
        async (event, opts: ProxyRequestOptions) => {
            assertTrustedIpcSender(event);
            let currentUrl = new URL(opts.url);
            if (
                currentUrl.protocol !== "http:" &&
                currentUrl.protocol !== "https:"
            ) {
                throw new Error(`Unsupported protocol: ${currentUrl.protocol}`);
            }

            let currentMethod = (opts.method ?? "GET").toUpperCase();
            const canHaveBody =
                currentMethod !== "GET" && currentMethod !== "HEAD";

            let currentHeaders = sanitizeHeaderRecord(
                opts.headers,
                currentUrl.protocol
            );

            let currentBody: BodyInit | undefined;
            if (canHaveBody && opts.body !== undefined && opts.body !== null) {
                if (
                    opts.bodyEncoding === "base64" &&
                    typeof opts.body === "string"
                ) {
                    currentBody = Buffer.from(
                        opts.body,
                        "base64"
                    ) as unknown as BodyInit;
                } else if (ArrayBuffer.isView(opts.body)) {
                    currentBody = Buffer.from(
                        opts.body.buffer,
                        opts.body.byteOffset,
                        opts.body.byteLength
                    ) as unknown as BodyInit;
                } else if (opts.body instanceof ArrayBuffer) {
                    currentBody = Buffer.from(opts.body) as unknown as BodyInit;
                } else {
                    currentBody = String(opts.body);
                }
            }

            const proxySession = getProxyFetchSession();
            let res: Response | null = null;
            for (let hop = 0; hop <= MAX_PROXY_REDIRECTS; hop++) {
                res = await proxySession.fetch(currentUrl.toString(), {
                    method: currentMethod,
                    headers: currentHeaders,
                    body: currentBody,
                    redirect: "manual",
                    credentials: "omit",
                });

                const isRedirect =
                    res.status === 301 ||
                    res.status === 302 ||
                    res.status === 303 ||
                    res.status === 307 ||
                    res.status === 308;
                const location = isRedirect ? res.headers.get("location") : null;
                if (!isRedirect || !location) {
                    break;
                }
                if (hop === MAX_PROXY_REDIRECTS) {
                    res.body?.cancel().catch(() => {});
                    throw new Error("Too many redirects");
                }

                const nextUrl = new URL(location, currentUrl);
                if (
                    nextUrl.protocol !== "http:" &&
                    nextUrl.protocol !== "https:"
                ) {
                    res.body?.cancel().catch(() => {});
                    throw new Error(
                        `Unsupported redirect protocol: ${nextUrl.protocol}`
                    );
                }

                const sameOrigin =
                    nextUrl.origin.toLowerCase() ===
                    currentUrl.origin.toLowerCase();

                if (
                    currentUrl.protocol === "https:" &&
                    nextUrl.protocol === "http:" &&
                    hasCredentialOrCustomHeaders(currentHeaders)
                ) {
                    res.body?.cancel().catch(() => {});
                    throw new Error(
                        "Blocked insecure HTTPS to HTTP credentialed redirect"
                    );
                }

                if (
                    res.status === 303 ||
                    ((res.status === 301 || res.status === 302) &&
                        currentMethod === "POST")
                ) {
                    currentMethod = "GET";
                    currentBody = undefined;
                    for (const key of Object.keys(currentHeaders)) {
                        const lower = key.toLowerCase();
                        if (
                            lower === "content-type" ||
                            lower === "content-length"
                        ) {
                            delete currentHeaders[key];
                        }
                    }
                }

                if (!sameOrigin) {
                    if (
                        (res.status === 307 || res.status === 308) &&
                        currentBody !== undefined
                    ) {
                        res.body?.cancel().catch(() => {});
                        throw new Error(
                            "Blocked cross-origin 307/308 redirect with request body"
                        );
                    }
                    currentHeaders = stripCrossOriginHeaders(currentHeaders);
                }

                res.body?.cancel().catch(() => {});
                currentUrl = nextUrl;
            }

            if (!res) {
                throw new Error("Proxy request produced no response");
            }

            const responseHeaders: Record<string, string> = {};
            res.headers.forEach((value, key) => {
                responseHeaders[key.toLowerCase()] = value;
            });

            const mode = opts.responseType ?? "text";
            let data: unknown;
            if (mode === "base64") {
                const buf = Buffer.from(await res.arrayBuffer());
                data = buf.toString("base64");
            } else if (mode === "json") {
                data = await res.json();
            } else {
                data = await res.text();
            }

            return {
                ok: res.ok,
                status: res.status,
                url: res.url || currentUrl.toString(),
                headers: responseHeaders,
                data,
            };
        }
    );

    ipcMain.handle(
        "paradise-proxy:open-auth-window",
        async (
            event,
            opts: {
                url: string;
                title?: string;
                external?: boolean;
                callbackPrefix?: string;
                closeOnPrefix?: string;
                redirectOnPrefix?: {
                    matchPrefix?: string;
                    targetUrl?: string;
                };
            }
        ) => {
            assertTrustedIpcSender(event);
            const parsed = new URL(String(opts?.url || ""));
            if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
                throw new Error(`Unsupported protocol: ${parsed.protocol}`);
            }

            if (opts?.external) {
                await shell.openExternal(parsed.toString());
                return { ok: true, mode: "external" };
            }

            const parseHttpPrefix = (raw: unknown): string | null => {
                if (typeof raw !== "string") return null;
                const trimmed = raw.trim();
                if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    return null;
                }
                try {
                    new URL(trimmed);
                    return trimmed;
                } catch {
                    return null;
                }
            };

            const callbackPrefix = parseHttpPrefix(opts?.callbackPrefix);
            const closeOnPrefix = parseHttpPrefix(opts?.closeOnPrefix);
            const redirectMatchPrefix = parseHttpPrefix(
                opts?.redirectOnPrefix?.matchPrefix
            );
            const redirectTargetUrl = parseHttpPrefix(
                opts?.redirectOnPrefix?.targetUrl
            );
            const redirectRule =
                redirectMatchPrefix && redirectTargetUrl
                    ? {
                          matchPrefix: redirectMatchPrefix,
                          targetUrl: redirectTargetUrl,
                      }
                    : null;
            const awaitingNav = Boolean(callbackPrefix || closeOnPrefix);

            const parentWin =
                BrowserWindow.fromWebContents(event.sender) ?? undefined;
            if (parentWin && !parentWin.isDestroyed()) {
                lastMainWebContentsId = parentWin.webContents.id;
            }

            if (activeAuthWindow && !activeAuthWindow.isDestroyed()) {
                if (!awaitingNav && !redirectRule) {
                    activeAuthWindow.setTitle(opts?.title || "Sign In");
                    activeAuthWindow
                        .loadURL(parsed.toString())
                        .catch((err) =>
                            console.warn(
                                "[paradise-auth] loadURL warning:",
                                err?.code ?? err?.message ?? "load failed"
                            )
                        );
                    activeAuthWindow.show();
                    activeAuthWindow.focus();
                    return { ok: true, mode: "window" };
                }
                activeAuthWindow.close();
                activeAuthWindow = null;
            }

            await clearAuthPartitionData();

            const authWin = new BrowserWindow({
                width: 520,
                height: 760,
                parent:
                    parentWin && !parentWin.isDestroyed()
                        ? parentWin
                        : undefined,
                modal: false,
                show: true,
                autoHideMenuBar: true,
                title: opts?.title || "Sign In",
                backgroundColor: "#1e1f22",
                webPreferences: {
                    nodeIntegration: false,
                    contextIsolation: true,
                    sandbox: true,
                    partition: AUTH_PARTITION,
                },
            });
            activeAuthWindow = authWin;

            authWin.webContents.setWindowOpenHandler(({ url }) => {
                try {
                    const extUrl = new URL(url);
                    if (
                        extUrl.protocol === "http:" ||
                        extUrl.protocol === "https:"
                    ) {
                        shell.openExternal(extUrl.toString()).catch(() => {});
                    }
                } catch {}
                return { action: "deny" };
            });

            if (redirectRule) {
                let redirected = false;
                const maybeRedirect = (
                    navEvent: Electron.Event | null,
                    targetUrl: string
                ) => {
                    if (
                        !redirected &&
                        !authWin.isDestroyed() &&
                        typeof targetUrl === "string" &&
                        targetUrl.startsWith(redirectRule.matchPrefix)
                    ) {
                        redirected = true;
                        if (navEvent && typeof navEvent.preventDefault === "function") {
                            navEvent.preventDefault();
                        }
                        authWin
                            .loadURL(redirectRule.targetUrl)
                            .catch((err) =>
                                console.warn(
                                    "[paradise-auth] redirect loadURL warning:",
                                    err?.code ?? err?.message ?? "load failed"
                                )
                            );
                    }
                };
                authWin.webContents.on("will-redirect", (e, u) =>
                    maybeRedirect(e, u)
                );
                authWin.webContents.on("will-navigate", (e, u) =>
                    maybeRedirect(e, u)
                );
                authWin.webContents.on("did-navigate", (_e, u) =>
                    maybeRedirect(null, u)
                );
                authWin.webContents.on("did-navigate-in-page", (_e, u) =>
                    maybeRedirect(null, u)
                );
            }

            if (awaitingNav) {
                return await new Promise((resolve) => {
                    let settled = false;
                    const finish = (result: {
                        ok: boolean;
                        mode: string;
                        callbackUrl?: string;
                        cancelled?: boolean;
                    }) => {
                        if (settled) return;
                        settled = true;
                        if (activeAuthWindow === authWin) {
                            activeAuthWindow = null;
                        }
                        if (!authWin.isDestroyed()) {
                            authWin.close();
                        }
                        void clearAuthPartitionData();
                        if (parentWin && !parentWin.isDestroyed()) {
                            parentWin.show();
                            parentWin.focus();
                        }
                        resolve(result);
                    };

                    const maybeCapture = (
                        navEvent: Electron.Event | null,
                        targetUrl: string
                    ) => {
                        if (settled || typeof targetUrl !== "string") return;
                        if (callbackPrefix && targetUrl.startsWith(callbackPrefix)) {
                            if (navEvent && typeof navEvent.preventDefault === "function") {
                                navEvent.preventDefault();
                            }
                            finish({
                                ok: true,
                                mode: "callback",
                                callbackUrl: targetUrl,
                            });
                            return;
                        }
                        if (closeOnPrefix && targetUrl.startsWith(closeOnPrefix)) {
                            if (navEvent && typeof navEvent.preventDefault === "function") {
                                navEvent.preventDefault();
                            }
                            finish({
                                ok: true,
                                mode: "completed",
                                callbackUrl: targetUrl,
                            });
                        }
                    };

                    authWin.webContents.on("will-redirect", (e, u) =>
                        maybeCapture(e, u)
                    );
                    authWin.webContents.on("will-navigate", (e, u) =>
                        maybeCapture(e, u)
                    );
                    authWin.webContents.on("did-navigate", (_e, u) =>
                        maybeCapture(null, u)
                    );
                    authWin.webContents.on("did-navigate-in-page", (_e, u) =>
                        maybeCapture(null, u)
                    );
                    authWin.on("closed", () => {
                        if (activeAuthWindow === authWin) {
                            activeAuthWindow = null;
                        }
                        void clearAuthPartitionData();
                        if (!settled) {
                            settled = true;
                            resolve({
                                ok: false,
                                mode: callbackPrefix ? "callback" : "completed",
                                cancelled: true,
                            });
                        }
                    });

                    authWin
                        .loadURL(parsed.toString())
                        .catch((err) =>
                            console.warn(
                                "[paradise-auth] loadURL warning:",
                                err?.code ?? err?.message ?? "load failed"
                            )
                        );
                    authWin.show();
                    authWin.focus();
                });
            }

            authWin.on("closed", () => {
                if (activeAuthWindow === authWin) {
                    activeAuthWindow = null;
                }
                void clearAuthPartitionData();
            });

            authWin
                .loadURL(parsed.toString())
                .catch((err) =>
                    console.warn(
                        "[paradise-auth] loadURL warning:",
                        err?.code ?? err?.message ?? "load failed"
                    )
                );
            authWin.show();
            authWin.focus();
            return { ok: true, mode: "window" };
        }
    );

    ipcMain.handle("paradise-proxy:close-auth-window", async (event) => {
        assertTrustedIpcSender(event);
        if (activeAuthWindow && !activeAuthWindow.isDestroyed()) {
            activeAuthWindow.close();
            activeAuthWindow = null;
        }
        await clearAuthPartitionData();
        const callerWin =
            BrowserWindow.fromWebContents(event.sender) ??
            (lastMainWebContentsId !== null
                ? BrowserWindow.getAllWindows().find(
                      (w) =>
                          !w.isDestroyed() &&
                          w.webContents.id === lastMainWebContentsId
                  )
                : undefined);
        if (callerWin && !callerWin.isDestroyed()) {
            callerWin.show();
            callerWin.focus();
        }
        return { ok: true };
    });

    ipcMain.handle(
        "paradise-proxy:open-external",
        async (event, rawUrl: string) => {
            assertTrustedIpcSender(event);
            const parsed = new URL(String(rawUrl || ""));
            if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
                throw new Error(`Unsupported protocol: ${parsed.protocol}`);
            }
            await shell.openExternal(parsed.toString());
            return { ok: true };
        }
    );
}

export function installStreamCorsWebRequest(customScheme?: string): void {
    if (customScheme) {
        activeCustomScheme = customScheme;
    }
    webRequestInstalled = true;

    const appOrigin = `${activeCustomScheme}://-`;

    session.defaultSession.webRequest.onBeforeSendHeaders(
        HTTP_AND_WS_URL_FILTER,
        (details, callback) => {
            const isPopoutHost =
                details.webContentsId !== undefined &&
                trustedFrameHostIds.has(details.webContentsId);
            if (
                mainWebContentsId !== null &&
                details.webContentsId !== undefined &&
                details.webContentsId > 0 &&
                details.webContentsId !== mainWebContentsId &&
                !isPopoutHost
            ) {
                callback({ requestHeaders: details.requestHeaders });
                return;
            }

            if (details.resourceType === "webSocket") {
                let wsReqFrame: Electron.WebFrameMain | undefined;
                try {
                    wsReqFrame = details.frame;
                } catch {
                    wsReqFrame = undefined;
                }
                const wsFromTrustedTopFrame =
                    !wsReqFrame ||
                    (wsReqFrame.parent === null &&
                        isTrustedAppUrl(wsReqFrame.url, activeCustomScheme));
                const wsOrigin = wsFromTrustedTopFrame
                    ? extractWsOrigin(details.url)
                    : null;
                const wsHdrs = wsOrigin
                    ? wsHeaderRules.get(wsOrigin)
                    : undefined;
                if (!wsHdrs || Object.keys(wsHdrs).length === 0) {
                    callback({ requestHeaders: details.requestHeaders });
                    return;
                }
                const nextWsHeaders: Record<string, string> = {
                    ...details.requestHeaders,
                };
                for (const [key, value] of Object.entries(wsHdrs)) {
                    for (const existing of Object.keys(nextWsHeaders)) {
                        if (existing.toLowerCase() === key.toLowerCase()) {
                            delete nextWsHeaders[existing];
                        }
                    }
                    nextWsHeaders[key] = value;
                }
                callback({ requestHeaders: nextWsHeaders });
                return;
            }

            const origin = extractExactOrigin(details.url);
            if (!origin) {
                callback({ requestHeaders: details.requestHeaders });
                return;
            }

            let reqFrame: Electron.WebFrameMain | undefined;
            try {
                reqFrame = details.frame;
            } catch {
                reqFrame = undefined;
            }
            const isTrustedHostUrl = (u: string | undefined): boolean =>
                isTrustedAppUrl(u, activeCustomScheme) ||
                (isPopoutHost && (!u || u === "about:blank"));
            const fromTrustedTopFrame =
                !reqFrame ||
                (reqFrame.parent === null && isTrustedHostUrl(reqFrame.url));
            const fromTrustedSubFrameHost =
                fromTrustedTopFrame ||
                Boolean(
                    reqFrame?.parent &&
                        reqFrame.parent.parent === null &&
                        isTrustedHostUrl(reqFrame.parent.url)
                );

            const streamHdrs =
                fromTrustedTopFrame &&
                STREAM_ALLOWED_RESOURCE_TYPES.has(details.resourceType)
                    ? streamHeaderRules.get(origin)
                    : undefined;

            const isPreparedFrameReq = Boolean(
                fromTrustedSubFrameHost &&
                    !isTrustedAppUrl(details.url, activeCustomScheme) &&
                    frameHeaderRules.has(origin)
            );

            const frameHdrs =
                isPreparedFrameReq && details.resourceType === "subFrame"
                    ? frameHeaderRules.get(origin)
                    : undefined;

            const existingRefererKey = Object.keys(
                details.requestHeaders
            ).find((k) => k.toLowerCase() === "referer");
            const existingReferer = existingRefererKey
                ? details.requestHeaders[existingRefererKey]
                : undefined;
            const isAppReferer = Boolean(
                existingReferer &&
                    (existingReferer.startsWith(`${activeCustomScheme}:`) ||
                        existingReferer.startsWith("capacitor:"))
            );
            const fallbackSubFrameReferer =
                isPreparedFrameReq && (!existingReferer || isAppReferer)
                    ? `${origin}/`
                    : undefined;

            const embedFetchMetadataHeaders: Record<string, string> | undefined =
                isPreparedFrameReq
                    ? {
                          "Sec-Fetch-Site": "same-origin",
                          ...(details.resourceType === "subFrame"
                              ? { "Sec-Fetch-Dest": "document" }
                              : {}),
                      }
                    : undefined;

            const customHeaders =
                streamHdrs ||
                frameHdrs ||
                fallbackSubFrameReferer ||
                embedFetchMetadataHeaders
                    ? {
                          ...(embedFetchMetadataHeaders ?? {}),
                          ...(fallbackSubFrameReferer
                              ? { Referer: fallbackSubFrameReferer }
                              : {}),
                          ...(frameHdrs ?? {}),
                          ...(streamHdrs ?? {}),
                      }
                    : undefined;
            if (!customHeaders || Object.keys(customHeaders).length === 0) {
                callback({ requestHeaders: details.requestHeaders });
                return;
            }

            const nextHeaders: Record<string, string> = {
                ...details.requestHeaders,
            };
            for (const [key, value] of Object.entries(customHeaders)) {
                for (const existing of Object.keys(nextHeaders)) {
                    if (existing.toLowerCase() === key.toLowerCase()) {
                        delete nextHeaders[existing];
                    }
                }
                nextHeaders[key] = value;
            }

            callback({ requestHeaders: nextHeaders });
        }
    );

    session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
        const headers: Record<string, string | string[]> = {
            ...(details.responseHeaders ?? {}),
        };

        const origin = extractExactOrigin(details.url);
        const streamRule = origin ? streamHeaderRules.get(origin) : undefined;
        const isPreparedStream = Boolean(streamRule);
        const isMainWindowRequest =
            mainWebContentsId === null ||
            details.webContentsId === undefined ||
            details.webContentsId <= 0 ||
            details.webContentsId === mainWebContentsId ||
            trustedFrameHostIds.has(details.webContentsId);
        const isTrustedAppResponse = isTrustedAppUrl(
            details.url,
            activeCustomScheme
        );
        const isPreparedFrameOrigin = Boolean(
            origin &&
                isMainWindowRequest &&
                !isTrustedAppResponse &&
                frameHeaderRules.has(origin)
        );
        const isMediaStream = Boolean(
            origin && (details.resourceType === "media" || isPreparedStream)
        );

        if (isPreparedFrameOrigin && details.resourceType === "subFrame") {
            stripHeaderCaseInsensitive(headers, "x-frame-options");
            stripFrameAncestorsFromCsp(headers, "content-security-policy");
            stripFrameAncestorsFromCsp(
                headers,
                "content-security-policy-report-only"
            );
            stripHeaderCaseInsensitive(headers, "cross-origin-opener-policy");
            stripHeaderCaseInsensitive(headers, "cross-origin-embedder-policy");
            stripHeaderCaseInsensitive(headers, "cross-origin-resource-policy");
            headers["Cross-Origin-Resource-Policy"] = ["cross-origin"];
        }

        if (isPreparedFrameOrigin) {
            rewriteSetCookieSameSiteNone(
                headers,
                details.url.startsWith("https://")
            );
        }

        if (isMediaStream) {
            stripHeaderCaseInsensitive(headers, "access-control-allow-origin");
            stripHeaderCaseInsensitive(headers, "access-control-allow-headers");
            stripHeaderCaseInsensitive(headers, "access-control-allow-methods");
            stripHeaderCaseInsensitive(headers, "access-control-expose-headers");
            stripHeaderCaseInsensitive(headers, "cross-origin-resource-policy");

            const allowedHeaderNames = new Set<string>([
                "Authorization",
                "Content-Type",
                "Range",
                "If-Range",
                "Accept",
            ]);
            for (const key of Object.keys(streamRule ?? {})) {
                allowedHeaderNames.add(key);
            }

            headers["Access-Control-Allow-Origin"] = [appOrigin];
            headers["Vary"] = ["Origin"];
            headers["Access-Control-Allow-Methods"] = [
                "GET, HEAD, POST, OPTIONS",
            ];
            headers["Access-Control-Allow-Headers"] = [
                Array.from(allowedHeaderNames).join(", "),
            ];
            headers["Access-Control-Expose-Headers"] = [
                "Content-Length, Content-Range, Accept-Ranges, Content-Type, Location",
            ];
            headers["Cross-Origin-Resource-Policy"] = ["cross-origin"];
        } else if (isTrustedAppResponse) {
            headers["Content-Security-Policy"] = [
                `default-src ${activeCustomScheme}://* 'self' 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval' devtools://* http: https: data: blob:; ` +
                    `script-src ${activeCustomScheme}://* 'self' 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval' devtools://* http: https: data: blob:; ` +
                    `style-src ${activeCustomScheme}://* 'self' 'unsafe-inline' http: https: data: blob:; ` +
                    `font-src ${activeCustomScheme}://* 'self' http: https: data: blob:; ` +
                    `connect-src ${activeCustomScheme}://* 'self' http: https: ws: wss: data: blob:; ` +
                    `img-src ${activeCustomScheme}://* 'self' http: https: data: blob:; ` +
                    `media-src ${activeCustomScheme}://* 'self' http: https: data: blob:; ` +
                    `worker-src ${activeCustomScheme}://* 'self' http: https: data: blob:; ` +
                    `frame-src ${activeCustomScheme}://* 'self' http: https: data: blob:;`,
            ];
        }

        callback({ responseHeaders: headers });
    });
}

function getProcessParentPid(pid: number): number {
    try {
        const stat = readFileSync(`/proc/${pid}/stat`, "utf8");
        const closeParen = stat.lastIndexOf(")");
        if (closeParen === -1) return 0;
        const fields = stat
            .slice(closeParen + 2)
            .trim()
            .split(/\s+/);
        return parseInt(fields[1], 10) || 0;
    } catch {
        return 0;
    }
}

function isDescendantOfPid(pid: number, targetRootPid: number): boolean {
    if (!pid || !targetRootPid || isNaN(pid) || isNaN(targetRootPid)) {
        return false;
    }
    let curr = pid;
    for (let depth = 0; depth < 12 && curr > 1; depth++) {
        if (curr === targetRootPid) return true;
        const ppid = getProcessParentPid(curr);
        if (!ppid || ppid === curr) break;
        curr = ppid;
    }
    return false;
}

function isParadiseProcess(pid: number): boolean {
    if (!pid || isNaN(pid)) return false;
    const rootPids = new Set<number>([process.pid, process.ppid]);
    const appPathLower = app.getAppPath().toLowerCase();

    let curr = pid;
    for (let depth = 0; depth < 12 && curr > 1; depth++) {
        if (rootPids.has(curr)) return true;

        try {
            const cmdline = readFileSync(`/proc/${curr}/cmdline`, "utf8")
                .replace(/\0/g, " ")
                .toLowerCase();
            if (
                cmdline.includes("paradise") ||
                cmdline.includes(appPathLower)
            ) {
                return true;
            }
        } catch {}

        const ppid = getProcessParentPid(curr);
        if (!ppid || ppid === curr) break;
        curr = ppid;
    }
    return false;
}

async function listActiveAudioApps(): Promise<AudioAppCandidate[]> {
    const { stdout } = await execFileAsync("pw-dump", [], EXEC_OPTS);
    const objects: any[] = JSON.parse(stdout);
    const seen = new Map<string, AudioAppCandidate>();

    for (const obj of objects) {
        const props = obj?.info?.props;
        if (!props || props["media.class"] !== "Stream/Output/Audio") continue;

        const pid = parseInt(
            props["application.process.id"] || props["pipewire.sec.pid"],
            10
        );
        const rawApp = String(props["application.name"] || "");
        const rawNode = String(props["node.name"] || "");
        const rawBinary = String(props["application.process.binary"] || "");

        const appNameLower = rawApp.toLowerCase();
        const nodeNameLower = rawNode.toLowerCase();
        const binaryLower = rawBinary.toLowerCase();

        if (
            isParadiseProcess(pid) ||
            appNameLower.includes("paradise") ||
            nodeNameLower.includes("paradise")
        ) {
            continue;
        }

        const display = rawApp || rawNode || rawBinary || `PID ${pid}`;
        const key = `${display.toLowerCase()}::${binaryLower}`;

        if (!seen.has(key)) {
            seen.set(key, {
                label: pid ? `${display} (PID ${pid})` : display,
                appNameLower,
                nodeNameLower,
                binaryLower,
                pid: isNaN(pid) ? 0 : pid,
            });
        }
    }

    return [...seen.values()];
}

function doesNodeMatchSelectedAudio(props: any, pid: number): boolean {
    if (selectedAudioTarget === "NONE") return false;
    if (selectedAudioTarget === "ALL") return true;

    const targetPid = Number(selectedAudioTarget.pid || 0);
    if (targetPid && pid) {
        if (
            pid === targetPid ||
            isDescendantOfPid(pid, targetPid) ||
            isDescendantOfPid(targetPid, pid)
        ) {
            return true;
        }
    }

    const appName = String(props["application.name"] || "").toLowerCase();
    const nodeName = String(props["node.name"] || "").toLowerCase();
    const binary = String(
        props["application.process.binary"] || ""
    ).toLowerCase();

    const wantApp = String(selectedAudioTarget.appNameLower || "").toLowerCase();
    const wantBin = String(selectedAudioTarget.binaryLower || "").toLowerCase();
    const wantNode = String(selectedAudioTarget.nodeNameLower || "").toLowerCase();

    if (wantApp && appName === wantApp) return true;
    if (wantBin && binary === wantBin) return true;
    if (wantNode && nodeName === wantNode) return true;

    return false;
}

async function getSourceOutputIds(): Promise<Set<number>> {
    const { stdout } = await execFileAsync(
        "pactl",
        ["list", "source-outputs", "short"],
        EXEC_OPTS
    );
    const ids = new Set<number>();
    for (const line of stdout.trim().split("\n")) {
        const id = parseInt(line.split(/\s+/)[0], 10);
        if (!isNaN(id)) ids.add(id);
    }
    return ids;
}

async function ensureNullSink(): Promise<void> {
    const { stdout } = await execFileAsync(
        "pactl",
        ["list", "sinks", "short"],
        EXEC_OPTS
    );
    if (!stdout.includes(SINK_NAME)) {
        await execFileAsync(
            "pactl",
            [
                "load-module",
                "module-null-sink",
                `sink_name=${SINK_NAME}`,
                "sink_properties=device.description=Paradise_ScreenShare",
            ],
            EXEC_OPTS
        );
    }
}

function unloadNullSinkSync(): void {
    if (process.platform !== "linux") return;
    try {
        const stdout = execFileSync("pactl", ["list", "modules", "short"], {
            ...EXEC_OPTS,
            encoding: "utf8",
        });
        for (const line of stdout.trim().split("\n")) {
            if (
                line.includes("module-null-sink") &&
                line.includes(`sink_name=${SINK_NAME}`)
            ) {
                const modId = line.split(/\s+/)[0];
                if (modId) {
                    execFileSync("pactl", ["unload-module", modId], EXEC_OPTS);
                }
            }
        }
    } catch {}
}

async function resetNullSink(): Promise<void> {
    unloadNullSinkSync();
    await ensureNullSink();
}

function stopPipewireRouting(): void {
    isScreenShareActive = false;
    if (pipewireInterval) {
        clearInterval(pipewireInterval);
        pipewireInterval = null;
    }
    baselineSourceOutputIds.clear();
    selectedAudioTarget = "NONE";
    unloadNullSinkSync();
}

app.on("will-quit", () => {
    stopPipewireRouting();
});

process.on("SIGINT", () => {
    stopPipewireRouting();
    process.exit(0);
});

process.on("SIGTERM", () => {
    stopPipewireRouting();
    process.exit(0);
});

async function moveNewLoopbackStreamsToNullSink(): Promise<number> {
    const currentIds = await getSourceOutputIds();
    const newIds = [...currentIds].filter(
        (id) => !baselineSourceOutputIds.has(id)
    );

    for (const id of newIds) {
        await execFileAsync(
            "pactl",
            ["move-source-output", String(id), `${SINK_NAME}.monitor`],
            EXEC_OPTS
        ).catch(() => {});
    }

    return newIds.length;
}

async function syncAppStreamsToNullSink(): Promise<void> {
    if (!isScreenShareActive) return;

    await ensureNullSink().catch(() => {});
    await moveNewLoopbackStreamsToNullSink().catch(() => {});

    const { stdout } = await execFileAsync("pw-dump", [], EXEC_OPTS);
    const objects: any[] = JSON.parse(stdout);

    const nullSinkNode = objects.find(
        (obj) =>
            obj?.info?.props?.["media.class"] === "Audio/Sink" &&
            obj?.info?.props?.["node.name"] === SINK_NAME
    );
    if (!nullSinkNode) return;

    const sinkPorts = objects.filter(
        (p) =>
            p.type === "PipeWire:Interface:Port" &&
            p?.info?.props?.["node.id"] === nullSinkNode.id &&
            p?.info?.props?.["port.direction"] === "in"
    );

    const inFL = sinkPorts.find(
        (p) => p?.info?.props?.["audio.channel"] === "FL"
    );
    const inFR = sinkPorts.find(
        (p) => p?.info?.props?.["audio.channel"] === "FR"
    );
    if (!inFL || !inFR) return;

    const existingLinks = objects.filter(
        (obj) =>
            obj.type === "PipeWire:Interface:Link" &&
            (obj?.info?.["input-port-id"] === inFL.id ||
                obj?.info?.["input-port-id"] === inFR.id)
    );

    const alreadyLinkedOutPorts = new Set<number>();

    for (const link of existingLinks) {
        const outNode = objects.find(
            (o) => o.id === link?.info?.["output-node-id"]
        );
        const outProps = outNode?.info?.props || {};
        const outPid = parseInt(
            outProps["application.process.id"] || outProps["pipewire.sec.pid"],
            10
        );
        if (
            isParadiseProcess(outPid) ||
            !doesNodeMatchSelectedAudio(outProps, outPid)
        ) {
            await execFileAsync(
                "pw-link",
                ["-d", String(link.id)],
                EXEC_OPTS
            ).catch(() => {});
            console.log(`[PipeWire] Unlinked link ${link.id} from ${SINK_NAME}`);
        } else {
            alreadyLinkedOutPorts.add(link?.info?.["output-port-id"]);
        }
    }

    for (const obj of objects) {
        const props = obj?.info?.props;
        if (!props || props["media.class"] !== "Stream/Output/Audio") continue;

        const pid = parseInt(
            props["application.process.id"] || props["pipewire.sec.pid"],
            10
        );
        const appName = String(props["application.name"] || "").toLowerCase();
        const nodeName = String(props["node.name"] || "").toLowerCase();

        if (
            isParadiseProcess(pid) ||
            appName.includes("paradise") ||
            nodeName.includes("paradise") ||
            !doesNodeMatchSelectedAudio(props, pid)
        ) {
            continue;
        }

        const outPorts = objects.filter(
            (p) =>
                p.type === "PipeWire:Interface:Port" &&
                p?.info?.props?.["node.id"] === obj.id &&
                p?.info?.props?.["port.direction"] === "out"
        );

        const outFL = outPorts.find(
            (p) => p?.info?.props?.["audio.channel"] === "FL"
        );
        const outFR = outPorts.find(
            (p) => p?.info?.props?.["audio.channel"] === "FR"
        );

        if (outFL && !alreadyLinkedOutPorts.has(outFL.id)) {
            await execFileAsync(
                "pw-link",
                [String(outFL.id), String(inFL.id)],
                EXEC_OPTS
            ).catch(() => {});
            console.log(
                `[PipeWire] Linked ${props["application.name"] || props["node.name"]} (PID ${pid}) -> ${SINK_NAME}`
            );
        }
        if (outFR && !alreadyLinkedOutPorts.has(outFR.id)) {
            await execFileAsync(
                "pw-link",
                [String(outFR.id), String(inFR.id)],
                EXEC_OPTS
            ).catch(() => {});
        }
    }
}

let ipcInitialized = false;

export function setupAudioIsolationIpc(): void {
    if (ipcInitialized) return;
    ipcInitialized = true;

    ipcMain.handle("paradise:audio-isolation-supported", (event) => {
        assertTrustedIpcSender(event);
        return process.platform === "linux";
    });

    ipcMain.handle("paradise:list-audio-apps", async (event) => {
        assertTrustedIpcSender(event);
        if (process.platform !== "linux") return [];
        return await listActiveAudioApps().catch(() => []);
    });

    ipcMain.handle("paradise:get-audio-target", (event) => {
        assertTrustedIpcSender(event);
        return selectedAudioTarget;
    });

    ipcMain.handle(
        "paradise:set-audio-target",
        async (event, target: AudioAppCandidate | "ALL" | "NONE") => {
            assertTrustedIpcSender(event);
            if (process.platform !== "linux") return false;
            selectedAudioTarget = target;
            console.log("[PipeWire] Switched audio target via UI:", target);
            await syncAppStreamsToNullSink().catch((e) =>
                console.error(
                    "[PipeWire] Target switch sync error:",
                    e.message
                )
            );
            return true;
        }
    );
}

export function setupDisplayMedia(): void {
    setupAudioIsolationIpc();

    session.defaultSession.setDisplayMediaRequestHandler(
        async (request, callback) => {
            const sources = await desktopCapturer.getSources({
                types: ["window", "screen"],
            });

            if (sources.length === 0) {
                callback({});
                return;
            }

            stopPipewireRouting();

            const wantsAudio = Boolean(request.audioRequested);

            if (wantsAudio && process.platform === "linux") {
                isScreenShareActive = true;
                selectedAudioTarget = "ALL";
                await resetNullSink().catch((e) =>
                    console.error(
                        "[PipeWire] resetNullSink failed:",
                        e.message
                    )
                );
                baselineSourceOutputIds = await getSourceOutputIds().catch(
                    () => new Set<number>()
                );
            }

            const response: Electron.Streams = {
                video: sources[0],
            };

            if (wantsAudio) {
                response.audio = "loopback";
            }

            callback(response);

            if (wantsAudio && process.platform === "linux") {
                setTimeout(() => {
                    syncAppStreamsToNullSink().catch((e) =>
                        console.error("[PipeWire] Initial sync error:", e.message)
                    );
                }, 500);

                pipewireInterval = setInterval(() => {
                    syncAppStreamsToNullSink().catch((e) =>
                        console.error("[PipeWire] Sync error:", e.message)
                    );
                }, 2000);
            }
        },
        { useSystemPicker: false }
    );
}

const reloadWatcher = {
    debouncer: null,
    ready: false,
    watcher: null,
};

export function setupReloadWatcher(
    electronCapacitorApp: ElectronCapacitorApp
): void {
    reloadWatcher.watcher = chokidar
        .watch(join(app.getAppPath(), "app"), {
            ignored: /[/\\]\./,
            persistent: true,
        })
        .on("ready", () => {
            reloadWatcher.ready = true;
        })
        .on("all", (_event, _path) => {
            if (reloadWatcher.ready) {
                clearTimeout(reloadWatcher.debouncer);
                reloadWatcher.debouncer = setTimeout(async () => {
                    electronCapacitorApp.getMainWindow().webContents.reload();
                    reloadWatcher.ready = false;
                    clearTimeout(reloadWatcher.debouncer);
                    reloadWatcher.debouncer = null;
                    reloadWatcher.watcher = null;
                    setupReloadWatcher(electronCapacitorApp);
                }, 1500);
            }
        });
}

export class ElectronCapacitorApp {
    private MainWindow: BrowserWindow | null = null;
    private SplashScreen: CapacitorSplashScreen | null = null;
    private TrayIcon: Tray | null = null;
    private CustomTrayItems: DynamicTrayItem[] = [];
    private CloseToTrayEnabled = true;
    private CapacitorFileConfig: CapacitorElectronConfig;
    private TrayMenuTemplate: (MenuItem | MenuItemConstructorOptions)[] = [
        new MenuItem({ label: "Quit App", role: "quit" }),
    ];
    private AppMenuBarMenuTemplate: (MenuItem | MenuItemConstructorOptions)[] =
        [
            { role: process.platform === "darwin" ? "appMenu" : "fileMenu" },
            { role: "viewMenu" },
        ];
    private mainWindowState;
    private loadWebApp;
    private customScheme: string;

    constructor(
        capacitorFileConfig: CapacitorElectronConfig,
        trayMenuTemplate?: (MenuItemConstructorOptions | MenuItem)[],
        appMenuBarMenuTemplate?: (MenuItemConstructorOptions | MenuItem)[]
    ) {
        this.CapacitorFileConfig = capacitorFileConfig;

        this.customScheme =
            this.CapacitorFileConfig.electron?.customUrlScheme ??
            "capacitor-electron";

        if (trayMenuTemplate) {
            this.TrayMenuTemplate = trayMenuTemplate;
        }

        if (appMenuBarMenuTemplate) {
            this.AppMenuBarMenuTemplate = appMenuBarMenuTemplate;
        }

        const webAppDir = join(app.getAppPath(), "app");
        if (!existsSync(join(webAppDir, "index.html"))) {
            console.error(
                `[Paradise] Missing web bundle at ${join(webAppDir, "index.html")}`
            );
        }

        this.loadWebApp = electronServe({
            directory: webAppDir,
            scheme: this.customScheme,
        });
    }

    private async loadMainWindow(thisRef: any) {
        try {
            await session.defaultSession.clearCache();
            await session.defaultSession.clearStorageData({
                storages: ["serviceworkers", "cachestorage"],
            });
        } catch (err) {
            console.warn("[Paradise] Failed to clear stale web cache:", err);
        }
        await thisRef.loadWebApp(thisRef.MainWindow);
    }

    getMainWindow(): BrowserWindow {
        return this.MainWindow;
    }

    getCustomURLScheme(): string {
        return this.customScheme;
    }

    getCloseToTrayEnabled(): boolean {
        return this.CloseToTrayEnabled;
    }

    setCloseToTrayEnabled(enabled: boolean): void {
        this.CloseToTrayEnabled = Boolean(enabled);
    }

    updateTrayMenu(customItems: DynamicTrayItem[] = this.CustomTrayItems): void {
        this.CustomTrayItems = customItems;
        if (!this.TrayIcon) return;

        const dynamicTemplate: MenuItemConstructorOptions[] = customItems.map(
            (item) => {
                if (item.type === "separator") {
                    return { type: "separator" };
                }
                return {
                    label: item.label || "",
                    type: item.type || "normal",
                    checked: item.checked,
                    enabled: item.enabled ?? true,
                    click: () => {
                        if (
                            item.id &&
                            this.MainWindow &&
                            !this.MainWindow.isDestroyed()
                        ) {
                            this.MainWindow.webContents.send(
                                "paradise:tray-action",
                                item.id
                            );
                        }
                    },
                };
            }
        );

        const isVisible =
            this.MainWindow && !this.MainWindow.isDestroyed()
                ? this.MainWindow.isVisible()
                : false;

        const baseTemplate: (MenuItem | MenuItemConstructorOptions)[] = [
            {
                label: isVisible ? "Hide Paradise" : "Show Paradise",
                click: () => {
                    if (!this.MainWindow || this.MainWindow.isDestroyed()) {
                        return;
                    }
                    if (this.MainWindow.isVisible()) {
                        this.MainWindow.hide();
                    } else {
                        this.MainWindow.show();
                        this.MainWindow.focus();
                    }
                    this.updateTrayMenu();
                },
            },
            { type: "separator" },
            ...this.TrayMenuTemplate,
        ];

        const fullTemplate =
            dynamicTemplate.length > 0
                ? [
                      ...dynamicTemplate,
                      { type: "separator" as const },
                      ...baseTemplate,
                  ]
                : baseTemplate;

        this.TrayIcon.setContextMenu(Menu.buildFromTemplate(fullTemplate));
    }

    async init(): Promise<void> {
        const icon = nativeImage.createFromPath(
            join(
                app.getAppPath(),
                "assets",
                process.platform === "win32" ? "appIcon.ico" : "appIcon.png"
            )
        );
        this.mainWindowState = windowStateKeeper({
            defaultWidth: 1000,
            defaultHeight: 800,
        });
        const preloadPath = join(
            app.getAppPath(),
            "build",
            "src",
            "preload.js"
        );

        setupCapacitorElectronPlugins();
        setupDisplayMedia();
        setupParadiseProxyIpc();
        if (!webRequestInstalled) {
            installStreamCorsWebRequest();
        }

        this.MainWindow = new BrowserWindow({
            icon,
            show: false,
            frame: false,
            autoHideMenuBar: true,
            titleBarStyle: process.platform === "darwin" ? "hiddenInset" : "hidden",
            x: this.mainWindowState.x,
            y: this.mainWindowState.y,
            width: this.mainWindowState.width,
            height: this.mainWindowState.height,
            webPreferences: {
                nodeIntegration: true,
                contextIsolation: true,
                sandbox: false,
                backgroundThrottling: false,
                preload: preloadPath,
            },
        });

        mainWebContentsId = this.MainWindow.webContents.id;

        this.MainWindow.webContents.on(
            "did-start-navigation",
            (_event, _url, _isInPlace, isMainFrame) => {
                if (isMainFrame) {
                    streamHeaderRules.clear();
                    frameHeaderRules.clear();
                    trustedFrameHostIds.clear();
                }
            }
        );

        this.MainWindow.on("maximize", () => {
            if (this.MainWindow && !this.MainWindow.isDestroyed()) {
                this.MainWindow.webContents.send("paradise:window-maximized", true);
            }
        });

        this.MainWindow.on("unmaximize", () => {
            if (this.MainWindow && !this.MainWindow.isDestroyed()) {
                this.MainWindow.webContents.send("paradise:window-maximized", false);
            }
        });

        ipcMain.removeHandler("paradise:window-minimize");
        ipcMain.handle("paradise:window-minimize", (event) => {
            assertTrustedIpcSender(event);
            if (this.MainWindow && !this.MainWindow.isDestroyed()) {
                this.MainWindow.minimize();
            }
        });

        ipcMain.removeHandler("paradise:window-toggle-maximize");
        ipcMain.handle("paradise:window-toggle-maximize", (event) => {
            assertTrustedIpcSender(event);
            if (!this.MainWindow || this.MainWindow.isDestroyed()) return false;
            if (this.MainWindow.isMaximized()) {
                this.MainWindow.unmaximize();
                return false;
            } else {
                this.MainWindow.maximize();
                return true;
            }
        });

        ipcMain.removeHandler("paradise:window-close");
        ipcMain.handle("paradise:window-close", (event) => {
            assertTrustedIpcSender(event);
            if (this.MainWindow && !this.MainWindow.isDestroyed()) {
                this.MainWindow.close();
            }
        });

        ipcMain.removeHandler("paradise:window-is-maximized");
        ipcMain.handle("paradise:window-is-maximized", (event) => {
            assertTrustedIpcSender(event);
            if (!this.MainWindow || this.MainWindow.isDestroyed()) return false;
            return this.MainWindow.isMaximized();
        });

        ipcMain.removeHandler("paradise:window-toggle-devtools");
        ipcMain.handle("paradise:window-toggle-devtools", (event) => {
            assertTrustedIpcSender(event);
            if (this.MainWindow && !this.MainWindow.isDestroyed()) {
                this.MainWindow.webContents.toggleDevTools();
            }
        });

        const activeOsNotifications = new Set<Notification>();

        ipcMain.removeHandler("paradise:notifications-supported");
        ipcMain.handle("paradise:notifications-supported", (event) => {
            assertTrustedIpcSender(event);
            return Notification.isSupported();
        });

        ipcMain.removeHandler("paradise:show-notification");
        ipcMain.handle("paradise:show-notification", (event, opts: any) => {
            assertTrustedIpcSender(event);
            if (!Notification.isSupported()) return false;
            const title =
                typeof opts?.title === "string" && opts.title.trim()
                    ? opts.title.trim()
                    : "Paradise";
            const body = typeof opts?.body === "string" ? opts.body : "";
            const roomId =
                typeof opts?.roomId === "string" ? opts.roomId : undefined;
            const eventId =
                typeof opts?.eventId === "string" ? opts.eventId : undefined;
            const silent = opts?.silent !== false;

            const notif = new Notification({ title, body, silent });
            activeOsNotifications.add(notif);
            const cleanup = () => activeOsNotifications.delete(notif);
            notif.on("close", cleanup);
            notif.on("failed", cleanup);
            notif.on("click", () => {
                cleanup();
                if (this.MainWindow && !this.MainWindow.isDestroyed()) {
                    if (this.MainWindow.isMinimized()) {
                        this.MainWindow.restore();
                    }
                    this.MainWindow.show();
                    this.MainWindow.focus();
                    if (roomId) {
                        this.MainWindow.webContents.send(
                            "paradise:notification-clicked",
                            { roomId, eventId }
                        );
                    }
                }
            });
            notif.show();
            return true;
        });

        this.mainWindowState.manage(this.MainWindow);

        if (this.CapacitorFileConfig.backgroundColor) {
            this.MainWindow.setBackgroundColor(
                this.CapacitorFileConfig.electron.backgroundColor
            );
        }

        this.MainWindow.on("show", () => {
            this.updateTrayMenu();
        });

        this.MainWindow.on("hide", () => {
            this.updateTrayMenu();
        });

        this.MainWindow.on("closed", () => {
            mainWebContentsId = null;
            streamHeaderRules.clear();
            frameHeaderRules.clear();
            trustedFrameHostIds.clear();
            stopPipewireRouting();
            if (
                this.SplashScreen?.getSplashWindow() &&
                !this.SplashScreen.getSplashWindow().isDestroyed()
            ) {
                this.SplashScreen.getSplashWindow().close();
            }
        });

        if (this.CapacitorFileConfig.electron?.trayIconAndMenuEnabled) {
            this.TrayIcon = new Tray(icon);
            this.TrayIcon.on("double-click", () => {
                if (this.MainWindow) {
                    if (this.MainWindow.isVisible()) {
                        this.MainWindow.hide();
                    } else {
                        this.MainWindow.show();
                        this.MainWindow.focus();
                    }
                }
            });
            this.TrayIcon.on("click", () => {
                if (this.MainWindow) {
                    if (this.MainWindow.isVisible()) {
                        this.MainWindow.hide();
                    } else {
                        this.MainWindow.show();
                        this.MainWindow.focus();
                    }
                }
            });
            this.TrayIcon.setToolTip(app.getName());
            this.updateTrayMenu([]);
        }

        ipcMain.removeHandler("paradise:set-tray-menu");
        ipcMain.handle(
            "paradise:set-tray-menu",
            (event, items: DynamicTrayItem[]) => {
                assertTrustedIpcSender(event);
                this.updateTrayMenu(Array.isArray(items) ? items : []);
                return true;
            }
        );

        ipcMain.removeHandler("paradise:set-tray-tooltip");
        ipcMain.handle(
            "paradise:set-tray-tooltip",
            (event, tooltip: string) => {
                assertTrustedIpcSender(event);
                if (this.TrayIcon) {
                    this.TrayIcon.setToolTip(tooltip || app.getName());
                }
                return true;
            }
        );

        ipcMain.removeHandler("paradise:set-close-to-tray");
        ipcMain.handle(
            "paradise:set-close-to-tray",
            (event, enabled: boolean) => {
                assertTrustedIpcSender(event);
                this.setCloseToTrayEnabled(Boolean(enabled));
                return this.getCloseToTrayEnabled();
            }
        );

        if (process.platform === "darwin") {
            Menu.setApplicationMenu(
                Menu.buildFromTemplate(this.AppMenuBarMenuTemplate)
            );
        } else {
            Menu.setApplicationMenu(null);
        }

        this.MainWindow.webContents.setWindowOpenHandler((details) => {
            const isBlankPopout =
                !details.url || details.url === "about:blank";
            if (!isBlankPopout && !isTrustedAppUrl(details.url, this.customScheme)) {
                return { action: "deny" };
            }
            if (isBlankPopout || details.frameName === "paradise-cast-stage") {
                return {
                    action: "allow",
                    overrideBrowserWindowOptions: {
                        width: 1280,
                        height: 720,
                        frame: true,
                        titleBarStyle: "default",
                        autoHideMenuBar: true,
                        backgroundColor: "#000000",
                        title:
                            details.frameName === "paradise-cast-stage"
                                ? "Paradise Cast"
                                : "Paradise",
                        webPreferences: {
                            nodeIntegration: false,
                            contextIsolation: true,
                            preload: undefined,
                        },
                    },
                };
            }
            return {
                action: "allow",
                overrideBrowserWindowOptions: {
                    webPreferences: {
                        nodeIntegration: false,
                        contextIsolation: true,
                        sandbox: true,
                        preload: undefined,
                    },
                },
            };
        });

        this.MainWindow.webContents.on(
            "did-create-window",
            (childWindow, details) => {
                const isBlankPopout =
                    !details.url || details.url === "about:blank";
                if (isBlankPopout || details.frameName === "paradise-cast-stage") {
                    const popoutWcId = childWindow.webContents.id;
                    trustedFrameHostIds.add(popoutWcId);
                    childWindow.on("closed", () => {
                        trustedFrameHostIds.delete(popoutWcId);
                    });
                    childWindow.webContents.on("will-navigate", (ev) => {
                        ev.preventDefault();
                    });
                    childWindow.webContents.setWindowOpenHandler(() => ({
                        action: "deny",
                    }));
                }
            }
        );

        this.MainWindow.webContents.on("will-navigate", (event, newURL) => {
            if (!isTrustedAppUrl(newURL, this.customScheme)) {
                event.preventDefault();
            }
        });

        if (this.CapacitorFileConfig.electron?.splashScreenEnabled) {
            this.SplashScreen = new CapacitorSplashScreen({
                imageFilePath: join(
                    app.getAppPath(),
                    "assets",
                    this.CapacitorFileConfig.electron?.splashScreenImageName ??
                        "splash.png"
                ),
                windowWidth: 400,
                windowHeight: 400,
            });
            this.SplashScreen.init(this.loadMainWindow, this);
        } else {
            this.loadMainWindow(this);
        }

        this.MainWindow.webContents.on("dom-ready", () => {
            if (this.CapacitorFileConfig.electron?.splashScreenEnabled) {
                this.SplashScreen.getSplashWindow().hide();
            }
            if (!this.CapacitorFileConfig.electron?.hideMainWindowOnLaunch) {
                this.MainWindow.show();
            }
            setTimeout(() => {
                if (electronIsDev) {
                    this.MainWindow.webContents.openDevTools();
                }
                CapElectronEventEmitter.emit(
                    "CAPELECTRON_DeeplinkListenerInitialized",
                    ""
                );
            }, 400);
        });
    }
}

export function setupContentSecurityPolicy(customScheme: string): void {
    installStreamCorsWebRequest(customScheme);
}

