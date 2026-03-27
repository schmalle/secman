import { defineConfig } from "astro/config";
import react from "@astrojs/react";
import node from "@astrojs/node";


const allowedDomain = process.env.SECMAN_DOMAIN || "http://localhost:4321";
const allowedHost = process.env.SECMAN_HOST || "localhost";

// Suppress noisy Vite warnings that are not actionable:
// - "externalized for browser compatibility" from @astrojs/node server-side dependencies
// - "emitFile() is not supported in serve mode" from astro:scripts plugin (known Astro issue)
const suppressDevWarnings = {
  name: "suppress-dev-warnings",
  configResolved(config) {
    const originalWarn = config.logger.warn;
    config.logger.warn = (msg, options) => {
      if (typeof msg === "string" && (
        msg.includes("externalized for browser compatibility") ||
        msg.includes("emitFile() is not supported in serve mode")
      )) return;
      originalWarn(msg, options);
    };
  },
};


// https://astro.build/config
export default defineConfig({
  integrations: [react()],
  output: "server",
  adapter: node({
    mode: "standalone",
  }),
  server: {
    host: true,
    port: 4321,
  },
  vite: {
    plugins: [suppressDevWarnings],
    server: {
        allowedHosts: [
            allowedHost
        ],
      proxy: {
        "/api": {
          target: allowedDomain,
          changeOrigin: true,
          secure: false,
        },
        "/oauth": {
          target: allowedDomain,
          changeOrigin: true,
          secure: false,
        },
        "/mcp": {
          target: allowedDomain,
          changeOrigin: true,
          secure: false,
        },
      },
    },
  },
});
