import { defineConfig } from "astro/config";
import react from "@astrojs/react";
import node from "@astrojs/node";

import { resolveBuildInfo } from "./buildinfo.mjs";


const allowedDomain = process.env.SECMAN_DOMAIN || "http://localhost:4321";
const allowedHost = process.env.SECMAN_HOST || "localhost";

// Resolved once, here, while the config is evaluated — i.e. at build time. The
// footer must never recompute this per request (output is "server", so
// frontmatter runs on every render) and must never depend on a .git directory
// existing at runtime, because the shipped container has none.
const buildInfo = resolveBuildInfo();

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
    // Double-encoded on purpose: `define` substitutes the replacement as raw
    // source text, so the injected value is a *string literal* the consumer
    // JSON.parses (Footer.astro). Injecting a bare object literal would be
    // parsed as a block in statement position.
    define: {
      __SECMAN_BUILD_INFO__: JSON.stringify(JSON.stringify(buildInfo)),
    },
    build: {
      // exceljs minified is ~916 KB intrinsically; raise the threshold so the
      // warning fires only on chunks that are actually fixable.
      chunkSizeWarningLimit: 1000,
    },
    server: {
        allowedHosts: [
            allowedHost,
            "secman.schmall.io"
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
