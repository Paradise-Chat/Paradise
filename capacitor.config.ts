import type { CapacitorConfig } from "@capacitor/cli";
import { KeyboardResize, KeyboardStyle } from "@capacitor/keyboard";

const config: CapacitorConfig = {
    appId: "com.gigiaj.paradise",
    appName: "Paradise",
    webDir: "dist",
    backgroundColor: "#313338",
    server: {
        // url: "http://192.168.3.33:8000",
        //        allowNavigation: ["192.168.3.33"],
        cleartext: true,
        androidScheme: "https",
    },
    plugins: {
        SystemBars: {
            insetsHandling: "disable",
        },
        SplashScreen: {
            launchShowDuration: 3000,
            launchAutoHide: false,
            backgroundColor: "#313338",
            showSpinner: true,
            androidSpinnerStyle: "large",
            iosSpinnerStyle: "small",
            spinnerColor: "#dbdee1",
        },
        Keyboard: {
            resize: KeyboardResize.None,
            resizeOnFullScreen: false,
            style: KeyboardStyle.Dark,
            autoBackdropColor: "auto",
        },
        PushNotifications: {
            presentationOptions: ["badge", "sound", "alert"],
        },
    },
};

export default config;
