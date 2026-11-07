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

// Define our menu templates (these are optional)
const trayMenuTemplate: (MenuItemConstructorOptions | MenuItem)[] = [
    new MenuItem({ label: "Quit App", role: "quit" }),
];
const appMenuBarMenuTemplate: (MenuItemConstructorOptions | MenuItem)[] = [
    { role: process.platform === "darwin" ? "appMenu" : "fileMenu" },
    { role: "viewMenu" },
];

// Get Config options from capacitor.config
const capacitorFileConfig: CapacitorElectronConfig =
    getCapacitorElectronConfig();

const myCapacitorApp = new ElectronCapacitorApp(
    capacitorFileConfig,
    null,
    appMenuBarMenuTemplate
);

// If deeplinking is enabled then we will set it up here.
if (capacitorFileConfig.electron?.deepLinkingEnabled) {
    setupElectronDeepLinking(myCapacitorApp, {
        customProtocol:
            capacitorFileConfig.electron.deepLinkingCustomProtocol ??
            "mycapacitorapp",
    });
}

// If we are in Dev mode, use the file watcher components.
if (electronIsDev) {
    setupReloadWatcher(myCapacitorApp);
}

(async () => {
    await app.whenReady();
    session.defaultSession.setDisplayMediaRequestHandler(
        (request, callback) => {
            desktopCapturer
                .getSources({ types: ["screen", "window"] })
                .then((sources) => {
                    if (process.platform === "linux" && sources.length === 1) {
                        callback({ video: sources[0], audio: "loopback" });
                        return;
                    }

                    const template = sources.map((source) => ({
                        label: source.name,
                        click: () => {
                            callback({ video: source, audio: "loopback" });
                        },
                    }));

                    template.push({ type: "separator" } as any);
                    template.push({
                        label: "Cancel",
                        click: () => callback(null),
                    });

                    const menu = Menu.buildFromTemplate(template);
                    menu.popup();
                })
                .catch((err) => {
                    console.error("Failed to fetch capture sources:", err);
                    callback(null);
                });
        },
        { useSystemPicker: true }
    );

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
