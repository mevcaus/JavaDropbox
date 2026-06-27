#!/bin/bash
echo -e "\033[0;36m========================================\033[0m"
echo -e "\033[0;36m      Starting JavaDropbox Stack        \033[0m"
echo -e "\033[0;36m========================================\033[0m"
echo -e ""

if [ ! -d "frontend/node_modules" ]; then
    echo -e "\033[0;32mInstalling frontend dependencies...\033[0m"
    cd frontend && npm install && cd ..
fi

npx concurrently -c "blue,green" -n "backend,frontend" "./gradlew bootRun" "npm run dev --prefix frontend"
