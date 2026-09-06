<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white" alt="Spring Boot 3.5"/>
  <img src="https://img.shields.io/badge/React-19-61DAFB?style=for-the-badge&logo=react&logoColor=black" alt="React 19"/>
  <img src="https://img.shields.io/badge/PostgreSQL-15-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL"/>
  <img src="https://img.shields.io/badge/Docker-Ready-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker"/>
  <img src="https://img.shields.io/badge/CI-GitHub_Actions-2088FF?style=for-the-badge&logo=github-actions&logoColor=white" alt="GitHub Actions"/>
</p>

# ☁️ JavaDropbox

A **full-stack, self-hosted cloud storage platform** built from scratch — inspired by Dropbox, Google Drive, and OneDrive. Users can upload, download, version, and manage files through a modern React dashboard, backed by a secure Spring Boot REST API with PostgreSQL persistence.

> **Why I built this:** To deeply understand the systems that power cloud storage — from file I/O and streaming ZIP compression to session-based auth, file versioning, and recursive directory traversal — by implementing them myself rather than relying on abstractions.

---

## Table of Contents

- [Demo](#demo)
- [Architecture Overview](#architecture-overview)
- [Key Features](#key-features)
- [System Design Decisions](#system-design-decisions)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [API Reference](#api-reference)
- [Testing](#testing)
- [CI/CD Pipeline](#cicd-pipeline)
- [Future Roadmap](#future-roadmap)
- [License](#license)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client (Browser)                        │
│                    React 19 + Redux Toolkit                    │
│              Vite Dev Server (port 5173) + Proxy               │
└──────────────────────────┬──────────────────────────────────────┘
                           │  HTTP (REST API)
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                     │
│                        (port 8080)                             │
│                                                                │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────────┐ │
│  │   Security   │  │  Controllers  │  │   Exception Advice   │ │
│  │   Filter     │──│  (REST API)   │──│   (Global Handler)   │ │
│  │   Chain      │  │               │  │                      │ │
│  └──────┬───────┘  └───────┬───────┘  └──────────────────────┘ │
│         │                  │                                    │
│         ▼                  ▼                                    │
│  ┌──────────────┐  ┌───────────────┐                           │
│  │  AuthService │  │FileServingServ│                           │
│  │  (BCrypt +   │  │  (File I/O +  │                           │
│  │  Sessions)   │  │  Versioning)  │                           │
│  └──────┬───────┘  └──────┬┬───────┘                           │
│         │                 ││                                    │
│         ▼                 │▼                                    │
│  ┌──────────────────────────────────────────────────┐          │
│  │          Spring Data JPA Repositories            │          │
│  │  UserRepo │ FileMetadataRepo │ FileVersionRepo   │          │
│  │           │ FileHistoryRepo                      │          │
│  └──────────────────────┬───────────────────────────┘          │
└─────────────────────────┼───────────────────────────────────────┘
                          │  JDBC
                          ▼
              ┌───────────────────────┐       ┌──────────────────┐
              │   PostgreSQL 15       │       │   File System    │
              │   (Docker Container)  │       │   (Serving Dir)  │
              │                       │       │                  │
              │  • users              │       │  • User files    │
              │  • file_metadata      │       │  • .versions/    │
              │  • file_versions      │       │    (snapshots)   │
              │  • file_history       │       │                  │
              └───────────────────────┘       └──────────────────┘
```

The system follows a **layered architecture** with clear separation of concerns:

| Layer | Responsibility | Key Classes |
|-------|---------------|-------------|
| **Controller** | Request routing, input validation, HTTP response formatting | `WebController`, `FileVersionController`, `HistoryApi`, `LoginController` |
| **Service** | Core business logic, file I/O, versioning, path security | `FileServingService`, `AuthService` |
| **Repository** | Data access via Spring Data JPA | `FileMetadataRepository`, `FileVersionRepository`, `FileHistoryRepository`, `UserRepository` |
| **Model** | JPA entities mapping to PostgreSQL tables | `User`, `FileMetadata`, `FileVersion`, `FileHistory` |
| **DTO** | API response shaping, decoupling internal models from API contracts | `FileTreeNode`, `FileVersionDto`, `FileHistoryDto`, `DownloadableResource` |
| **Config** | Cross-cutting concerns: security, CORS, filters | `SecurityConfig`, `SetupFilter`, `PasswordConfig` |

---

## Key Features

### File Management
- **Upload** — Multi-file upload via drag-and-drop (react-dropzone) or file selector with `multipart/form-data` handling
- **Download** — Individual file downloads with proper MIME type detection; folder downloads as on-the-fly **streaming ZIP archives** (`ZipOutputStream`)
- **Delete** — Recursive directory deletion with cascading metadata cleanup
- **Create Folders** — Directory creation with path traversal validation

### File Versioning System
- **Automatic snapshotting** — On re-upload, the previous version is moved to a `.versions/` directory and tracked in the database
- **Version history** — Query all versions of a file via REST API (`GET /api/files/{fileId}/versions`)
- **Restore support** — Restore any previous version with two strategies:
  - `OVERWRITE` — Replace the current file (current version is snapshotted first)
  - `COPY` — Restore as a new file alongside the original
- **Configurable retention** — Automatic pruning of old versions beyond a configurable limit (default: 10)

### Security
- **Spring Security** integration with form-based login and session management
- **BCrypt password hashing** via `PasswordEncoder`
- **First-run setup flow** — Custom `OncePerRequestFilter` (`SetupFilter`) intercepts all requests and redirects to `/setup` until the first user is created
- **Path traversal protection** — All file operations validate that resolved paths stay within the serving directory boundary
- **CORS configuration** — Explicitly configured allowed origins for the Vite dev server
- **Session-based auth** with `JSESSIONID` cookie and automatic 401 interception on the frontend via Axios interceptors

### Audit Trail
- **Complete file history** — Every upload, delete, and folder creation is logged to the `file_history` table with timestamps, user attribution, and success/failure status
- **Error tracking** — Failed operations are recorded with error messages for debugging

### Frontend
- **React 19** SPA with **Redux Toolkit** for global state management
- **Responsive layout** with collapsible sidebar, breadcrumb navigation, and mobile hamburger menu
- **Smart file icons** — Context-aware icons based on file extension (images, video, audio, code, documents)
- **Storage quota indicator** — Visual progress bar showing disk usage
- **Protected routes** — `MainLayout` guards routes via Redux auth state with redirect-to-login

---

## System Design Decisions

### Why a Dual Storage Strategy (Filesystem + Database)?
Files are stored on the **filesystem** for performance and simplicity (no BLOB overhead), while **metadata, version history, and audit logs** live in PostgreSQL. This mirrors how production cloud storage systems like Dropbox work — the database serves as the source of truth for relationships and history, while the filesystem handles raw byte storage.

### Why On-the-Fly ZIP Streaming?
Folder downloads use `ZipOutputStream` writing to a `ByteArrayOutputStream` rather than creating temporary ZIP files on disk. This avoids disk I/O overhead and cleanup complexity, trading off memory usage for simplicity — an acceptable tradeoff for a self-hosted tool with bounded concurrent users.

### Why a Custom Setup Filter?
Rather than shipping hardcoded credentials or requiring environment variables, the application detects first-run state (no users in the database) and redirects to a setup wizard. This is implemented as a servlet filter (`SetupFilter`) ordered before Spring Security's `UsernamePasswordAuthenticationFilter`, ensuring the setup flow is accessible without authentication.

### Why Redux Toolkit Over React Context?
With async thunks for file operations (upload, delete, fetch, create directory), Redux Toolkit provides structured side-effect management via `createAsyncThunk`, built-in loading/error states, and DevTools integration — capabilities that would require significant boilerplate with plain Context + useReducer.

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Backend** | Java 21, Spring Boot 3.5 | REST API, dependency injection, auto-configuration |
| **Security** | Spring Security 6 | Authentication, authorization, CSRF, session management |
| **ORM** | Spring Data JPA + Hibernate | Object-relational mapping, repository pattern |
| **Database** | PostgreSQL 15 | Persistent storage for users, metadata, versions, history |
| **Testing** | JUnit 5, MockMvc, H2 (in-memory) | Integration tests with isolated test database |
| **Frontend** | React 19, Vite 7 | Component-based SPA with HMR |
| **State Mgmt** | Redux Toolkit | Centralized state with async thunk side effects |
| **HTTP Client** | Axios | API communication with interceptors for auth |
| **Styling** | Tailwind CSS 3 | Utility-first CSS framework |
| **Icons** | Lucide React | Consistent icon library |
| **DnD** | react-dropzone | Drag-and-drop file upload |
| **Build** | Gradle (Wrapper) | Backend build tool with Spring Boot plugin |
| **CI/CD** | GitHub Actions | Automated build, test, and dependency submission |
| **Containerization** | Docker, Docker Compose | Multi-stage build, PostgreSQL service |

---

## Project Structure

```
JavaDropbox/
├── .github/workflows/
│   └── gradle.yml                  # CI pipeline: build → test → dependency graph
├── frontend/                       # React SPA (Vite)
│   ├── src/
│   │   ├── components/             # Reusable UI components
│   │   │   ├── Breadcrumbs.jsx     #   Path navigation breadcrumbs
│   │   │   ├── CreateFolderModal.jsx
│   │   │   ├── DeleteConfirmationModal.jsx
│   │   │   ├── FileTable.jsx       #   File listing with context-aware icons
│   │   │   ├── InfoModal.jsx       #   Generic info/alert modal
│   │   │   ├── Logo.jsx
│   │   │   ├── Navbar.jsx          #   Top bar with user info and logout
│   │   │   ├── Sidebar.jsx         #   Navigation sidebar with storage quota
│   │   │   └── UploadModal.jsx     #   Drag-and-drop upload modal
│   │   ├── features/               # Redux slices
│   │   │   ├── authSlice.js        #   Login/logout async thunks + state
│   │   │   └── filesSlice.js       #   File CRUD thunks + tree selectors
│   │   ├── layouts/
│   │   │   └── MainLayout.jsx      #   Auth-guarded layout wrapper
│   │   ├── pages/
│   │   │   ├── Dashboard.jsx       #   Main file manager view
│   │   │   └── Login.jsx           #   Login form
│   │   ├── redux/
│   │   │   └── store.js            #   Redux store configuration
│   │   ├── services/
│   │   │   └── api.js              #   Axios instance with 401 interceptor
│   │   ├── App.jsx                 #   Route definitions
│   │   └── main.jsx                #   Entry point
│   ├── vite.config.js              # Dev proxy to Spring Boot backend
│   └── package.json
├── src/main/java/com/javadropbox/javadropbox/
│   ├── JavadropboxApplication.java # Entry point, CLI arg parsing
│   ├── config/
│   │   ├── SecurityConfig.java     # Filter chain, CORS, form login, remember-me
│   │   ├── SetupFilter.java        # First-run redirect filter
│   │   └── PasswordConfig.java     # BCrypt encoder bean
│   ├── controller/
│   │   ├── WebController.java      # File CRUD REST endpoints
│   │   ├── FileVersionController.java  # Version listing + restore endpoints
│   │   ├── HistoryApi.java         # Audit log endpoint
│   │   ├── LoginController.java    # Login/root page routing
│   │   ├── CustomErrorController.java  # Custom error page
│   │   └── FileUploadExceptionAdvice.java  # Global exception handler
│   ├── dto/
│   │   ├── DownloadableResource.java   # Record: resource + filename + MIME
│   │   ├── FileTreeNode.java       # Recursive tree node for directory listing
│   │   ├── FileItem.java           # Flat file listing DTO
│   │   ├── FileVersionDto.java     # Version history response DTO
│   │   └── FileHistoryDto.java     # Audit log response DTO (record)
│   ├── model/
│   │   ├── User.java               # JPA entity: users table
│   │   ├── FileMetadata.java       # JPA entity: file tracking + ownership
│   │   ├── FileVersion.java        # JPA entity: version snapshots
│   │   ├── FileHistory.java        # JPA entity: audit log entries
│   │   └── RestoreMode.java        # Enum: OVERWRITE | COPY
│   ├── repository/
│   │   ├── UserRepository.java
│   │   ├── FileMetadataRepository.java
│   │   ├── FileVersionRepository.java
│   │   └── FileHistoryRepository.java
│   └── service/
│       ├── FileServingService.java  # Core: file I/O, versioning, ZIP, security
│       └── AuthService.java        # User setup + lookup
├── src/test/
│   ├── java/com/javadropbox/javadropbox/
│   │   ├── SecurityIntegrationTests.java  # Auth, roles, session, content-type
│   │   ├── SetupIntegrationTests.java     # First-run flow, filter redirect
│   │   └── JavaDropBoxApplicationTests.java
│   └── resources/
│       └── application.properties  # H2 in-memory DB for test isolation
├── build.gradle                    # Dependencies, Spring Boot plugin
├── compose.yaml                    # PostgreSQL Docker service
├── Dockerfile                      # Multi-stage: Gradle build → JRE runtime
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
git clone https://github.com/mevcaus/JavaDropBox.git
cd JavaDropBox
```

### 2. Start Everything

```bash
./start.sh
```

This single command starts the **PostgreSQL database** (via Docker Compose, managed automatically by Spring Boot), the **Spring Boot backend** on `http://localhost:8080`, and the **Vite frontend** on `http://localhost:5173` — all concurrently. Frontend dependencies are installed automatically on first run.

<details>
<summary><strong>Manual startup (individual steps)</strong></summary>

```bash
# Start the database
docker compose up -d

# Run the backend
./gradlew bootRun

# In a separate terminal, run the frontend
cd frontend
npm install
npm run dev
```

</details>

### 3. Initial Setup

1. Navigate to `http://localhost:5173`
2. You'll be redirected to the **setup page** — create your admin username and password
3. Log in with your new credentials
4. Start uploading and managing files!

> **Forgot your password?** Delete all rows from the `users` table in PostgreSQL and restart the app to trigger the setup flow again.

---

## API Reference

All endpoints require authentication unless noted otherwise.

### Authentication

| Method | Endpoint | Auth Required | Description |
|--------|----------|:---:|-------------|
| `POST` | `/setup` | ❌ | Create the first user (only works once) |
| `POST` | `/login` | ❌ | Authenticate with `username` + `password` (form-encoded) |
| `POST` | `/logout` | ✅ | Invalidate session |

### File Operations

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/files` | Get full directory tree as recursive JSON |
| `GET` | `/api/directory-info` | Get serving directory path + read/write status |
| `GET` | `/api/download?path=<path>` | Download file or folder (folders returned as `.zip`) |
| `POST` | `/api/upload` | Upload files (`multipart/form-data`: `files[]` + `path`) |
| `DELETE` | `/api/delete?path=<path>` | Delete file or folder recursively |
| `POST` | `/api/create-directory` | Create folder (`path` + `name` params) |

### Versioning

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/files/{fileId}/versions` | List all versions of a file |
| `POST` | `/api/files/{fileId}/versions/{version}/restore?mode=<OVERWRITE\|COPY>` | Restore a specific version |

### History

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/history` | Get full audit log (sorted by timestamp descending) |

---

## Testing

The project uses **JUnit 5** with **Spring Boot Test** and **MockMvc** for integration testing. Tests run against an **H2 in-memory database** for isolation.

```bash
# Run all tests
./gradlew test
```

### Test Coverage

| Test Suite | What It Covers |
|-----------|----------------|
| `SecurityIntegrationTests` | 401 for unauthenticated users, role-based access (USER/ADMIN), session logout, JSON content type verification |
| `SetupIntegrationTests` | First-run redirect behavior, setup form validation (missing/empty fields), filter bypass for setup page, post-setup lockout |

### Test Design Highlights
- **Test isolation**: Each test class manages its own `@BeforeEach`/`@AfterEach` lifecycle, cleaning up users and metadata between runs to prevent test pollution
- **H2 substitution**: Test `application.properties` swaps PostgreSQL for H2 with `create-drop` DDL, ensuring a clean schema per test run
- **Setup filter control**: The `app.setup.filter.enabled` property allows tests to toggle the setup redirect behavior independently

---

## CI/CD Pipeline

The GitHub Actions workflow (`.github/workflows/gradle.yml`) runs on every push and pull request to `main`:

```
Push/PR to main
      │
      ▼
┌─────────────────────────────────┐
│         Build Job               │
│                                 │
│  1. Checkout code               │
│  2. Setup JDK 21 (Temurin)     │
│  3. Start PostgreSQL service    │
│  4. ./gradlew build             │
│  5. ./gradlew test              │
└─────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────┐
│    Dependency Submission Job    │
│                                 │
│  Generates dependency graph     │
│  for Dependabot Alerts          │
└─────────────────────────────────┘
```

---

## Future Roadmap

- [ ] **File Previews** — In-browser preview for images, PDFs, and text files
- [ ] **Search** — Full-text search across filenames and metadata
- [ ] **Sorting** — Sort files by name, date, size, or type
- [ ] **Folder Upload** — Upload entire directory structures
- [ ] **Sharing** — Generate shareable links with optional expiry and passwords
- [ ] **Desktop Sync Client** — Background daemon that syncs a local folder with the server
- [ ] **Multi-user Support** — Role-based access control with per-user storage quotas
- [ ] **S3-compatible Backend** — Pluggable storage backend for cloud deployment

---

## License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details.
