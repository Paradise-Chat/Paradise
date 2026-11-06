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
