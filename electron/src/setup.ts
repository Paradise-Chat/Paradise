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
    Tray,
    session,
} from "electron";
import electronIsDev from "electron-is-dev";
import electronServe from "electron-serve";
import windowStateKeeper from "electron-window-state";
import { readFileSync } from "fs";
import { join } from "path";
import { promisify } from "util";

const execFileAsync = promisify(execFile);
const SINK_NAME = "paradise_share";
const EXEC_OPTS = { maxBuffer: 10 * 1024 * 1024 };

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

let pipewireInterval: NodeJS.Timeout | null = null;
let baselineSourceOutputIds = new Set<number>();
let selectedAudioTarget: AudioAppCandidate | "ALL" | "NONE" = "NONE";
let isScreenShareActive = false;

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

    ipcMain.handle("paradise:audio-isolation-supported", () => {
        return process.platform === "linux";
    });

    ipcMain.handle("paradise:list-audio-apps", async () => {
        if (process.platform !== "linux") return [];
        return await listActiveAudioApps().catch(() => []);
    });

    ipcMain.handle("paradise:get-audio-target", () => {
        return selectedAudioTarget;
    });

    ipcMain.handle(
        "paradise:set-audio-target",
        async (_event, target: AudioAppCandidate | "ALL" | "NONE") => {
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

        this.loadWebApp = electronServe({
            directory: join(app.getAppPath(), "app"),
            scheme: this.customScheme,
        });
    }

    private async loadMainWindow(thisRef: any) {
        await thisRef.loadWebApp(thisRef.MainWindow);
    }

    getMainWindow(): BrowserWindow {
        return this.MainWindow;
    }

    getCustomURLScheme(): string {
        return this.customScheme;
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
                preload: preloadPath,
            },
        });

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
        ipcMain.handle("paradise:window-minimize", () => {
            if (this.MainWindow && !this.MainWindow.isDestroyed()) {
                this.MainWindow.minimize();
            }
        });

        ipcMain.removeHandler("paradise:window-toggle-maximize");
        ipcMain.handle("paradise:window-toggle-maximize", () => {
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
        ipcMain.handle("paradise:window-close", () => {
            if (this.MainWindow && !this.MainWindow.isDestroyed()) {
                this.MainWindow.close();
            }
        });

        ipcMain.removeHandler("paradise:window-is-maximized");
        ipcMain.handle("paradise:window-is-maximized", () => {
            if (!this.MainWindow || this.MainWindow.isDestroyed()) return false;
            return this.MainWindow.isMaximized();
        });

        ipcMain.removeHandler("paradise:window-toggle-devtools");
        ipcMain.handle("paradise:window-toggle-devtools", () => {
            if (this.MainWindow && !this.MainWindow.isDestroyed()) {
                this.MainWindow.webContents.toggleDevTools();
            }
        });


        this.mainWindowState.manage(this.MainWindow);

        if (this.CapacitorFileConfig.backgroundColor) {
            this.MainWindow.setBackgroundColor(
                this.CapacitorFileConfig.electron.backgroundColor
            );
        }

        // If we close the main window with the splashscreen enabled we need to destory the ref.
        this.MainWindow.on("closed", () => {
            if (
                this.SplashScreen?.getSplashWindow() &&
                !this.SplashScreen.getSplashWindow().isDestroyed()
            ) {
                this.SplashScreen.getSplashWindow().close();
            }
        });

        // When the tray icon is enabled, setup the options.
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
            this.TrayIcon.setContextMenu(
                Menu.buildFromTemplate(this.TrayMenuTemplate)
            );
        }

        // Setup the main manu bar at the top of our window.
        Menu.setApplicationMenu(
            Menu.buildFromTemplate(this.AppMenuBarMenuTemplate)
        );

        // If the splashscreen is enabled, show it first while the main window loads then switch it out for the main window, or just load the main window from the start.
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

        // Security
        this.MainWindow.webContents.setWindowOpenHandler((details) => {
            if (!details.url.includes(this.customScheme)) {
                return { action: "deny" };
            } else {
                return { action: "allow" };
            }
        });
        this.MainWindow.webContents.on("will-navigate", (event, _newURL) => {
            if (
                !this.MainWindow.webContents
                    .getURL()
                    .includes(this.customScheme)
            ) {
                event.preventDefault();
            }
        });

        // Link electron plugins into the system.
        setupCapacitorElectronPlugins();

        // When the web app is loaded we hide the splashscreen if needed and show the mainwindow.
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
    session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
        callback({
            responseHeaders: {
                ...details.responseHeaders,
                "Content-Security-Policy": [
                    electronIsDev
                        ? `default-src ${customScheme}://* 'unsafe-inline' devtools://* 'unsafe-eval' 'wasm-unsafe-eval' data:; connect-src ${customScheme}://* https://* wss://*; img-src ${customScheme}://* https://* data: blob:; media-src ${customScheme}://* https://* data: blob:;`
                        : `default-src ${customScheme}://* 'unsafe-inline' data: 'wasm-unsafe-eval'; connect-src ${customScheme}://* https://* wss://*; img-src ${customScheme}://* https://* data: blob:; media-src ${customScheme}://* https://* data: blob:;`,
                ],
            },
        });
    });
}
