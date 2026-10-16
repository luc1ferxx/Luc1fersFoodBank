/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { API_PROXY_PATTERN } from "./src/api/proxyPaths.ts";

// Backend that `npm start` and `npm run preview` forward API calls to.
const backendTarget = process.env.DEV_PROXY_TARGET || "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  build: {
    // Gradle (buildFrontend/syncFrontendBuild) and `npm run deploy:backend` read frontend/build.
    outDir: "build",
    emptyOutDir: true,
    // The backend only permits anonymous GETs for /static/** among hashed assets.
    assetsDir: "static",
  },
  server: {
    port: 3000,
    strictPort: true,
    proxy: {
      [API_PROXY_PATTERN]: {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/setupTests.ts"],
    css: false,
    restoreMocks: true,
    unstubGlobals: true,
  },
});
