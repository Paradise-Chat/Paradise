import { defineConfig } from "vite";
import path from "path";
import wasm from "vite-plugin-wasm";
import commonjs from "vite-plugin-commonjs";
import { viteStaticCopy } from "vite-plugin-static-copy";
import { VitePWA } from "vite-plugin-pwa";

const copyFiles = {
    targets: [
        {
            src: path.resolve(
                __dirname,
                "node_modules/livekit-client/dist/livekit-client.e2ee.worker.mjs"
            ),
            dest: ".",
        },
        {
            src: path.resolve(__dirname, "build/*.wasm"),
            dest: ".",
        },
        {
            src: path.resolve(__dirname, "build/config.edn"),
            dest: ".",
        },
        {
            src: path.resolve(__dirname, "build/i18n.edn"),
            dest: ".",
        },
        {
            src: path.resolve(__dirname, "build/css/*"),
            dest: "css",
        },
        {
            src: "themes/*",
            dest: "themes",
        },
    ],
};

export default defineConfig({
    root: "./build",
    base: "./",

    define: {
        "process.env.NODE_ENV": JSON.stringify(
            process.env.NODE_ENV || "production"
        ),
        global: "globalThis",
    },

    resolve: {
        alias: {
            react: path.resolve(__dirname, "node_modules/react"),
            "react-dom": path.resolve(__dirname, "node_modules/react-dom"),
        },
    },

    plugins: [
        wasm(),
        commonjs({
            filter(id) {
                if (id.includes("cljs-runtime")) {
                    return false;
                }
                if (id.includes("uniffi-bindgen-react-native")) {
                    return true;
                }
                return false;
            },
        }),
        viteStaticCopy(copyFiles),
        VitePWA({
            strategies: "injectManifest",
            srcDir: ".",
            filename: "sw.js",
            injectRegister: false,
            manifest: false,
            injectManifest: {
                injectionPoint: "self.__WB_MANIFEST",
                maximumFileSizeToCacheInBytes: 15 * 1024 * 1024,
            },
            devOptions: {
                enabled: true,
                type: "module",
            },
        }),
    ],

    worker: {
        format: "es",
        plugins: () => [wasm()],
    },

    server: {
        port: 8000,
        host: true,
        headers: {
            "Cross-Origin-Opener-Policy": "same-origin",
            "Cross-Origin-Embedder-Policy": "credentialless",
        },
    },

    build: {
        target: "esnext",
        outDir: "../dist",
        emptyOutDir: true,
        rollupOptions: {
            input: {
                main: path.resolve(__dirname, "build/index.html"),
                engine: path.resolve(__dirname, "build/engine.js"),
                virtualizer: path.resolve(__dirname, "build/virtualizer.js"),
            },
            output: {
                entryFileNames: (chunkInfo) => {
                    if (
                        chunkInfo.name === "engine" ||
                        chunkInfo.name === "virtualizer"
                    ) {
                        return "[name].js";
                    }
                    return "assets/[name]-[hash].js";
                },
                chunkFileNames: "assets/[name]-[hash].js",
                assetFileNames: "assets/[name]-[hash].[ext]",
            },
        },
    },
});
