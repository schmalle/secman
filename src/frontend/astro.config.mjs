import { defineConfig } from "astro/config";
import react from "@astrojs/react";
import node from "@astrojs/node";


const allowedDomain = process.env.SECMAN_DOMAIN || "http://localhost:4321";
const allowedHost = process.env.SECMAN_HOST || "localhost";

// Suppress "externalized for browser compatibility" warnings from @astrojs/node
// server-side dependencies (send, etag, on-finished) that import Node.js built-ins.
const suppressNodeWarnings = {
  name: "suppress-node-externalize-warnings",
  configResolved(config) {
    const originalWarn = config.logger.warn;
    config.logger.warn = (msg, options) => {
      if (typeof msg === "string" && msg.includes("externalized for browser compatibility")) return;
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
    plugins: [suppressNodeWarnings],
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
