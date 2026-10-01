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

> **Why I built this:** To deeply understand the systems that power cloud storage — from file I/O and streaming ZIP compression to session-based auth, file versioning, and recursive directory traversal — by implementing them myself rather than relying on abstractions.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Key Features](#key-features)
- [System Design Decisions](#system-design-decisions)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Database Migrations](#database-migrations)
- [API Reference](#api-reference)
- [Testing](#testing)
- [CI/CD Pipeline](#cicd-pipeline)
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
│  │  FileVersionService · SetupService · ShareTokenService    │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                Spring Data JPA Repositories               │  │
│  │      User · FileMetadata · FileVersion · FileHistory      │  │
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
| **Service** | Path validation, file I/O, versioning, audit log, setup, share tokens | `StoragePaths`, `FileService`, `FileTreeService`, `FileVersionService`, `FileHistoryService`, `SetupService`, `AuthService`, `ShareTokenService` |
| **Repository** | Data access via Spring Data JPA | `FileMetadataRepository`, `FileVersionRepository`, `FileHistoryRepository`, `UserRepository` |
| **Model** | JPA entities mapping to PostgreSQL tables | `User`, `FileMetadata`, `FileVersion`, `FileHistory` |
| **DTO** | API response shaping, decoupling internal models from API contracts | `FileTreeNode`, `FileVersionDto`, `FileHistoryDto`, `HistoryPage`, `Download` |
| **Config** | Cross-cutting concerns: security, CORS, filters, sign-in throttling | `SecurityConfig`, `SetupFilter`, `LoginThrottleFilter`, `PasswordConfig`, `StartupBanner` |

---

## Key Features

### File Management
- **Upload** — Multi-file upload with `multipart/form-data`; each file is written to a scratch file and renamed into place, so a dropped upload never leaves a truncated file behind
- **Download** — File downloads with MIME type detection and HTTP range support (resumable); folder downloads are **ZIP archives streamed straight to the response**, so their size never has to fit in memory
- **Delete** — Recursive deletion that also removes the metadata rows and stored versions of everything inside
- **Create Folders** — Folder creation with single-segment name validation

### File Versioning System
- **Automatic snapshotting** — On re-upload, the previous content is moved to `.versions/<file id>/v<n>` and tracked in the database (so same-named files in different folders never share storage)
- **Version history** — A Versions action on each tracked file lists its versions (size, date, author); also available as `GET /api/files/{fileId}/versions`
- **Restore support** — Restore any previous version with two strategies:
  - `OVERWRITE` — Replace the current file (current version is snapshotted first)
  - `COPY` — Restore as a new file alongside the original (`name_v2.txt`, or `name_v2 (2).txt` if that is taken)
- **Configurable retention** — Automatic pruning of old versions beyond a configurable limit, set via `javadropbox.versions.max-retained` in `application.properties` (default: 10)

### Security
- **Spring Security** integration with form-based login, session management and cookie-based CSRF protection
- **BCrypt password hashing** via `PasswordEncoder`
- **First-run setup flow** — `SetupFilter` redirects every request to `/setup` until the first account exists, and creating it requires a **one-time setup code printed in the server log**, so only whoever runs the server can claim it; five wrong codes from one address lock that address out for 15 minutes, without changing the code
- **Path safety** — Every client-supplied path goes through `StoragePaths`, which rejects anything outside the serving directory (after `..` is normalized *and* after symlinks are followed), the app's own `.versions/` and `.javadropbox/` directories, and the root itself for delete and share
- **Sign-in throttling** — Five failed sign-ins from one address within 15 minutes lock it out for 15 minutes (`429` with `Retry-After`)
- **CORS** — Off unless `app.cors.allowed-origins` lists origins (the dev profile allows the Vite dev server)
- **Session-based auth** with `JSESSIONID` cookie and automatic 401 interception on the frontend via Axios interceptors

### Share Links
- **Time-limited public links** — `POST /api/share` issues a stateless, HMAC-SHA256 signed JWT encoding the file path and expiry; no server-side token storage
- **Per-install signing key** — Generated on first start and kept in `.javadropbox/share-jwt.key` (owner-only permissions) unless `APP_SHARE_JWT_SECRET` is set; no key ships with the code
- **Public download** — `GET /share/{token}` validates signature and expiration, then streams the file through the same path-traversal-safe serving logic
- **Bounded lifetime** — Expiration is capped at 7 days; links for nonexistent paths, and for the root folder, are rejected at creation
- **Frontend** — Share icon in the file table opens a modal to pick an expiry and copy the generated link

### API Documentation
- **OpenAPI 3 spec** generated at runtime by springdoc from the actual Spring mappings, so it cannot drift from the code
- **Swagger UI** at `/swagger-ui.html` — browse and execute every endpoint; published only by the `dev` profile (off in production), and reachable there without authentication, including before first-run setup

### Audit Trail
- **Complete file history** — Every upload, delete, folder creation and restore is logged to the `file_history` table with timestamps, the signed-in user, and success/failure status; `GET /api/history` pages through it
- **Error tracking** — Failed operations are recorded with error messages, in a transaction of their own so the entry survives the failed operation's rollback

### Frontend
- **React 19** SPA with **Redux Toolkit** for global state management
- **Responsive layout** with collapsible sidebar, breadcrumb navigation, and mobile hamburger menu
- **Smart file icons** — Context-aware icons based on file extension (images, video, audio, code, documents)
- **File search** — Search box above the file table matches filenames (case-insensitive substring) across the open folder **and every folder beneath it**, flattening results into a list labelled with each match's full path; runs entirely client-side against the already-loaded tree, so no extra request is made
- **Column sorting** — Name, Size, and Last Modified headers sort in either direction, keyboard-operable and annotated with `aria-sort`; folders stay grouped ahead of files in every ordering
- **Version history** — Browse a file's previous versions and restore one in place or as a copy
- **Accessible dialogs** — One `Modal` shell: Escape closes, focus is trapped inside and restored on close
- **Protected routes** — `MainLayout` guards routes via Redux auth state with redirect-to-login

---

## System Design Decisions

### Why a Dual Storage Strategy (Filesystem + Database)?
Files are stored on the **filesystem** for performance and simplicity (no BLOB overhead), while **metadata, version history, and audit logs** live in PostgreSQL. This mirrors how production cloud storage systems like Dropbox work — the database serves as the source of truth for relationships and history, while the filesystem handles raw byte storage.

### Why On-the-Fly ZIP Streaming?
Folder downloads write a `ZipOutputStream` straight to the HTTP response (`FolderArchive`) rather than building the archive in memory or in a temporary file. Memory use stays flat however large the folder is, and there is nothing to clean up afterwards. The cost is that the size is not known up front, so the response has no `Content-Length`.

### Why a Custom Setup Filter and a Setup Code?
Rather than shipping hardcoded credentials or requiring environment variables, the application detects first-run state (no users in the database) and redirects to a setup wizard. This is implemented as a filter (`SetupFilter`) inside Spring Security's chain, ahead of `UsernamePasswordAuthenticationFilter`, so the setup flow is reachable without authentication.

Reachable without authentication also means reachable by whoever finds the server first. So setup additionally needs a one-time code that the server prints to its log on startup (the approach Jupyter takes): the person who installed the server can read it, someone who merely found the address cannot.

### Why Stateless JWT Share Links?
Share links encode the file path and expiry in an HMAC-SHA256 signed JWT rather than storing tokens in a database table. The signature makes the link tamper-evident and the expiry self-enforcing, so no table needs cleaning up and no lookup happens on the download path. The tradeoff is that a link cannot be revoked before it expires — acceptable given the 7-day cap, and the share dialog says so. A folder link also serves the folder as it is at download time, including files added after the link was made.

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
| **Auth Tokens** | JJWT 0.12 | Signed, stateless share-link tokens (HMAC-SHA256) |
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
│   │   │   ├── StartupBanner.java       # Logs the bound port and serving directory
│   │   │   └── PasswordConfig.java      # BCrypt encoder bean
│   │   ├── controller/
│   │   │   ├── FileController.java      # Tree, upload, download, delete, folders, storage info
│   │   │   ├── FileVersionController.java  # Version listing + restore
│   │   │   ├── HistoryController.java   # Paged audit log
│   │   │   ├── ShareController.java     # Share-link creation + public download
│   │   │   ├── SetupController.java     # First-run account creation
│   │   │   ├── AuthController.java      # Current user
│   │   │   ├── SpaController.java       # Serves the built app for client-side routes
│   │   │   ├── DownloadResponses.java   # File/zip responses, Content-Disposition
│   │   │   └── ApiExceptionHandler.java # Exceptions -> {"message"} with the right status
│   │   ├── dto/                    # FileTreeNode, FileVersionDto, FileHistoryDto, HistoryPage, Download
│   │   ├── exception/              # BadRequest (400), Forbidden (403), NotFound (404), Conflict (409)
│   │   ├── model/                  # JPA entities: User, FileMetadata, FileVersion, FileHistory
│   │   ├── repository/             # Spring Data repositories
│   │   └── service/
│   │       ├── StoragePaths.java        # The one place client paths become filesystem paths
│   │       ├── FileService.java         # Upload, delete, create folder, restore, download
│   │       ├── FileTreeService.java     # The browsable tree
│   │       ├── FileVersionService.java  # Archiving, pruning and looking up versions
│   │       ├── FileHistoryService.java  # Audit log, including failures
│   │       ├── FolderArchive.java       # Streams a folder as a zip
│   │       ├── SetupService.java        # Setup code + first account
│   │       ├── ShareTokenService.java   # JWT signing/validation, generated key
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
| **JDK** | 21+ | [Download](https://www.oracle.com/java/technologies/downloads/) |
| **Node.js** | 20.19+ or 22.12+ | [Download](https://nodejs.org/) |
| **Docker** | Latest | [Download](https://www.docker.com/get-started) |

### 1. Clone the Repository

```bash
git clone https://github.com/mevcaus/JavaDropbox.git
cd JavaDropbox
```

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
2. You'll be redirected to the **setup page**. Enter the **setup code** printed in the backend's log (a banner reading *"No account exists yet…"* with a code like `K7QMT-9XH2C`), then choose your admin username and password (at least 8 characters)
3. Log in with your new credentials
4. Start uploading and managing files!

> **Forgot your password?** Delete all rows from the `users` table in PostgreSQL and restart the app to trigger the setup flow again; a new setup code is printed on startup.

### Run with Docker

The image bundles the frontend into the backend, so one container serves the whole app:

```bash
docker compose --profile app up --build
```

Open `http://localhost:8080` and complete setup with the code from `docker compose logs app`. Files, their versions and the share-link key live in the `javadropbox-data` volume, and the database in `postgres-data`. Set `POSTGRES_PASSWORD` for anything beyond local use. The app service sits behind the `app` profile so that `./gradlew bootRun`, which starts `compose.yaml` for its database, doesn't also start a second copy of the app.

Behind a reverse proxy that terminates TLS, forward `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`, so the sign-in throttle sees each client's real address and share links carry your public `https://` address. The app only believes these headers from the proxies in `server.tomcat.remoteip.internal-proxies`, a regular expression matched against the connecting address; from anyone else they are ignored, so a client cannot choose its own address. It trusts loopback only by default, which suits a proxy on the same host. For a proxy anywhere else, such as another container, set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to its address, e.g. `172\.18\.0\.2`. Don't widen it to a whole network that untrusted machines can connect from.

### Configuration

Every property can also be set as an environment variable (`javadropbox.serving.directory` → `JAVADROPBOX_SERVING_DIRECTORY`).

| Property | Default | Purpose |
|----------|---------|---------|
| `spring.datasource.url` / `.username` / `.password` | none (the dev profile uses `compose.yaml`'s Postgres) | Database connection; required in production |
| `javadropbox.serving.directory` | `./JDB` | Where files are stored; also `--directory=/path` or a bare path as the first argument |
| `javadropbox.versions.max-retained` | `10` | Previous versions kept per file |
| `app.share.jwt-secret` | generated per install | Share-link signing key (base64, ≥ 256 bits); set only to share a key between instances |
| `app.setup.code` | generated per start | Fixed setup code for scripted installs: at least 10 characters (not counting dashes), and not printed to the log |
| `app.cors.allowed-origins` | none | Origins allowed to call the API cross-origin, comma-separated |
| `server.tomcat.remoteip.internal-proxies` | loopback only | Regex of reverse-proxy addresses whose `X-Forwarded-*` headers are trusted |
| `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` | `false` (`true` in dev) | Publish the OpenAPI spec and Swagger UI |

---

## Database Migrations

The schema is owned by [Flyway](https://documentation.red-gate.com/flyway). Migrations live in `src/main/resources/db/migration` and run automatically on startup, before Hibernate starts. Hibernate runs with `ddl-auto=validate`: it checks that the entity classes match the migrated schema and refuses to start if they don't, but it never changes the database itself.

**Adding a migration.** Any change to an entity's columns, tables or constraints needs a matching migration:

1. Create `src/main/resources/db/migration/V<next number>__<what_it_does>.sql`, e.g. `V4__add_file_tags.sql`. Write plain PostgreSQL. (A migration that needs information SQL can't have goes in `src/main/java/db/migration` as a Java migration instead, like `V3__timestamps_with_time_zone`, which needs the JVM's time zone.)
2. Update the entity to match.
3. Run `./gradlew test`. The Flyway tests run every migration against a real PostgreSQL container and then validate the entities against the result, so a mismatch fails here instead of at startup.

Never edit a migration once it has been merged. Flyway checksums applied migrations and refuses to start if one changes; fix mistakes with a new migration.

**Existing installs.** Databases created before Flyway was introduced were built by Hibernate's `ddl-auto=update` and have no migration history. `spring.flyway.baseline-on-migrate=true` stamps them as version 1 instead of re-running the baseline, and `V1__baseline.sql` reproduces that Hibernate-generated schema exactly, constraint names included, so old and new databases converge on the same schema.

**What's there.** `V1` is the baseline. `V2` adds the constraints the app relies on (one metadata row per path, one account per username, cascading deletes for versions, history that outlives its file), cleaning up any duplicates the pre-V2 code could have created first. `V3` stores timestamps as `timestamptz`.

**Tests.** The H2 integration tests keep `ddl-auto=create-drop` with Flyway disabled, because the migrations are PostgreSQL SQL. Tests that need the real schema run against Testcontainers PostgreSQL through `PostgresTestSupport`, which applies the production Flyway and `ddl-auto` settings unchanged.

---

## API Reference

All endpoints require authentication unless noted otherwise. For a live, interactive reference of all REST API endpoints, visit the [Swagger UI](http://localhost:8080/swagger-ui.html) while the backend runs with the `dev` profile (`./gradlew bootRun`).

Errors come back as `{"message": "..."}` with a meaningful status: `400` for an invalid path or name, `403` for a wrong setup code, `404` when the item or version does not exist, `409` when something already exists, `429` when sign-in or setup is throttled.

### Authentication

| Method | Endpoint | Auth Required | Description |
|--------|----------|:---:|-------------|
| `POST` | `/setup` | ❌ | Create the first account (`code`, `username`, `password`); only while none exists |
| `POST` | `/login` | ❌ | Authenticate with `username` + `password` (form-encoded) |
| `POST` | `/logout` | ✅ | Invalidate the session (POST only, with the CSRF header) |
| `GET` | `/api/me` | ✅ | The signed-in user |

### Files and Folders

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/files` | Full tree as recursive JSON |
| `POST` | `/api/files` | Upload (`multipart/form-data`: `files[]` + `path`); replaced files keep their previous content as a version |
| `DELETE` | `/api/files?path=<path>` | Delete a file, or a folder with everything in it |
| `GET` | `/api/files/download?path=<path>` | Download a file, or a folder as a streamed `.zip` |
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
| `POST` | `/api/share?path=<path>&expirationMinutes=<n>` | ✅ | Issue a signed, time-limited share link (max 7 days; not for the root) |
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
| `StoragePathSecurityTests` | The root under every spelling, `..` traversal, symlink escapes and loops, the reserved `.versions`/`.javadropbox` directories, single-segment upload names |
| `DownloadIntegrationTests` | Folder zips (without symlinks), shared folder downloads, `Content-Disposition` for awkward names, range requests, links to deleted items or the root, `Content-Security-Policy: sandbox` on every download |
| `FolderArchiveTests` | Zips leave out hidden files, FIFOs and entries that vanish while zipping, and keep empty folders |
| `ClientAbortIntegrationTests` / `IoExceptionHandlingTests` | Cancelled file and zip downloads are logged at debug only (on real Tomcat); nothing is written into a response already under way |
| `DownloadConnectionIntegrationTests` | A paused download holds no database connection, with a one-connection pool (on real Tomcat) |
| `SecurityIntegrationTests` | 401 for unauthenticated users, role-based access, logout, JSON errors, the SPA shell served for client-side routes |
| `AuthIntegrationTests` | CSRF cookie round trip, any account can sign in, sign-in throttling |
| `LoginThrottleIntegrationTests` | Percent-encoded login URLs are throttled, a parallel burst gets no more than five password checks (on real Tomcat) |
| `TrustedProxyIntegrationTests` / `UntrustedForwardedHeadersIntegrationTests` | `X-Forwarded-*` headers only count from a trusted proxy: the throttled address, share-link URLs, the `Secure` session cookie (on real Tomcat) |
| `MultipartCsrfIntegrationTests` | Uploads that fail the CSRF check, or come from an anonymous client, write nothing to disk |
| `SetupIntegrationTests` | First-run redirects, the setup code and its throttling, validation, the app shell during setup, 409 after setup (on PostgreSQL) |
| `ShareLinkIntegrationTests` / `ShareTokenServiceTests` | Link issue/expiry/tamper and public download; the generated per-install key, and rejection of tokens signed with the formerly published key |
| `CorsIntegrationTests` | Configured origins allowed, others refused |
| `SwaggerIntegrationTests` | Docs reachable with the setup filter active, spec lists every tag and endpoint |
| `FlywayMigrationIntegrationTests` / `FlywayBaselineIntegrationTests` | Migrations build an empty PostgreSQL database, and a pre-Flyway database is adopted; Hibernate validates both |
| `FlywayIntegrityMigrationTests` / `FlywayTimestampMigrationTests` | V2 cleans up duplicate rows before adding constraints; V3 keeps each timestamp's instant |
| `SetupServiceTests`, `LoginAttemptLimiterTests`, `JavadropboxApplicationArgumentsTests` | Setup codes throttled per client, lockout timing and bounds, command-line shorthands |

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
| `Dashboard.test.jsx` | Downloads through a link rather than into memory, re-uploading the same file, keeping the table during refreshes |
| `Modal.test.jsx` | Escape, focus trap and focus restore |
| `ShareModal.test.jsx` | Expiry selection, errors, double-submit guard, clipboard fallback over plain http |
| `VersionHistoryModal.test.jsx` | Listing versions and restoring in either mode |
| `Setup.test.jsx`, `authSlice.test.js`, `filesSlice.test.js`, `api.test.js` | Setup code and password checks, session handling (logout is a POST), CSRF priming, 401 handling |
| `FileTable.test.jsx` | Default folders-before-files ordering, recursive filename search with path labels and its empty state, search scoping to the current subtree, sorting by name/size/last-modified with direction toggling, `aria-sort` annotation and keyboard activation of headers, and search clearing on folder navigation |

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
│  ./gradlew build         │  │  docker build .          │  │  npm run lint            │
│   ├ tests (H2 +          │  │   (frontend bundle +     │  │  npm test                │
│   │  Testcontainers)     │  │    backend jar)          │  │  npm run build           │
│   └ spotlessCheck        │  │                          │  │                          │
└──────────────────────────┘  └──────────────────────────┘  └──────────────────────────┘

Dependency Submission Job: generates the dependency graph for Dependabot alerts.
```

---

## Future Roadmap

- [ ] **File Previews** — In-browser preview for images, PDFs, and text files
- [ ] **Full-text Search** — Server-side search across file *contents* and metadata (filename search across the folder tree already works client-side)
- [ ] **Folder Upload** — Upload entire directory structures
- [ ] **Desktop Sync Client** — Background daemon that syncs a local folder with the server
- [ ] **Multi-user Support** — Role-based access control with per-user storage quotas
- [ ] **S3-compatible Backend** — Pluggable storage backend for cloud deployment

---

## License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details.
