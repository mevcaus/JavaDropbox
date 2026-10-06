<p align="center">
  <img src="frontend/src/assets/logo/javadropbox-horizontal-color.png" width="420" alt="JavaDropbox"/>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white" alt="Spring Boot 3.5"/>
  <img src="https://img.shields.io/badge/React-19-61DAFB?style=for-the-badge&logo=react&logoColor=black" alt="React 19"/>
  <img src="https://img.shields.io/badge/PostgreSQL-15-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL"/>
  <img src="https://img.shields.io/badge/Docker-Ready-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker"/>
  <img src="https://img.shields.io/badge/CI-GitHub_Actions-2088FF?style=for-the-badge&logo=github-actions&logoColor=white" alt="GitHub Actions"/>
  <a href="https://github.com/mevcaus/JavaDropbox/actions/workflows/frontend-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/mevcaus/JavaDropbox/frontend-ci.yml?branch=main&style=for-the-badge&logo=github-actions&logoColor=white&label=Frontend%20CI" alt="Frontend CI"/></a>
</p>

# ☁️ JavaDropbox

A **full-stack, self-hosted cloud storage platform** built from scratch — inspired by Dropbox, Google Drive, and OneDrive. Users can upload, download, version, and manage files through a modern React dashboard, backed by a secure Spring Boot REST API with PostgreSQL persistence.

**[Try the live demo →](https://javadropbox.mevcaus.dev)** Sign in as `demo` with the password `javadropbox`. Everyone shares that account, and it is reset every day ([details](#live-demo)).

> **Why I built this:** To deeply understand the systems that power cloud storage — from file I/O and streaming ZIP compression to session-based auth, file versioning, and recursive directory traversal — by implementing them myself rather than relying on abstractions.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Key Features](#key-features)
- [System Design Decisions](#system-design-decisions)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Live Demo](#live-demo)
- [Database Migrations](#database-migrations)
- [API Reference](#api-reference)
- [Testing](#testing)
- [CI/CD Pipeline](#cicd-pipeline)
- [Observability](#observability)
- [Future Roadmap](#future-roadmap)
- [License](#license)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         Client (Browser)                        │
│                     React 19 + Redux Toolkit                    │
│               Vite Dev Server (port 5173) + Proxy               │
└────────────────────────────────┬────────────────────────────────┘
                                 │  HTTP (REST API)
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│               Spring Boot Application  (port 8080)              │
│                                                                 │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │
│  │  SetupFilter +  │  │   Controllers   │  │  ApiException   │  │
│  │  Security Chain │  │  (REST + SPA)   │  │     Handler     │  │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                       Service Layer                       │  │
│  │  StoragePaths · FileService · FileTreeService             │  │
│  │  FileVersionService · SetupService · ShareLinkService     │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                Spring Data JPA Repositories               │  │
│  │             User · FileMetadata · FileVersion             │  │
│  │                  FileHistory · ShareLink                  │  │
│  └───────────────────────────────────────────────────────────┘  │
└────────────────────────────────┬────────────────────────────────┘
                                 │  JDBC
                                 ▼
  ┌────────────────────────────┐     ┌──────────────────────────┐
  │       PostgreSQL 15        │     │       File System        │
  │     (Docker Container)     │     │   (Serving Directory)    │
  │                            │     │                          │
  │   users · file_metadata    │     │        user files        │
  │       file_versions        │     │   .versions/ snapshots   │
  │        file_history        │     │                          │
  └────────────────────────────┘     └──────────────────────────┘
```

The system follows a **layered architecture** with clear separation of concerns:

| Layer | Responsibility | Key Classes |
|-------|---------------|-------------|
| **Controller** | Request routing, HTTP response formatting, serving the built SPA | `FileController`, `FileVersionController`, `HistoryController`, `ShareController`, `SetupController`, `AuthController`, `SpaController`, `ApiExceptionHandler` |
| **Service** | Path validation, file I/O, versioning, audit log, setup, share links | `StoragePaths`, `FileService`, `FileTreeService`, `FileVersionService`, `FileHistoryService`, `SetupService`, `AuthService`, `ShareLinkService` |
| **Repository** | Data access via Spring Data JPA | `FileMetadataRepository`, `FileVersionRepository`, `FileHistoryRepository`, `UserRepository` |
| **Model** | JPA entities mapping to PostgreSQL tables | `User`, `FileMetadata`, `FileVersion`, `FileHistory` |
| **DTO** | API response shaping, decoupling internal models from API contracts | `FileTreeNode`, `FileVersionDto`, `FileHistoryDto`, `HistoryPage`, `Download` |
| **Config** | Cross-cutting concerns: security, CORS, filters, sign-in throttling | `SecurityConfig`, `SetupFilter`, `LoginThrottleFilter`, `SpaFallbackFilter`, `PasswordConfig`, `StartupBanner` |

---

## Key Features

### File Management
- **Upload** — Multi-file upload with `multipart/form-data`; each file is written to a scratch file and renamed into place, so a dropped upload never leaves a truncated file behind
- **Download** — File downloads with MIME type detection and HTTP range support (resumable); folder downloads are **ZIP archives streamed straight to the response**, so their size never has to fit in memory
- **Delete** — Recursive deletion that also removes the metadata rows and stored versions of everything inside
- **Create Folders** — Folder creation with single-segment name validation
- **Previews** — Images (PNG, JPEG, GIF, WebP, AVIF, BMP, SVG), PDFs, and text and source files open in a dialog instead of downloading. The server decides from the extension which files can be previewed and says so in the tree (`previewType`); text previews fetch only the first 256 KB through a range request

### File Versioning System
- **Automatic snapshotting** — On re-upload, the previous content is moved to `.versions/<file id>/v<n>` and tracked in the database (so same-named files in different folders never share storage)
- **Version history** — A Versions action on each tracked file lists its versions (size, date, author); also available as `GET /api/files/{fileId}/versions`
- **Restore support** — Restore any previous version with two strategies:
  - `OVERWRITE` — Replace the current file (current version is snapshotted first)
  - `COPY` — Restore as a new file alongside the original (`name_v2.txt`, or `name_v2 (2).txt` if that is taken)
- **Configurable retention** — Automatic pruning of old versions beyond a configurable limit, set via `javadropbox.versions.max-retained` in `application.properties` (default: 10)
- **Clean-up of leftovers** — On startup, upload scratch files and unreferenced stored versions of files the database knows (what a crash leaves behind) are removed once they are over an hour old. Version folders of files the database doesn't know are left alone and logged, and nothing is swept if the database has no files at all, so starting against the wrong database can't delete version history

### Security
- **Spring Security** integration with form-based login, session management and cookie-based CSRF protection
- **BCrypt password hashing** via `PasswordEncoder`
- **First-run setup flow** — `SetupFilter` redirects every request to `/setup` until the first account exists, and creating it requires a **one-time setup code printed in the server log**, so only whoever runs the server can claim it; five wrong codes from one address lock that address out for 15 minutes, without changing the code
- **Path safety** — Every client-supplied path goes through `StoragePaths`, which rejects anything outside the serving directory (after `..` is normalized), any path through a symlink (the tree never shows one), the app's own `.versions/` and `.javadropbox/` directories under any letter case, new names starting with a dot (the tree hides them), and the root itself for delete and share. Paths are keyed by their on-disk spelling, so case variants on macOS or Windows share one metadata row, and are checked again for swapped-in symlinks right before each disk operation
- **Sign-in throttling** — Five failed sign-ins from one address within 15 minutes lock it out for 15 minutes (`429` with `Retry-After`)
- **CORS** — Off unless `app.cors.allowed-origins` lists origins (the dev profile allows the Vite dev server)
- **Session-based auth** with `JSESSIONID` cookie and automatic 401 interception on the frontend via Axios interceptors

### Share Links
- **Time-limited public links** — `POST /api/share` creates a link stored in the `share_links` table. The URL carries only a random 32-byte token, which reveals nothing about the path; the server keeps just its SHA-256 hash, so the table cannot be used to rebuild a link
- **Bound to the item** — A link belongs to the metadata row of the file or folder it was made for and is deleted with it, so a different item created later at the same path is never served by an old link. Sharing a file copied in by hand starts tracking it
- **Revocable** — `GET /api/share?path=` lists a path's active links and `DELETE /api/share/{id}` revokes one; it then returns 404 like any unknown link
- **Public download** — `GET /share/{token}` looks up the hash, refuses expired or revoked links and items that have moved or changed between file and folder, then streams the item through the same path-traversal-safe serving logic
- **Bounded lifetime** — Expiration is capped at 7 days; links for nonexistent paths, and for the root folder, are rejected at creation. Expired links are pruned whenever a new one is made
- **Frontend** — Share icon in the file table opens a modal to pick an expiry, copy the generated link (shown once), and see and revoke the item's active links

### API Documentation
- **OpenAPI 3 spec** generated at runtime by springdoc from the actual Spring mappings, so it cannot drift from the code
- **Swagger UI** at `/swagger-ui.html` — browse and execute every endpoint; published only by the `dev` profile (off in production), and reachable there without authentication, including before first-run setup

### Audit Trail
- **Complete file history** — Every upload, delete, folder creation and restore is logged to the `file_history` table with timestamps, the signed-in user, and success/failure status; `GET /api/history` pages through it
- **Error tracking** — Failed operations are recorded with error messages, in a transaction of their own so the entry survives the failed operation's rollback

### Frontend
- **React 19** SPA with **Redux Toolkit** for global state management
- **Responsive layout** with collapsible sidebar, breadcrumb navigation, and mobile hamburger menu; the open folder is kept in the URL (`/dashboard?path=...`), so a reload and Back/Forward keep it
- **File previews** — Clicking a previewable file's name opens it in a dialog: images in an `<img>`, PDFs in the browser's own viewer, text in a `<pre>` (shown as source, never rendered), with a Download button alongside
- **Smart file icons** — Context-aware icons based on file extension (images, video, audio, code, documents)
- **File search** — Search box above the file table matches filenames (case-insensitive substring) across the open folder **and every folder beneath it**, flattening results into a list labelled with each match's full path; runs entirely client-side against the already-loaded tree, so no extra request is made
- **Column sorting** — Name, Size, and Last Modified headers sort in either direction, keyboard-operable and annotated with `aria-sort`; folders stay grouped ahead of files in every ordering
- **Version history** — Browse a file's previous versions and restore one in place or as a copy
- **Accessible dialogs** — One `Modal` shell: Escape closes, focus is trapped inside and restored on close
- **Protected routes** — `MainLayout` guards routes via Redux auth state with redirect-to-login
- **Unknown URLs** — Opening or refreshing a page the app doesn't have (`/dashbord`, an old bookmark) loads the app, which redirects to the dashboard or sign-in, instead of a bare 401. The backend serves the app shell for any page a browser opens outside `/api`, `/share`, the API docs and static files (`SpaFallbackFilter`)

---

## System Design Decisions

### Why a Dual Storage Strategy (Filesystem + Database)?
Files are stored on the **filesystem** for performance and simplicity (no BLOB overhead), while **metadata, version history, and audit logs** live in PostgreSQL. This mirrors how production cloud storage systems like Dropbox work — the database serves as the source of truth for relationships and history, while the filesystem handles raw byte storage.

### Why On-the-Fly ZIP Streaming?
Folder downloads write a `ZipOutputStream` straight to the HTTP response (`FolderArchive`) rather than building the archive in memory or in a temporary file. Memory use stays flat however large the folder is, and there is nothing to clean up afterwards. The cost is that the size is not known up front, so the response has no `Content-Length`.

### Why a Custom Setup Filter and a Setup Code?
Rather than shipping hardcoded credentials or requiring environment variables, the application detects first-run state (no users in the database) and redirects to a setup wizard. This is implemented as a filter (`SetupFilter`) inside Spring Security's chain, ahead of `UsernamePasswordAuthenticationFilter`, so the setup flow is reachable without authentication.

Reachable without authentication also means reachable by whoever finds the server first. So setup additionally needs a one-time code that the server prints to its log on startup (the approach Jupyter takes): the person who installed the server can read it, someone who merely found the address cannot.

### Why Do Previews Have Their Own Route?
Downloads are deliberately unable to render: every one is `Content-Disposition: attachment` with `Content-Security-Policy: sandbox`, and no response may be framed (`X-Frame-Options: DENY`). They also serve the public share route. So rather than a flag that relaxes all of that, `GET /api/files/preview` serves only an allowlist of types, signed in only, each with the narrowest headers that still let the browser show it:
- **Text and source files** are always `text/plain; charset=UTF-8`, so an `.html` file shows its markup instead of running it. With `nosniff`, a file named `.png` that is really HTML is never sniffed into a page either.
- **Images and text** keep the sandbox: an SVG opened on its own runs no script on the app's origin.
- **PDFs** cannot keep it, because browsers' PDF viewers refuse to render a sandboxed document. Instead they may be framed by the app's own pages and no other (`frame-ancestors 'self'`, `X-Frame-Options: SAMEORIGIN`), which is how the preview dialog shows them. Every other response still refuses to be framed.

The list of previewable types lives on the server (`PreviewType`) and reaches the UI through the tree, so the two cannot disagree about which files open.

### Why Server-Side Share Links?
Share links used to be stateless JWTs naming a path. That avoided a table, but a link could not be withdrawn without changing the signing key (killing every link), its path was readable by anyone holding it, it served whatever was at the path when opened, and the key itself had to live somewhere: in the served folder, where anything else exposing that folder exposed it. Storing links as rows with a random token fixes all of that at the cost of one indexed lookup per download. Only the token's hash is stored, so a database leak does not hand out working links. A folder link still serves the folder as it is at download time, including files added after the link was made.

### Why Redux Toolkit Over React Context?
With async thunks for file operations (upload, delete, fetch, create directory), Redux Toolkit provides structured side-effect management via `createAsyncThunk`, built-in loading/error states, and DevTools integration — capabilities that would require significant boilerplate with plain Context + useReducer.

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Backend** | Java 21, Spring Boot 3.5 | REST API, dependency injection, auto-configuration |
| **Security** | Spring Security 6 | Authentication, authorization, CSRF, session management |
| **ORM** | Spring Data JPA + Hibernate | Object-relational mapping, repository pattern |
| **Migrations** | Flyway | Versioned SQL schema migrations; Hibernate only validates |
| **Database** | PostgreSQL 15 | Persistent storage for users, metadata, versions, history |
| **API Docs** | springdoc-openapi 2.8 | OpenAPI 3 spec + Swagger UI generated from controllers |
| **Testing** | JUnit 5, MockMvc, H2, Testcontainers | Backend integration tests on H2, plus real PostgreSQL for schema and setup tests |
| **Frontend Testing** | Vitest, Testing Library, jsdom | Component tests driving the real DOM with real user events |
| **Code Style** | Spotless + google-java-format | Formatting enforced on every Java file by the build |
| **Frontend** | React 19, Vite 7 | Component-based SPA with HMR |
| **State Mgmt** | Redux Toolkit | Centralized state with async thunk side effects |
| **HTTP Client** | Axios | API communication with interceptors for auth |
| **Styling** | Tailwind CSS 3 | Utility-first CSS framework |
| **Icons** | Lucide React | Consistent icon library |
| **Build** | Gradle (Wrapper) | Backend build tool with Spring Boot plugin |
| **CI/CD** | GitHub Actions | Build and test, Docker image build, frontend lint/test/build, dependency submission |
| **Containerization** | Docker, Docker Compose | Multi-stage build (frontend bundle inside the jar), app + PostgreSQL services |

---

## Project Structure

```
JavaDropbox/
├── .github/workflows/
│   ├── gradle.yml                  # Backend build + tests, Docker image build, dependency graph
│   └── frontend-ci.yml             # Frontend lint, tests, production build
├── frontend/                       # React SPA (Vite)
│   ├── src/
│   │   ├── components/
│   │   │   ├── Modal.jsx           #   Shared dialog shell: Escape, focus trap, focus restore
│   │   │   ├── CreateFolderModal.jsx
│   │   │   ├── DeleteConfirmationModal.jsx
│   │   │   ├── ShareModal.jsx      #   Share-link creation, clipboard fallback for plain http
│   │   │   ├── VersionHistoryModal.jsx  # List and restore previous versions
│   │   │   ├── PreviewModal.jsx    #   Image, PDF and text previews
│   │   │   ├── FileTable.jsx       #   File listing with recursive search, sorting, row actions
│   │   │   ├── Breadcrumbs.jsx     #   Path navigation breadcrumbs
│   │   │   ├── Navbar.jsx          #   Top bar with user info and logout
│   │   │   ├── Sidebar.jsx         #   Navigation and storage used
│   │   │   └── Logo.jsx, AnimatedLogo.jsx
│   │   ├── features/               # Redux slices
│   │   │   ├── authSlice.js        #   Login/logout/session thunks + state
│   │   │   └── filesSlice.js       #   File thunks, tree selectors, endpoint paths
│   │   ├── layouts/MainLayout.jsx  # Auth-guarded layout wrapper
│   │   ├── pages/                  # Dashboard, Login, Setup
│   │   ├── services/api.js         # Axios instance: CSRF priming, 401 handler hook
│   │   ├── utils/                  # date, errors (readableError), format (formatSize)
│   │   ├── App.jsx                 # Route definitions
│   │   └── main.jsx                # Entry point; registers the 401 handler
│   ├── vite.config.js              # Dev proxy to Spring Boot backend
│   └── package.json
├── src/main/java/
│   ├── com/javadropbox/javadropbox/
│   │   ├── JavadropboxApplication.java  # Entry point, --directory shorthand
│   │   ├── config/
│   │   │   ├── SecurityConfig.java      # Filter chain, CORS, form login, SPA routes
│   │   │   ├── SetupFilter.java         # First-run redirect filter
│   │   │   ├── LoginAttemptLimiter.java # Failed sign-in counting per address
│   │   │   ├── LoginThrottleFilter.java # 429 for locked-out addresses
│   │   │   ├── SpaFallbackFilter.java   # App shell for pages the server has no route for
│   │   │   ├── StartupBanner.java       # Logs the bound port and serving directory
│   │   │   ├── RetiredShareKey.java     # Refuses the old share-link secret, deletes the old key file
│   │   │   └── PasswordConfig.java      # BCrypt encoder bean
│   │   ├── controller/
│   │   │   ├── FileController.java      # Tree, upload, download, preview, delete, folders, storage info
│   │   │   ├── FileVersionController.java  # Version listing + restore
│   │   │   ├── HistoryController.java   # Paged audit log
│   │   │   ├── ShareController.java     # Share-link creation, listing, revoking + public download
│   │   │   ├── SetupController.java     # First-run account creation
│   │   │   ├── AuthController.java      # Current user
│   │   │   ├── SpaController.java       # Serves the built app for client-side routes
│   │   │   ├── DownloadResponses.java   # File/zip/preview responses, Content-Disposition, CSP
│   │   │   └── ApiExceptionHandler.java # Exceptions -> {"message"} with the right status
│   │   ├── dto/                    # FileTreeNode, FileVersionDto, FileHistoryDto, HistoryPage, Download, Preview, ShareLinkDto
│   │   ├── exception/              # BadRequest (400), Forbidden (403), NotFound (404), Conflict (409)
│   │   ├── model/                  # JPA entities: User, FileMetadata, FileVersion, FileHistory, ShareLink; PreviewType
│   │   ├── repository/             # Spring Data repositories
│   │   └── service/
│   │       ├── StoragePaths.java        # The one place client paths become filesystem paths
│   │       ├── FileService.java         # Upload, delete, create folder, restore, download, preview
│   │       ├── FileTreeService.java     # The browsable tree
│   │       ├── FileVersionService.java  # Archiving, pruning and looking up versions
│   │       ├── FileHistoryService.java  # Audit log, including failures
│   │       ├── FolderArchive.java       # Streams a folder as a zip
│   │       ├── SetupService.java        # Setup code + first account
│   │       ├── ShareLinkService.java    # Share links: random tokens, stored hashed, bound to their item
│   │       └── AuthService.java         # Current user, setup state
│   └── db/migration/
│       └── V3__timestamps_with_time_zone.java  # Java migration (needs the JVM's zone)
├── src/main/resources/
│   ├── db/migration/               # Flyway SQL migrations (V1__baseline.sql, V2__integrity_constraints.sql)
│   ├── application.properties      # Production defaults
│   └── application-dev.properties  # Local development (bootRun)
├── src/test/                       # See Testing below
├── build.gradle                    # Dependencies, Spring Boot plugin, Spotless, -PbundleFrontend
├── compose.yaml                    # PostgreSQL, plus the app itself behind the "app" profile
├── Dockerfile                      # Multi-stage: frontend bundle -> Gradle build -> JRE runtime
├── package.json                    # Root: `concurrently` wrapper used by start.sh
├── start.sh                        # One-command start: DB + backend + frontend
├── LICENSE
└── README.md
```

---

## Getting Started

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **JDK** | 17–24 to run Gradle; the build uses JDK 21 and downloads it if missing | [Download](https://adoptium.net/temurin/releases/?version=21) |
| **Node.js** | 20.19+ or 22.12+ | [Download](https://nodejs.org/) |
| **Docker** | Latest | [Download](https://www.docker.com/get-started) |
| **Git LFS** | Any; run `git lfs install` once before cloning | [Download](https://git-lfs.com/) |

### 1. Clone the Repository

```bash
git clone https://github.com/mevcaus/JavaDropbox.git
cd JavaDropbox
```

The logos and favicon are stored in Git LFS. Cloned without it, they are small text pointers instead of images: run `git lfs pull` to fetch them. The Docker build stops with a message saying so rather than ship broken images.

### 2. Start Everything

```bash
./start.sh
```

This single command starts the **PostgreSQL database** (via Docker Compose, managed automatically by Spring Boot), the **Spring Boot backend** on `http://localhost:8080`, and the **Vite frontend** on `http://localhost:5173` — all concurrently. Dependencies are installed automatically on first run. `./gradlew bootRun` runs with the `dev` profile (`application-dev.properties`), which supplies the local database settings, enables Swagger UI and allows the Vite origin.

<details>
<summary><strong>Manual startup (individual steps)</strong></summary>

```bash
# Start the database
docker compose up -d

# Run the backend (dev profile)
./gradlew bootRun

# In a separate terminal, run the frontend
cd frontend
npm install
npm run dev
```

</details>

### 3. Initial Setup

1. Navigate to `http://localhost:5173`
2. While no account exists, the sign-in page offers **Set up the first user**; follow it to the **setup page** (the backend on port 8080 redirects there by itself). Enter the **setup code** printed in the backend's log (a banner reading *"No account exists yet…"* with a code like `K7QMT-9XH2C`), then choose your admin username and password (at least 8 characters)
3. Log in with your new credentials
4. Start uploading and managing files!

> **Forgot your password?** Store a new bcrypt hash on your account. `htpasswd` makes one (run it from the `httpd` image as here, or use a local `htpasswd`, which can prompt for the password if you leave out `-b` and the password); replace `admin` with your username:
>
> ```bash
> HASH=$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" 'my-new-password' | tr -d ':\n')
> docker compose exec postgres psql -U postgres -d javadropbox \
>   -c "UPDATE users SET password = '$HASH' WHERE username = 'admin'"
> ```
>
> `UPDATE 1` means it worked; the new password applies from the next sign-in, without a restart, and your files are untouched. `SELECT username FROM users` lists the accounts if you've forgotten the name too.

### Run with Docker

The image bundles the frontend into the backend, so one container serves the whole app:

```bash
docker compose --profile app up --build
```

Open `http://localhost:8080` and complete setup with the code from `docker compose logs app`. Files and their versions live in the `javadropbox-data` volume, and the database in `postgres-data`. Set `POSTGRES_PASSWORD` for anything beyond local use, before the first start: Postgres only reads it when it creates the `postgres-data` volume. To change it later, change it in the database as well, with `docker compose exec postgres psql -U postgres -c "ALTER USER postgres PASSWORD 'new-password'"`, then start again with the new `POSTGRES_PASSWORD`. The app service sits behind the `app` profile so that `./gradlew bootRun`, which starts `compose.yaml` for its database, doesn't also start a second copy of the app.

The container runs as uid and gid `10001`. To keep the files in a host directory instead of the volume, mount it at `/data` and hand it to that user first, e.g. `sudo chown -R 10001:10001 /srv/javadropbox`. A volume created by an image from before the uid was fixed belongs to a different uid; hand it over once with `docker compose run --rm --no-deps --user root --entrypoint chown app -R 10001:10001 /data`.

Behind a reverse proxy that terminates TLS, forward `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`, so the sign-in throttle sees each client's real address and share links carry your public `https://` address. The app only believes these headers from the proxies in `server.tomcat.remoteip.internal-proxies`, a regular expression matched against the connecting address; from anyone else they are ignored, so a client cannot choose its own address. It trusts loopback only by default, which suits a proxy on the same host. For a proxy anywhere else, such as another container, set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to its address, e.g. `172\.18\.0\.2`. Don't widen it to a whole network that untrusted machines can connect from.

### Configuration

Every property can also be set as an environment variable (`javadropbox.serving.directory` → `JAVADROPBOX_SERVING_DIRECTORY`).

| Property | Default | Purpose |
|----------|---------|---------|
| `spring.datasource.url` / `.username` / `.password` | none (the dev profile uses `compose.yaml`'s Postgres) | Database connection; required in production |
| `javadropbox.serving.directory` | `./JDB` | Where files are stored; also `--directory=/path` or a bare path as the first argument |
| `javadropbox.versions.max-retained` | `10` | Previous versions kept per file (0 or more; a negative value stops startup) |
| `javadropbox.storage.max-total-size` | none | Cap on everything stored, previous versions included (e.g. `50MB`); an upload or restore that would go over it is refused with `507` |
| `javadropbox.share.max-expiration` | `7d` | Longest lifetime a share link can be given |
| `spring.servlet.multipart.max-file-size` | `1024MB` | Largest file a single upload can carry |
| `app.setup.code` | generated per start | Fixed setup code for scripted installs: at least 10 characters (not counting dashes), and not printed to the log |
| `app.cors.allowed-origins` | none | Origins allowed to call the API cross-origin, comma-separated |
| `server.tomcat.remoteip.internal-proxies` | loopback only | Regex of reverse-proxy addresses whose `X-Forwarded-*` headers are trusted |
| `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` | `false` (`true` in dev) | Publish the OpenAPI spec and Swagger UI |

`app.share.jwt-secret` (`APP_SHARE_JWT_SECRET`) is gone: share links are stored on the server and no longer signed. The app refuses to start while it is set, so remove it when upgrading. A `.javadropbox/share-jwt.key` left by an earlier version is deleted on startup.

---

## Live Demo

[javadropbox.mevcaus.dev](https://javadropbox.mevcaus.dev) is the same Docker image, deployed to [Fly.io](https://fly.io) with a [Neon](https://neon.tech) PostgreSQL database every time a merge to `main` passes CI. A `demo` Spring profile turns it into something safe to leave open to the public:

- **No setup.** The shared account (`demo` / `javadropbox`) is created at startup, and the sign-in page offers to fill it in.
- **A daily reset.** Every file, version, share link and history entry is deleted, and a few sample files are stored again, including one with versions to restore. Fly suspends the server while nobody is using it, and a scheduled job can't run during a suspend, so the reset runs on the first request after it falls due, before that request is handled.
- **Small limits**, so it can't be used as free file hosting: 5 MB per file, 50 MB in total (previous versions included), and share links that last at most 15 minutes. The total cap is a general `javadropbox.storage.max-total-size` setting, off by default.

---

## Database Migrations

The schema is owned by [Flyway](https://documentation.red-gate.com/flyway). Migrations live in `src/main/resources/db/migration` and run automatically on startup, before Hibernate starts. Hibernate runs with `ddl-auto=validate`: it checks that the entity classes match the migrated schema and refuses to start if they don't, but it never changes the database itself.

**Adding a migration.** Any change to an entity's columns, tables or constraints needs a matching migration:

1. Create `src/main/resources/db/migration/V<next number>__<what_it_does>.sql`, e.g. `V4__add_file_tags.sql`. Write plain PostgreSQL. (A migration that needs information SQL can't have goes in `src/main/java/db/migration` as a Java migration instead, like `V3__timestamps_with_time_zone`, which needs the JVM's time zone.)
2. Update the entity to match.
3. Run `./gradlew test`. The Flyway tests run every migration against a real PostgreSQL container and then validate the entities against the result, so a mismatch fails here instead of at startup.

Never edit a migration once it has been merged. Flyway checksums applied migrations and refuses to start if one changes; fix mistakes with a new migration.

**Existing installs.** Databases created before Flyway was introduced were built by Hibernate's `ddl-auto=update` and have no migration history. `spring.flyway.baseline-on-migrate=true` stamps them as version 1 instead of re-running the baseline, and `V1__baseline.sql` reproduces that Hibernate-generated schema exactly, constraint names included, so old and new databases converge on the same schema.

**What's there.** `V1` is the baseline. `V2` adds the constraints the app relies on (one metadata row per path, one account per username, cascading deletes for versions, history that outlives its file), cleaning up any duplicates the pre-V2 code could have created first. `V3` stores timestamps as `timestamptz`. `V4` allows one row per version number of a file, dropping duplicates left by concurrent replaces first. `V5` indexes `file_history.file_id`, so deleting files doesn't scan the whole history. `V6` stores share links on the server (`share_links`).

**Tests.** The H2 integration tests keep `ddl-auto=create-drop` with Flyway disabled, because the migrations are PostgreSQL SQL. Tests that need the real schema run against Testcontainers PostgreSQL through `PostgresTestSupport`, which applies the production Flyway and `ddl-auto` settings unchanged.

---

## API Reference

All endpoints require authentication unless noted otherwise. For a live, interactive reference of all REST API endpoints, visit the [Swagger UI](http://localhost:8080/swagger-ui.html) while the backend runs with the `dev` profile (`./gradlew bootRun`).

Errors come back as `{"message": "..."}` with a meaningful status: `400` for an invalid path or name, `403` for a wrong setup code, `404` when the item or version does not exist, `409` when something already exists or a concurrent change got in the way, `429` when sign-in or setup is throttled.

### Authentication

| Method | Endpoint | Auth Required | Description |
|--------|----------|:---:|-------------|
| `POST` | `/setup` | ❌ | Create the first account (`code`, `username`, `password`); only while none exists |
| `POST` | `/login` | ❌ | Authenticate with `username` + `password` (form-encoded) |
| `POST` | `/logout` | ❌ | Invalidate the session, if there is one (POST only, with the CSRF header) |
| `GET` | `/api/me` | ✅ | The signed-in user |
| `GET` | `/api/demo` | ❌ | Only in the `demo` profile: the shared account and the limits, for the sign-in page (`404` elsewhere) |

### Files and Folders

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/files` | Full tree as recursive JSON |
| `POST` | `/api/files` | Upload (`multipart/form-data`: one `files` part per file, plus `path`); replaced files keep their previous content as a version |
| `DELETE` | `/api/files?path=<path>` | Delete a file, or a folder with everything in it |
| `GET` | `/api/files/download?path=<path>` | Download a file, or a folder as a streamed `.zip` |
| `GET` | `/api/files/preview?path=<path>` | Serve a previewable file inline: images and PDFs as themselves, text and source files as `text/plain`; supports range requests. `400` for folders and other kinds of file |
| `POST` | `/api/folders` | Create a folder (`path` + `name`) |
| `GET` | `/api/storage` | Serving directory path + read/write status |

### Versioning

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/files/{fileId}/versions` | Versions of a file, newest first (`size` in bytes) |
| `POST` | `/api/files/{fileId}/versions/{version}/restore?mode=<OVERWRITE\|COPY>` | Restore a version in place or as a copy |

### Sharing

| Method | Endpoint | Auth Required | Description |
|--------|----------|:---:|-------------|
| `POST` | `/api/share?path=<path>&expirationMinutes=<n>` | ✅ | Create a time-limited share link (at most `javadropbox.share.max-expiration`, 7 days by default; 24 hours or that maximum if left out; not for the root): `{url, expiresAt}`. The URL is only returned here |
| `GET` | `/api/share?path=<path>` | ✅ | The path's links that have not expired or been revoked, soonest to expire first: `[{id, createdAt, expiresAt, createdBy}]` |
| `DELETE` | `/api/share/{id}` | ✅ | Revoke a link; it stops working at once |
| `GET` | `/share/{token}` | ❌ | Download a shared file or folder via its token |

### History

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/history?page=<n>&size=<n>` | One page of the audit log, newest first (`size` ≤ 200, default 50): `{items, page, size, totalItems, totalPages}` |

---

## Testing

The backend uses **JUnit 5** with **Spring Boot Test** and **MockMvc** for integration testing. Most tests run against an **H2 in-memory database** for speed; the setup and schema tests run against **PostgreSQL via Testcontainers**, so Docker must be running.

```bash
# Run all backend tests
./gradlew test
```

### Backend Test Coverage

| Test Suite | What It Covers |
|-----------|----------------|
| `FileOperationsIntegrationTests` | Delete-then-recreate, folder deletes removing child rows and versions, restore in place and as a copy (twice), versions kept per path, pruning, untracked files versioned before replace, failed uploads keeping the old content, failures recorded despite rollback, attribution to the signed-in user, 404s, history paging |
| `FileIntegrityIntegrationTests` | On PostgreSQL: concurrent replaces, restores and deletes of one file, the disk put back when a replace or restore fails before, after or at commit, folder deletes in a fixed number of statements |
| `MultipartErrorIntegrationTests` | On a real Tomcat: a server fault storing an upload is a 500, a malformed upload a 400 |
| `StoragePathSecurityTests` | The root under every spelling, `..` traversal, symlink escapes and loops, in-root symlinks aliasing the root or a reserved folder, the reserved `.versions`/`.javadropbox` directories in any letter case, dot-named uploads and folders, single-segment upload names |
| `StoragePathsTests`, `SymlinkSwapTests`, `StorageFilesTests`, `DownloadResponsesTests` | Path resolution on disk and on an in-memory case-insensitive filesystem (Jimfs), symlinks swapped in after the check for zip, delete, upload and download, upload scratch-file permissions |
| `PreviewIntegrationTests`, `PreviewTypeTests` | Inline serving and the `Content-Type` per kind, markup served as plain text, the sandbox on images and text, PDFs frameable by the app only while downloads stay unframeable, range requests, `400` for folders and other files, traversal, `401` when signed out, `previewType` in the tree, the extension rules |
| `DownloadIntegrationTests` | Folder zips (without symlinks), shared folder downloads, `Content-Disposition` for awkward names, range requests, links to deleted items, `Content-Security-Policy: sandbox` on every download |
| `FolderArchiveTests` | Zips leave out hidden files, FIFOs and entries that vanish while zipping, and keep empty folders |
| `ClientAbortIntegrationTests` / `IoExceptionHandlingTests` | Cancelled file and zip downloads are logged at debug only (on real Tomcat); nothing is written into a response already under way |
| `DownloadConnectionIntegrationTests` | A paused download holds no database connection, with a one-connection pool (on real Tomcat) |
| `SecurityIntegrationTests` | 401 for unauthenticated users, the API open to any signed-in account whatever its role, logout, JSON errors, the SPA shell served for client-side routes and for any other page a browser opens, but not for API calls, share links, missing files or non-HTML requests |
| `AuthIntegrationTests` | CSRF cookie round trip, any account can sign in, sign-in throttling |
| `LoginThrottleIntegrationTests` | Percent-encoded login URLs are throttled, a parallel burst gets no more than five password checks (on real Tomcat) |
| `TrustedProxyIntegrationTests` / `UntrustedForwardedHeadersIntegrationTests` | `X-Forwarded-*` headers only count from a trusted proxy: the throttled address, share-link URLs, the `Secure` session cookie (on real Tomcat) |
| `MultipartCsrfIntegrationTests` | Uploads that fail the CSRF check, or come from an anonymous client, write nothing to disk |
| `SetupIntegrationTests` | First-run redirects, the setup code and its throttling, validation, the app shell during setup, 409 after setup (on PostgreSQL) |
| `PasswordRecoveryIntegrationTests` | The "Forgot your password?" procedure above, on PostgreSQL with uploaded files: an `htpasswd` hash set by `UPDATE` signs in |
| `StorageQuotaIntegrationTests` | The storage cap: uploads and restored copies that would go over it are a `507` and leave nothing behind, and previous versions count toward it |
| `DemoIntegrationTests`, `DemoServiceTests` | The `demo` profile: the account signs in with no setup, `/api/demo` is public, the sample files (one with two versions) are stored, a due reset deletes everything and stores them again while one that is not due changes nothing, share links of at most 15 minutes; when the daily reset falls due |
| `ShareLinkIntegrationTests` | Link creation, expiry, listing and revoking, public download; tokens that reveal no path and are stored only hashed; links dying with their item even when the path is reused or the item changes type |
| `RetiredShareKeyTests` / `FlywayShareLinksMigrationTests` | No signing key is created, a leftover (even empty) key file is deleted, a configured secret stops startup; on PostgreSQL, links are deleted with their file and token hashes are unique |
| `CorsIntegrationTests` | Configured origins allowed, others refused |
| `SwaggerIntegrationTests` | Docs reachable with the setup filter active, spec lists every tag and endpoint |
| `FlywayMigrationIntegrationTests` / `FlywayBaselineIntegrationTests` | Migrations build an empty PostgreSQL database, and a pre-Flyway database is adopted; Hibernate validates both |
| `FlywayFileVersionMigrationTests` | V4 drops duplicate version rows before making versions unique; V5 lets deletes find history rows through an index |
| `FlywayIntegrityMigrationTests` / `FlywayTimestampMigrationTests` | V2 cleans up duplicate rows before adding constraints; V3 keeps each timestamp's instant under UTC, region and offset-style (`GMT+01:00`) JVM zones |
| `SetupServiceTests`, `LoginAttemptLimiterTests`, `JavadropboxApplicationArgumentsTests` | Setup codes throttled per client, lockout timing and bounds, command-line shorthands |
| `FileVersionServiceTests`, `ApiExceptionHandlerTests` | A negative retention limit stops startup; statuses for lost races and for upload parsing failures |

### Test Design Highlights
- **Test isolation**: Test classes with the same configuration share one Spring context and database, so each wipes every table after a test through `TestDatabase.wipe`
- **H2 substitution**: Test `application.properties` swaps PostgreSQL for H2 with `create-drop` DDL and Flyway disabled
- **Real PostgreSQL where it matters**: Schema and setup tests use a Testcontainers PostgreSQL 15 with the production Flyway settings, so the migrations are exercised on every build

### Frontend Tests

Component tests run under **Vitest** in a **jsdom** environment using **Testing Library**. They render the real component and drive it with real user events (typing, clicking, keyboard) rather than mocking its internals, and assert against the rendered DOM.

```bash
cd frontend
npm test
```

| Test Suite | What It Covers |
|-----------|----------------|
| `Dashboard.test.jsx`, `App.test.jsx` | Downloads through a link rather than into memory, previewing a file in the open folder by its full path, re-uploading the same file, keeping the table during refreshes, the open folder in the URL (reload and Back), dot-named uploads, unknown URLs redirecting |
| `Modal.test.jsx` | Escape, focus trap (including focus outside the panel or on a removed control) and focus restore with a fallback |
| `CreateFolderModal.test.jsx` | Closing only once the folder exists, the pending state, inline server errors, the dot-name rule |
| `ShareModal.test.jsx` | Expiry selection, errors, double-submit guard, clipboard fallback over plain http, ignoring a slow answer for the previous item, listing and revoking active links |
| `PreviewModal.test.jsx` | Images and PDFs from the preview endpoint, text fetched by range and shown unrendered, the cut-short notice ending on a whole line, empty files, server errors, starting over for the next file |
| `VersionHistoryModal.test.jsx` | Listing versions, restoring in either mode, a restore for one file not affecting the next file's dialog |
| `Login.test.jsx`, `Setup.test.jsx`, `Navbar.test.jsx` | Offering setup only while no account exists, errors announced as alerts, setup code and password checks, a failed logout keeping the user signed in |
| `authSlice.test.js`, `filesSlice.test.js`, `api.test.js`, `errors.test.js` | Session handling (logout is a POST, no password in the console), only the newest file listing applied, refreshing after failed mutations, CSRF priming, 401 handling, readable error messages |
| `ToastContext.test.jsx` | Errors announced assertively, toasts held while hovered or focused |
| `FileTable.test.jsx`, `Breadcrumbs.test.jsx`, `Sidebar.test.jsx` | Default folders-before-files ordering, recursive filename search with path labels and its empty state, search scoping to the current subtree, sorting by name/size/last-modified with direction toggling, `aria-sort` annotation and keyboard activation of headers, search clearing on folder navigation, actions reachable on touch screens and named after their file, previews offered only for files the server marks previewable, visible keyboard focus, the breadcrumb landmark |

### Code Style

Java is formatted with **google-java-format** via Spotless. `./gradlew build` fails on unformatted code, so run this before committing:

```bash
./gradlew spotlessApply
```

Every Java file under `src/` is checked. The codebase was reformatted in one commit when this was switched on; that commit is listed in `.git-blame-ignore-revs`, which GitHub's blame view honours automatically. To make local `git blame` skip it too:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

---

## CI/CD Pipeline

The GitHub Actions workflows run on every push and pull request to `main`:

```
Push/PR to main
      │
      ├──────────────────────────────┬───────────────────────────────┐
      ▼                              ▼                               ▼
┌──────────────────────────┐  ┌──────────────────────────┐  ┌──────────────────────────┐
│       Build Job          │  │       Docker Job         │  │      Frontend CI         │
│                          │  │                          │  │   (frontend-ci.yml)      │
│  JDK 21 (Temurin)        │  │  Checkout with LFS       │  │  npm ci                  │
│  ./gradlew build         │  │  compose up --wait       │  │  npm run lint            │
│   ├ tests (H2 +          │  │   (healthchecks)         │  │  npm test                │
│   │  Testcontainers)     │  │  curl / and its script   │  │  npm run build           │
│   └ spotlessCheck        │  │                          │  │                          │
└──────────────────────────┘  └──────────────────────────┘  └──────────────────────────┘

Dependency Submission Job (pushes to main only): generates the dependency graph for Dependabot alerts.
```

Once the Gradle workflow (build and Docker jobs) has passed on a push to `main`, `deploy-demo.yml` deploys that commit to the [live demo](#live-demo) on Fly.io.

---

## Observability

[Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/) exposes two endpoints and nothing else (no `env`, `beans`, `heapdump` and the like, which describe the server rather than its health):

| Endpoint | Access | What it reports |
|----------|--------|-----------------|
| `GET /actuator/health` | Public, even before setup | `UP`, or `DOWN` with `503` when the database is unreachable or the disk the files are stored on is nearly full. Signed in, it also shows each check (`db`, `diskSpace` for the serving directory, `ping`) |
| `GET /actuator/metrics` | Signed in | The names of all meters; `/actuator/metrics/<name>` gives one meter's values, filtered with `?tag=key:value` |

Health is public because load balancers and the Docker `HEALTHCHECK` call it without a session, and an anonymous caller learns only `UP` or `DOWN`. Metrics need a session. Every account is the owner today, and installs from before the setup code have a `ROLE_USER` owner, so this is a sign-in rule rather than an `ADMIN` one until there are accounts that aren't the owner's.

Beside the JVM, HTTP, Tomcat and connection-pool meters Spring Boot records, the app records what it is used for:

| Meter | Type | Counts |
|-------|------|--------|
| `javadropbox.files.served` | Counter, tagged `route` = `download`, `preview` or `share-link` | Files and zipped folders served. Requests for something missing aren't counted; each range request is, so a PDF viewer reading a file in parts counts more than once |
| `javadropbox.uploads.size` | Distribution summary, in bytes | One sample per stored file once its upload has committed: `COUNT` is files uploaded, `TOTAL` the bytes, `MAX` the largest |
| `javadropbox.share.links.created` | Counter | Share links created |

```bash
# Signed-in session cookie from the browser's dev tools
curl -b JSESSIONID=... 'http://localhost:8080/actuator/metrics/javadropbox.files.served?tag=route:share-link'
```

There is no Prometheus endpoint yet: a scraper has no way to sign in through the form login, and opening metrics to anonymous callers would publish usage figures. Scraping needs either a separate management port that only the monitoring network can reach, or a scrape credential that is throttled like sign-in.

---

## Future Roadmap

- [x] **File Previews** — In-browser preview for images, PDFs, and text files
- [ ] **Full-text Search** — Server-side search across file *contents* and metadata (filename search across the folder tree already works client-side)
- [ ] **Folder Upload** — Upload entire directory structures
- [ ] **Desktop Sync Client** — Background daemon that syncs a local folder with the server
- [ ] **Multi-user Support** — Role-based access control with per-user storage quotas
- [ ] **S3-compatible Backend** — Pluggable storage backend for cloud deployment

---

## License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details.
