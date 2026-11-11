import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
    // npm test -- --coverage writes the report to coverage/ and fails below these thresholds.
    // They sit just under the coverage when they were set (94.6% of lines, 87.7% of branches,
    // 93.8% of statements, 92.1% of functions), about 7 lines, 5 branches, 10 statements and
    // 4 functions of slack. Raise them as coverage rises.
    coverage: {
      provider: 'v8',
      // Every source file, so one no test imports counts as uncovered instead of going unseen.
      include: ['src/**/*.{js,jsx,ts,tsx}'],
      exclude: ['src/**/*.test.*', 'src/setupTests.js', 'src/test/**', 'src/services/apiSchema.ts'],
      reporter: ['text-summary', 'html', 'lcovonly'],
      thresholds: {
        lines: 94,
        branches: 87,
        statements: 93,
        functions: 91,
      },
    },
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
