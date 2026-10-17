import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/** Cursor/tests default to 8080; `./dev.sh` sets GRIDIRON_API_PROXY to :18080. */
const apiProxyTarget = process.env.GRIDIRON_API_PROXY ?? "http://localhost:8080";

const proxyConfig = {
    "/api": {
        target: apiProxyTarget,
        changeOrigin: true,
        configure: (proxy: { on: (event: "proxyRes", handler: (proxyRes: { headers: Record<string, string | string[] | undefined> }) => void) => void }) => {
            proxy.on("proxyRes", (proxyRes) => {
                const contentType = proxyRes.headers["content-type"];
                if (typeof contentType === "string" && contentType.includes("text/event-stream")) {
                    proxyRes.headers["cache-control"] = "no-cache";
                    proxyRes.headers["x-accel-buffering"] = "no";
                }
            });
        },
    },
    "/v3/api-docs": {
        target: apiProxyTarget,
        changeOrigin: true,
    },
};

export default defineConfig({
    plugins: [react()],
    server: {
        proxy: proxyConfig,
    },
    preview: {
        proxy: proxyConfig,
    },
});
