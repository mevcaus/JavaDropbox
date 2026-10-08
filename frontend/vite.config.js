import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        // Before the first account exists the backend redirects /api/me to /setup, with an absolute
        // URL built from the Host header that changeOrigin rewrote to :8080. Point it back at the
        // dev server: a cross-origin redirect would fail CORS, and the app would never learn that
        // setup is pending (see fetchCurrentUser).
        autoRewrite: true,
        // Sends X-Forwarded-Host/-Port/-Proto with the dev server's address, which the backend
        // trusts from loopback, so the share links it builds point here rather than at :8080
        // (where there is no built app to show a link's page).
        xfwd: true,
      },
      // Public share links. A browser opening the link itself gets the app's page for it, served
      // by Vite with hot reload; everything else (the page's /info, /preview and /download, or curl
      // fetching the link) goes to the backend.
      '/share': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        bypass: (req) => {
          const isLinkPage = /^\/share\/[^/?]+(\?|$)/.test(req.url);
          return isLinkPage && req.headers.accept?.includes('text/html') ? '/index.html' : undefined;
        },
      },
      '/setup': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        // Only the POST that creates the admin account goes to the backend.
        // GET /setup is a client-side route, so let Vite serve the SPA.
        bypass: (req) => (req.method !== 'POST' ? '/index.html' : undefined),
      },
      '/login': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        // Only the POST that authenticates goes to the backend. GET /login is a client-side
        // route, so let Vite serve the SPA with hot reload rather than the backend's built copy.
        bypass: (req) => (req.method !== 'POST' ? '/index.html' : undefined),
      },
      '/logout': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      }
    }
  }
})
