import { contextBridge, ipcRenderer } from "electron";

const isTrustedAppFrame =
    typeof window !== "undefined" &&
    window === window.top &&
    window.location?.protocol === "capacitor-electron:";

if (isTrustedAppFrame) {
    require("./rt/electron-rt");
    console.log("User Preload!");

    contextBridge.exposeInMainWorld("paradiseAudio", {
        isSupported: () =>
            ipcRenderer.invoke("paradise:audio-isolation-supported"),
        listApps: () => ipcRenderer.invoke("paradise:list-audio-apps"),
        getTarget: () => ipcRenderer.invoke("paradise:get-audio-target"),
        setTarget: (target: any) =>
            ipcRenderer.invoke("paradise:set-audio-target", target),
    });

    contextBridge.exposeInMainWorld("paradiseTray", {
        setMenu: (items: any[]) =>
            ipcRenderer.invoke("paradise:set-tray-menu", items),
        setTooltip: (tooltip: string) =>
            ipcRenderer.invoke("paradise:set-tray-tooltip", tooltip),
        setCloseToTray: (enabled: boolean) =>
            ipcRenderer.invoke("paradise:set-close-to-tray", Boolean(enabled)),
        onAction: (callback: (actionId: string) => void) => {
            const listener = (_event: any, actionId: string) =>
                callback(actionId);
            ipcRenderer.on("paradise:tray-action", listener);
            return () =>
                ipcRenderer.removeListener("paradise:tray-action", listener);
        },
    });

    contextBridge.exposeInMainWorld("paradiseNotifications", {
        isSupported: () =>
            ipcRenderer.invoke("paradise:notifications-supported"),
        show: (opts: {
            title?: string;
            body?: string;
            roomId?: string;
            eventId?: string;
            silent?: boolean;
        }) => ipcRenderer.invoke("paradise:show-notification", opts),
        onClicked: (
            callback: (payload: { roomId?: string; eventId?: string }) => void
        ) => {
            const listener = (
                _event: any,
                payload: { roomId?: string; eventId?: string }
            ) => callback(payload);
            ipcRenderer.on("paradise:notification-clicked", listener);
            return () =>
                ipcRenderer.removeListener(
                    "paradise:notification-clicked",
                    listener
                );
        },
    });

    contextBridge.exposeInMainWorld("paradiseShortcuts", {
        setBindings: (bindings: any[]) =>
            ipcRenderer.invoke("paradise:set-global-shortcuts", bindings),
        onTriggered: (callback: (actionId: string) => void) => {
            const listener = (_event: any, actionId: string) =>
                callback(actionId);
            ipcRenderer.on("paradise:shortcut-triggered", listener);
            return () =>
                ipcRenderer.removeListener(
                    "paradise:shortcut-triggered",
                    listener
                );
        },
    });

    contextBridge.exposeInMainWorld("paradiseWindow", {
        platform: process.platform,
        minimize: () => ipcRenderer.invoke("paradise:window-minimize"),
        toggleMaximize: () =>
            ipcRenderer.invoke("paradise:window-toggle-maximize"),
        close: () => ipcRenderer.invoke("paradise:window-close"),
        isMaximized: () => ipcRenderer.invoke("paradise:window-is-maximized"),
        toggleDevTools: () =>
            ipcRenderer.invoke("paradise:window-toggle-devtools"),
        onMaximizedChange: (callback: (maximized: boolean) => void) => {
            const listener = (_event: any, maximized: boolean) =>
                callback(Boolean(maximized));
            ipcRenderer.on("paradise:window-maximized", listener);
            return () =>
                ipcRenderer.removeListener(
                    "paradise:window-maximized",
                    listener
                );
        },
    });

    contextBridge.exposeInMainWorld("paradiseProxy", {
        provider: "electron",
        version: "1.0",
        isSupported: () => Promise.resolve(true),
        request: (opts: any) =>
            ipcRenderer.invoke("paradise-proxy:request", opts),
        prepareStream: (opts: any) =>
            ipcRenderer.invoke("paradise-proxy:prepare-stream", opts),
        clearStream: (urlOrHost?: string) =>
            ipcRenderer.invoke("paradise-proxy:clear-stream", urlOrHost),
        prepareFrame: (opts: any) =>
            ipcRenderer.invoke("paradise-proxy:prepare-frame", opts),
        clearFrame: (urlOrHost?: string) =>
            ipcRenderer.invoke("paradise-proxy:clear-frame", urlOrHost),
        prepareWebSocket: (opts: any) =>
            ipcRenderer.invoke("paradise-proxy:prepare-websocket", opts),
        clearWebSocket: (urlOrHost?: string) =>
            ipcRenderer.invoke("paradise-proxy:clear-websocket", urlOrHost),
        openAuthWindow: (opts: any) =>
            ipcRenderer.invoke("paradise-proxy:open-auth-window", opts),
        closeAuthWindow: () =>
            ipcRenderer.invoke("paradise-proxy:close-auth-window"),
        openExternal: (url: string) =>
            ipcRenderer.invoke("paradise-proxy:open-external", url),
    });

    const announceElectronProxyReady = () => {
        try {
            window.dispatchEvent(
                new CustomEvent("paradise:proxy-ready", {
                    detail: { provider: "electron", version: "1.0" },
                })
            );
        } catch {
        }
    };

    announceElectronProxyReady();
    window.addEventListener("DOMContentLoaded", announceElectronProxyReady, {
        once: true,
    });
}

