import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
    appId: "com.gigiaj.paradise",
    appName: "Paradise",
    webDir: "dist",
    server: {
        // url: "http://192.168.3.33:8000",
        //        allowNavigation: ["192.168.3.33"],
        cleartext: true,
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
            resizeOnFullScreen: false,
        },
        PushNotifications: {
            presentationOptions: ["badge", "sound", "alert"],
        },
    },
};

export default config;
