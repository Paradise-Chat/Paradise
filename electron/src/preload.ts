require('./rt/electron-rt');
//////////////////////////////
// User Defined Preload scripts below
console.log('User Preload!');


import { contextBridge, ipcRenderer } from "electron";



contextBridge.exposeInMainWorld("paradiseAudio", {
    isSupported: () => ipcRenderer.invoke("paradise:audio-isolation-supported"),
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
    onAction: (callback: (actionId: string) => void) => {
        const listener = (_event: any, actionId: string) => callback(actionId);
        ipcRenderer.on("paradise:tray-action", listener);
        return () =>
            ipcRenderer.removeListener("paradise:tray-action", listener);
    },
});


contextBridge.exposeInMainWorld("paradiseShortcuts", {
    setBindings: (bindings: any[]) =>
        ipcRenderer.invoke("paradise:set-global-shortcuts", bindings),
    onTriggered: (callback: (actionId: string) => void) => {
        const listener = (_event: any, actionId: string) => callback(actionId);
        ipcRenderer.on("paradise:shortcut-triggered", listener);
        return () =>
            ipcRenderer.removeListener("paradise:shortcut-triggered", listener);
    },
});




