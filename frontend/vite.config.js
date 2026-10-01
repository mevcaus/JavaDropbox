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
      },
      // Public share links. The backend builds them from the request's host, which changeOrigin
      // rewrites to the backend's, so they point at :8080 directly; this entry makes a link that
      // was opened through the dev server work too.
      '/share': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
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
