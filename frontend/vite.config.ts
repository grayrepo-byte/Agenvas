import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { configDefaults, defineConfig } from "vitest/config";
import { fileURLToPath, URL } from "node:url";
import { videoEditMediaPreview } from "./e2e/video-edit-media";
import { settingsDesignMediaPreview } from "./e2e/settings-design-media";

export default defineConfig({
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  // Serve-only fixture middleware isolates native synthetic media requests before /api proxying.
  // Its bytes and middleware are absent from the production client build.
  plugins: [videoEditMediaPreview(), settingsDesignMediaPreview(), react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
  test: {
    // Node script tests run separately before Vitest through the package test command.
    exclude: [...configDefaults.exclude, "scripts/check-i18n.test.mjs"],
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    restoreMocks: true,
  },
});
