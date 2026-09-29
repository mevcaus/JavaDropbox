#!/bin/bash
# Starts Postgres (through bootRun's Docker Compose support), the backend on :8080 and the Vite
# dev server on :5173. The command itself lives in package.json ("npm run dev").
set -euo pipefail
cd "$(dirname "$0")"

echo -e "\033[0;36mStarting JavaDropbox (backend :8080, frontend :5173)\033[0m"

if [ ! -d node_modules ] || [ ! -d frontend/node_modules ]; then
    echo -e "\033[0;32mInstalling dependencies...\033[0m"
    npm run install-all
fi

exec npm run dev
