import type { CapacitorElectronConfig } from "@capacitor-community/electron";
import {
    getCapacitorElectronConfig,
    setupElectronDeepLinking,
} from "@capacitor-community/electron";
import type { MenuItemConstructorOptions } from "electron";
import {
    app,
    MenuItem,
    Tray,
    Menu,
    nativeImage,
    dialog,
    ipcMain,
    globalShortcut
} from "electron";
import electronIsDev from "electron-is-dev";
import unhandled from "electron-unhandled";
import { autoUpdater } from "electron-updater";
import { join } from "path";
import { existsSync, mkdirSync, writeFileSync } from "fs";
import { homedir } from "os";
import type { DynamicTrayItem } from "./setup";
import {
    ElectronCapacitorApp,
    setupContentSecurityPolicy,
    setupReloadWatcher,
} from "./setup";

if (process.platform === "linux") {
    try {
        const desktopId = "com.gigiaj.paradise.desktop";
        const appsDir = join(homedir(), ".local", "share", "applications");
        const desktopFile = join(appsDir, desktopId);
        mkdirSync(appsDir, { recursive: true });
        writeFileSync(
            desktopFile,
            [
                "[Desktop Entry]",
                "Type=Application",
                "Name=Paradise",
                "Comment=Paradise Voice and Chat",
                `Exec=${process.execPath} %U`,
                "Icon=paradise",
                "Terminal=false",
                "Categories=Network;InstantMessaging;",
                "StartupWMClass=Paradise",
                "",
            ].join("\n"),
            "utf8"
        );
        process.env.CHROME_DESKTOP = desktopId;
        if (typeof (app as any).setDesktopName === "function") {
            (app as any).setDesktopName(desktopId);
        }
    } catch {}
}

app.setName("Paradise");

app.commandLine.appendSwitch("ozone-platform-hint", "auto");
app.commandLine.appendSwitch(
    "enable-features",
    "WaylandWindowDecorations,WebRTCPipeWireCapturer,WebRtcAllowInputVolumeAdjustment,PulseaudioLoopbackForScreenShare,MacLoopbackAudioForScreenShare,MacCatapSystemAudioLoopbackCapture,GlobalShortcutsPortal"
);
unhandled();

const appMenuBarMenuTemplate: (MenuItemConstructorOptions | MenuItem)[] = [
    { role: process.platform === "darwin" ? "appMenu" : "fileMenu" },
    { role: "viewMenu" },
];

const capacitorFileConfig: CapacitorElectronConfig =
    getCapacitorElectronConfig();

const myCapacitorApp = new ElectronCapacitorApp(
    capacitorFileConfig,
    null,
    appMenuBarMenuTemplate
);


const gotTheLock = app.requestSingleInstanceLock();
    if (!gotTheLock) {
        app.quit();
        process.exit(0);
    }

app.on("second-instance", (_event, commandLine) => {
    const win = myCapacitorApp.getMainWindow();
    const actionArg = commandLine.find((arg) => arg.startsWith("--action="));
    if (actionArg) {
        const actionId = actionArg.slice("--action=".length).trim();
        if (actionId && win && !win.isDestroyed()) {
            console.log(`[Shortcuts] Triggered via CLI flag: ${actionId}`);
            win.webContents.send("paradise:shortcut-triggered", actionId);
        }
        return;
    }
    if (win && !win.isDestroyed()) {
        if (win.isMinimized()) win.restore();
        win.show();
        win.focus();
    }
});


if (capacitorFileConfig.electron?.deepLinkingEnabled) {
    setupElectronDeepLinking(myCapacitorApp, {
        customProtocol:
            capacitorFileConfig.electron.deepLinkingCustomProtocol ??
            "mycapacitorapp",
    });
}

if (electronIsDev) {
    setupReloadWatcher(myCapacitorApp);
}

let customTray: Tray | null = null;
let currentTrayItems: DynamicTrayItem[] = [];
let isQuitting = false;

function rebuildCustomTrayMenu(
    customItems: DynamicTrayItem[] = currentTrayItems
): void {
    currentTrayItems = customItems;
    if (!customTray) return;

    const win = myCapacitorApp.getMainWindow();
    const isVisible = win && !win.isDestroyed() ? win.isVisible() : false;

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
                    const activeWin = myCapacitorApp.getMainWindow();
                    if (item.id && activeWin && !activeWin.isDestroyed()) {
                        activeWin.webContents.send(
                            "paradise:tray-action",
                            item.id
                        );
                    }
                },
            };
        }
    );

    const baseTemplate: MenuItemConstructorOptions[] = [
        {
            label: isVisible ? "Hide Paradise" : "Open Paradise",
            click: () => {
                const activeWin = myCapacitorApp.getMainWindow();
                if (!activeWin || activeWin.isDestroyed()) return;
                if (activeWin.isVisible()) {
                    activeWin.hide();
                } else {
                    activeWin.show();
                    activeWin.focus();
                }
                rebuildCustomTrayMenu();
            },
        },
        { type: "separator" },
        {
            label: "Quit",
            click: () => {
                isQuitting = true;
                app.quit();
            },
        },
    ];

    const fullTemplate: MenuItemConstructorOptions[] =
        dynamicTemplate.length > 0
            ? [...dynamicTemplate, { type: "separator" }, ...baseTemplate]
            : baseTemplate;

    customTray.setContextMenu(Menu.buildFromTemplate(fullTemplate));
}


export interface ShortcutBinding {
    id: string;
    accelerator: string;
}

const registeredAccelerators = new Set<string>();

function syncGlobalShortcuts(
    bindings: ShortcutBinding[]
): Record<string, boolean> {
    for (const acc of registeredAccelerators) {
        try {
            globalShortcut.unregister(acc);
        } catch {}
    }
    registeredAccelerators.clear();

    const results: Record<string, boolean> = {};

    for (const item of bindings) {
        if (!item?.id || !item?.accelerator) continue;
        try {
            const ok = globalShortcut.register(item.accelerator, () => {
                const win = myCapacitorApp.getMainWindow();
                if (win && !win.isDestroyed()) {
                    win.webContents.send(
                        "paradise:shortcut-triggered",
                        item.id
                    );
                }
            });
            if (ok) {
                registeredAccelerators.add(item.accelerator);
            }
            results[item.id] = Boolean(ok);
        } catch {
            results[item.id] = false;
        }
    }

    return results;
}


app.on("will-quit", () => {
    try {
        globalShortcut.unregisterAll();
    } catch {}
});

app.on("before-quit", () => {
    isQuitting = true;
});

    setupContentSecurityPolicy(myCapacitorApp.getCustomURLScheme());
    await myCapacitorApp.init();

    const mainWindow = myCapacitorApp.getMainWindow();
    if (mainWindow) {
        mainWindow.on("close", (event) => {
            if (!isQuitting) {
                event.preventDefault();
                mainWindow.hide();
                if (process.platform === "darwin") {
                    app.hide();
                }
            }
        });
    }

    autoUpdater.checkForUpdatesAndNotify();
})();

// When the dock icon is clicked.
app.on("activate", async function () {
    // On OS X it's common to re-create a window in the app when the
    // dock icon is clicked and there are no other windows open.
    if (myCapacitorApp.getMainWindow().isDestroyed()) {
        await myCapacitorApp.init();
    }
});

// Place all ipc or other electron api calls and custom functionality under this line

autoUpdater.on("update-downloaded", (info) => {
    const win = myCapacitorApp.getMainWindow();
    dialog
        .showMessageBox(win, {
            type: "info",
            title: "Update Ready",
            message:
                "A new version of Paradise has been downloaded. Restart the application to apply the updates.",
            buttons: ["Restart Now", "Later"],
        })
        .then((result) => {
            if (result.response === 0) {
                isQuitting = true;
                autoUpdater.quitAndInstall();
            }
        });
});

let customTray: Tray | null = null;
let isQuitting = false;

app.whenReady().then(() => {
    const iconPath = join(__dirname, "../../assets/appIcon.png");
    const trayIcon = nativeImage.createFromPath(iconPath);

    customTray = new Tray(trayIcon);

    const contextMenu = Menu.buildFromTemplate([
        {
            label: "Open Paradise",
            click: () => {
                const win = myCapacitorApp.getMainWindow();
                if (win) {
                    win.show();
                    win.focus();
                }
            },
        },
        { type: "separator" },
        {
            label: "Quit",
            click: () => {
                isQuitting = true;
                app.quit();
            },
        },
    ]);

    customTray.setToolTip("Paradise");
    customTray.setContextMenu(contextMenu);

    customTray.on("double-click", () => {
        const win = myCapacitorApp.getMainWindow();
        if (win) {
            win.show();
            win.focus();
        }
    });
    customTray.on("click", () => {
        const win = myCapacitorApp.getMainWindow();
        if (win) {
            if (win.isVisible()) {
                win.hide();
            } else {
                win.show();
                win.focus();
            }
        }
    });
});
