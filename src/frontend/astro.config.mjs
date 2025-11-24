import { defineConfig } from "astro/config";
import react from "@astrojs/react";
import node from "@astrojs/node";


const allowedDomain = process.env.SECMAN_DOMAIN || "http://localhost:4321";
const allowedHost = process.env.SECMAN_HOST || "localhost";


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
      },
    },
  },
});
