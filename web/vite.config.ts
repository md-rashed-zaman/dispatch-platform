import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The browser talks to one origin: /api goes through this dev server to the API, so the API needs no CORS.
// Server-sent events stream through the proxy too. In production a reverse proxy does the same.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173, // the emails link here (dispatch.web.base-url), and Keycloak allows it as a redirect
    strictPort: true,
    proxy: { "/api": "http://localhost:8080" },
  },
});
