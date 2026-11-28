import { defineConfig, normalizePath } from "vite";
import path from "path";
import wasm from "vite-plugin-wasm";
import commonjs from "vite-plugin-commonjs";
import { viteStaticCopy } from "vite-plugin-static-copy";
import { VitePWA } from "vite-plugin-pwa";

const copyFiles = {
    silent: true,
    targets: [
        {
            src: normalizePath(path.resolve(__dirname, "config.edn")),
            dest: ".",
        },
        {
            src: normalizePath(
                path.resolve(
                    __dirname,
                    "node_modules/simple-rnnoise-wasm/dist/*"
                )
            ),
            dest: "rnnoise",
        },
        {
            src: normalizePath(path.resolve(__dirname, "i18n.edn")),
            dest: ".",
        },
        {
            src: normalizePath(path.resolve(__dirname, "css/*")),
            dest: "css",
        },
    ],
};

function scopedCommonjs() {
    const plugin = commonjs({
        filter(id) {
            return id.includes("uniffi-bindgen-react-native");
        },
    });
    return {
        ...plugin,
        configResolved(resolvedConfig) {
            plugin.configResolved?.call(this, resolvedConfig);
            const esbuildPlugins =
                resolvedConfig.optimizeDeps?.esbuildOptions?.plugins;
            const preBundle = esbuildPlugins?.find(
                (p) => p.name === "vite-plugin-commonjs:pre-bundle"
            );
            if (preBundle) {
                const origSetup = preBundle.setup;
                preBundle.setup = (build) =>
                    origSetup({
                        ...build,
                        onLoad(opts, cb) {
                            return build.onLoad(
                                { ...opts, filter: /uniffi-bindgen-react-native/ },
                                cb
                            );
                        },
                    });
            }
        },
        transform(code, id) {
            if (!id.includes("uniffi-bindgen-react-native")) {
                return null;
            }
            return plugin.transform.call(this, code, id);
        },
    };
}

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
        scopedCommonjs(),
        viteStaticCopy(copyFiles),
        VitePWA({
            strategies: "injectManifest",
            srcDir: ".",
            filename: "sw.js",
            injectRegister: false,
            manifest: false,
            injectManifest: {
                injectionPoint: "self.__WB_MANIFEST",
                maximumFileSizeToCacheInBytes: 32 * 1024 * 1024,
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
                media: path.resolve(__dirname, "build/media.js"),
            },
            output: {
                entryFileNames: (chunkInfo) => {
                    if (
                        chunkInfo.name === "engine" ||
                        chunkInfo.name === "virtualizer" ||
                        chunkInfo.name === "media"
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
