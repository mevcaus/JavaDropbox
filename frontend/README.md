# JavaDropbox — Frontend

The React + Vite single-page app for [JavaDropbox](../README.md). It talks to the Spring
Boot backend through Vite's dev proxy.

## Stack

- React 19 + Vite
- Redux Toolkit (state) and React Router (routing)
- Tailwind CSS (styling)
- axios (HTTP)

## Prerequisites

- Node.js 20+ and npm
- The backend running on **http://localhost:8080** (see the [root README](../README.md))

## Development

```bash
npm install
npm run dev
```

The app runs on **http://localhost:5173**. Vite proxies `/api`, `/setup`, `/login`, and
`/logout` to the backend on port 8080 (see [`vite.config.js`](vite.config.js)), so make
sure the backend is running first.

## Available scripts

| Script            | Description                            |
| ----------------- | -------------------------------------- |
| `npm run dev`     | Start the Vite dev server with HMR.    |
| `npm run build`   | Build production assets into `dist/`.  |
| `npm run preview` | Preview the production build locally.  |
| `npm run lint`    | Run ESLint over the project.           |

## Project structure

```
src/
  components/   Reusable UI components (modals, tables, navbar, sidebar)
  features/     Redux slices (auth, files)
  layouts/      Shared page layout(s)
  pages/        Route-level pages (Login, Setup, Dashboard)
  services/     axios instance and API helpers
  redux/        Store configuration
```
