# JavaDropbox

> A lightweight, self-hosted file management solution built with Spring Boot and React. Access, manage, and download your files from anywhere.

JavaDropbox is designed for:
- **Developers and IT professionals** who want a private, self-hosted solution for managing project files or internal documentation.
- **Small businesses** that wish to securely store, share, and access digital assets within their own infrastructure.
- **Students and educators** needing a simple system to upload and share documents in an academic environment.
- **Individuals** who prefer self-hosted alternatives to commercial cloud storage services, focusing on privacy and ownership of data.

In short, the main audience consists of users who value **control, security, and simplicity** over large enterprise-scale features.

## Features

-   **File & Folder Downloads:**
    -   Download individual files directly.
    -   Download entire folders, which are automatically zipped on-the-fly.
-   **Uploads:** Easily add files by dragging them into the browser or using a traditional file selector.
-   **Folder Management:** Create new folders and navigate nested directories from the UI.
-   **File Versioning & History:** Track changes to files and view their history (backed by PostgreSQL).
-   **Configurable Directory:** Serve files from the default `JDB` subfolder or specify any directory on your system via a command-line argument.
-   **Spring Security:** Single-admin authentication to restrict access to the file management interface.

## Tech Stack

-   **Backend:**
    -   Java 21
    -   Spring Boot 3.5 (Spring Web, Spring Security, Spring Data JPA, Spring Modulith)
    -   PostgreSQL 15
-   **Frontend:**
    -   React 19 + Vite
    -   Redux Toolkit, React Router
    -   Tailwind CSS, axios
-   **Build / Tooling:**
    -   Gradle (wrapper included)
    -   Docker (for the PostgreSQL database)

## Architecture

The backend is a Spring Boot application that exposes a JSON API (under `/api`) plus
`/setup`, `/login`, and `/logout` endpoints. The frontend is a separate React single-page
app served by Vite during development, which proxies API/auth requests to the backend.
File metadata, versions, and the single admin user are stored in PostgreSQL; the actual
files live on disk in the configured serving directory (default: `./JDB`).

## Getting Started

Follow these instructions to get a local copy up and running.

### Prerequisites

-   **JDK 21** ([Temurin](https://adoptium.net/) or equivalent)
-   **Docker** (Docker Desktop or compatible) — used to run PostgreSQL. The backend
    auto-starts the database via Spring Boot's Docker Compose support, so Docker must be
    running before you start the backend.
-   **Node.js 20+** and npm — for the frontend.

### 1. Clone the repository

```bash
git clone https://github.com/mevcaus/JavaDropbox.git
cd JavaDropbox
```

### 2. Run the backend (and database)

The backend reads [`compose.yaml`](compose.yaml) and automatically starts a PostgreSQL
container on first launch (via the `spring-boot-docker-compose` integration), so you do
**not** need to start the database manually.

```bash
./gradlew bootRun
```

This starts the API on **http://localhost:8080** and creates/serves files from a `JDB`
folder in the project directory.

**Custom serving directory** — pass an absolute path to serve any directory:

```bash
./gradlew bootRun --args='--directory=/path/to/your/files'
```

> If you prefer to run PostgreSQL yourself instead of via Docker, start it with the
> credentials in [`compose.yaml`](compose.yaml) (db `javadropbox`, user `postgres`,
> password `password`, port `5432`) — these match
> [`src/main/resources/application.properties`](src/main/resources/application.properties).

### 3. Run the frontend

In a second terminal:

```bash
cd frontend
npm install
npm run dev
```

This starts the React app on **http://localhost:5173** and proxies API/auth calls to the
backend on port 8080.

## How to Use

### First-time setup

1.  With both servers running, open **http://localhost:5173**.
2.  On the login screen, click **"Need to setup a first user?"** to open the setup page.
3.  Create an admin username and password. You'll be redirected to the login screen.
4.  Log in with the credentials you just created.

> **Resetting credentials:** the admin account is stored in the `users` table in
> PostgreSQL. To start over, either clear that table
> (`TRUNCATE users RESTART IDENTITY CASCADE;`) or wipe the database volume entirely with
> `docker compose down -v`. After that, the setup flow will be available again.

### Core Functionality

-   **Downloading:** Click directly on the name of any file or folder to start the download. Folders are downloaded as a `.zip` file.
-   **Uploading:** Click the "Add Files" button to drag and drop files or open your system's file explorer.
-   **Creating folders:** Use the new-folder action to create empty directories.
-   **Deleting:** Click the trash can icon (🗑️) on any row. A confirmation pop-up prevents accidental deletion.

## Building for Production

```bash
# Backend: produces an executable jar in build/libs/
./gradlew build

# Frontend: produces static assets in frontend/dist/
cd frontend && npm run build
```

## Running the Tests

```bash
./gradlew test
```

Tests run against an in-memory H2 database, so they do **not** require Docker or
PostgreSQL.

## Future Enhancements

-   [ ] **File Previews:** Implement previews for common file types (images, PDFs, text files).
-   [ ] **Search Functionality:** Add a search bar to quickly locate files and folders.
-   [ ] **Sorting Options:** Allow users to sort files and folders by name, date, size, etc.
-   [ ] **Upload to Subfolders:** Allow users to upload files directly into the currently expanded folder.
-   [ ] **Desktop Folder Integration:** Sync a local folder with the server.

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.
