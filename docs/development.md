# Developing JavaDropbox

Running the app from source, how the code is laid out, and the conventions the build enforces. To run your own instance in Docker, see [self-hosting.md](self-hosting.md).

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **JDK** | 17–24 to run Gradle; the build uses JDK 21 and downloads it if missing | [Download](https://adoptium.net/temurin/releases/?version=21) |
| **Node.js** | 20.19+ or 22.12+ | [Download](https://nodejs.org/) |
| **Docker** | Latest | [Download](https://www.docker.com/get-started) |
| **Git LFS** | Any; run `git lfs install` once before cloning | [Download](https://git-lfs.com/) |

## Get the code

```bash
git clone https://github.com/mevcaus/JavaDropbox.git
cd JavaDropbox
```

The logos and favicon are stored in Git LFS. Cloned without it, they are small text pointers instead of images: run `git lfs pull` to fetch them.

## Run it

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

Then open `http://localhost:5173`. While no account exists, the sign-in page offers **Set up the first user**; the setup code is in the backend's log (see [self-hosting.md](self-hosting.md#first-run-setup)). With the `dev` profile, the Swagger UI at `http://localhost:8080/swagger-ui.html` lists and runs every endpoint, and [api.md](api.md) has the same reference in writing.

### With the files in S3

To work on the S3 store, run an S3 server and point the backend at a bucket in it. [S3Mock](https://github.com/adobe/S3Mock), which the tests use, keeps everything in the container, so its bucket is empty again on every start:

```bash
docker run --rm -p 9090:9090 -e COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS=javadropbox adobe/s3mock:5.2.2

JAVADROPBOX_STORAGE_TYPE=s3 \
JAVADROPBOX_STORAGE_S3_BUCKET=javadropbox \
JAVADROPBOX_STORAGE_S3_ENDPOINT=http://localhost:9090 \
JAVADROPBOX_STORAGE_S3_PATH_STYLE_ACCESS=true \
JAVADROPBOX_STORAGE_S3_ACCESS_KEY=any JAVADROPBOX_STORAGE_S3_SECRET_KEY=any \
./gradlew bootRun
```

Everything else is as above, with the search index still in `./JDB/.javadropbox`. [self-hosting.md](self-hosting.md#storing-files-in-s3) has every S3 setting.

## Project structure

```
JavaDropbox/
├── .github/workflows/
│   ├── gradle.yml                  # Backend build + tests, Docker image build, dependency graph
│   ├── frontend-ci.yml             # Frontend lint, tests, production build
│   └── deploy-demo.yml             # Deploys the live demo once CI passes on main
├── frontend/                       # React SPA (Vite)
│   ├── src/
│   │   ├── components/
│   │   │   ├── Modal.jsx           #   Shared dialog shell: Escape, focus trap, focus restore
│   │   │   ├── CreateFolderModal.jsx
│   │   │   ├── DeleteConfirmationModal.jsx
│   │   │   ├── ShareModal.jsx      #   Share-link creation and the active links
│   │   │   ├── CopyLinkField.jsx   #   A link shown once, with copying that works over plain http
│   │   │   ├── InviteModal.jsx     #   Admins: invite someone, with a role and a quota
│   │   │   ├── QuotaModal.jsx      #   Admins: change an account's quota (QuotaFields: amount + unit)
│   │   │   ├── VersionHistoryModal.jsx  # List and restore previous versions
│   │   │   ├── PreviewModal.jsx    #   The preview dialog on the dashboard
│   │   │   ├── FilePreview.jsx     #   Image, PDF and text previews, for the dialog and share pages
│   │   │   ├── FileIcon.jsx        #   Folder and file-type icons
│   │   │   ├── FileTable.jsx       #   File listing with search results, sorting, row actions
│   │   │   ├── Breadcrumbs.jsx     #   Path navigation breadcrumbs
│   │   │   ├── Navbar.jsx          #   Top bar with user info and logout
│   │   │   ├── Sidebar.jsx         #   Navigation, and storage used against the quota
│   │   │   ├── DemoBanner.jsx      #   The live demo's limits and next reset
│   │   │   └── Logo.jsx, AnimatedLogo.jsx
│   │   ├── features/               # Redux slices
│   │   │   ├── authSlice.js        #   Login/logout/session thunks + state, the role
│   │   │   └── filesSlice.js       #   File thunks, tree selectors, endpoint paths
│   │   ├── hooks/                  # useFileSearch (search as you type), useToast, useDemoInfo
│   │   ├── layouts/MainLayout.jsx  # Auth-guarded layout wrapper
│   │   ├── pages/                  # Dashboard, Login, Setup, Admin (accounts), Shared (a share
│   │   │                           #   link's public page), AccountLink (invitation and reset pages)
│   │   ├── services/api.js         # Axios instance: CSRF priming, 401 handler hook
│   │   ├── utils/                  # date, errors (readableError), format (formatSize), quota, clipboard
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
│   │   │   ├── AccountSessionFilter.java  # Ends sessions of disabled or changed accounts
│   │   │   ├── LoginAttemptLimiter.java # Failed sign-in counting per address
│   │   │   ├── LoginThrottleFilter.java # 429 for locked-out addresses
│   │   │   ├── SpaFallbackFilter.java   # App shell for pages the server has no route for
│   │   │   ├── StartupBanner.java       # Logs the bound port and where files are stored
│   │   │   ├── StorageConfig.java       # The file store: the serving directory, or an S3 bucket
│   │   │   ├── DemoResetFilter.java     # Live demo: runs the daily reset when it falls due
│   │   │   ├── RetiredShareKey.java     # Refuses the old share-link secret, deletes the old key file
│   │   │   └── PasswordConfig.java      # BCrypt encoder bean
│   │   ├── controller/
│   │   │   ├── FileController.java      # Tree, upload, download, preview, delete, folders, storage use
│   │   │   ├── FileVersionController.java  # Version listing + restore
│   │   │   ├── HistoryController.java   # Paged audit log
│   │   │   ├── ShareController.java     # Share-link creation, listing, revoking + the public page's routes
│   │   │   ├── SearchController.java    # Search by name and file contents
│   │   │   ├── SetupController.java     # First-run account creation
│   │   │   ├── AdminController.java     # Accounts, invitations, quotas, password resets (admins)
│   │   │   ├── AccountLinkController.java  # The public side of invitations and password resets
│   │   │   ├── AuthController.java      # Current user and role
│   │   │   ├── DemoController.java      # Live demo: the shared account and limits
│   │   │   ├── SpaController.java       # Serves the built app for client-side routes
│   │   │   ├── DownloadResponses.java   # File/zip/preview responses, Content-Disposition, CSP
│   │   │   └── ApiExceptionHandler.java # Exceptions -> {"message"} with the right status
│   │   ├── dto/                    # FileTreeNode, FileVersionDto, FileHistoryDto, HistoryPage, Download, Preview, ShareLinkDto, SharedItemDto, SearchResults, SearchResult, Snippet, AccountDto, InviteDto, AccountLinkInfo
│   │   ├── exception/              # BadRequest (400), Forbidden (403), NotFound (404), Conflict (409)
│   │   ├── model/                  # JPA entities: User, FileMetadata, FileVersion, FileHistory, ShareLink, AccountLink; PreviewType
│   │   ├── repository/             # Spring Data repositories
│   │   └── service/
│   │       ├── StoragePaths.java        # The one place client paths become keys in the store, in an account's folder
│   │       ├── FileStore.java           # Where the bytes are, behaving like a filesystem on any storage
│   │       ├── LocalFileStore.java      # The serving directory on disk, with symlinks refused before each operation
│   │       ├── S3FileStore.java         # A bucket in S3 or a service with its API: folder markers, moves as copies
│   │       ├── LooseFileAdoption.java   # Gives an upgraded install's files to the first account
│   │       ├── FileService.java         # Upload, delete, create folder, restore, download, preview
│   │       ├── FileTreeService.java     # The browsable tree
│   │       ├── SearchIndex.java         # Lucene index of names and text, kept in step with the store
│   │       ├── SearchService.java       # Checks searches and their hits against the store
│   │       ├── TextExtractor.java       # Text of text files, PDFs (PDFBox) and .docx
│   │       ├── FileVersionService.java  # Archiving, pruning and looking up versions
│   │       ├── FileHistoryService.java  # Audit log, including failures
│   │       ├── FolderArchive.java       # Streams a folder as a zip
│   │       ├── SetupService.java        # Setup code + first account
│   │       ├── AccountService.java      # Admins: list, disable, roles, quotas
│   │       ├── AccountLinkService.java  # Invitations and password resets: one-time links
│   │       ├── ShareLinkService.java    # Share links: random tokens, stored hashed, bound to their item
│   │       ├── StorageQuota.java        # Account quotas, previous versions included
│   │       ├── DemoService.java         # Live demo: shared account, daily reset, sample files
│   │       ├── Credentials.java, Roles.java, Tokens.java  # Username and password rules, role names, link tokens
│   │       └── AuthService.java         # Current user, setup state
│   └── db/migration/
│       └── V3__timestamps_with_time_zone.java  # Java migration (needs the JVM's zone)
├── src/main/resources/
│   ├── db/migration/               # Flyway SQL migrations (V1 to V7)
│   ├── demo/                       # The live demo's sample files
│   ├── application.properties      # Production defaults
│   ├── application-dev.properties  # Local development (bootRun)
│   └── application-demo.properties # The live demo: shared account, small limits
├── src/test/                       # See testing.md
├── build.gradle                    # Dependencies, Spring Boot plugin, Spotless, -PbundleFrontend
├── compose.yaml                    # PostgreSQL, plus the app itself behind the "app" profile
├── Dockerfile                      # Multi-stage: frontend bundle -> Gradle build -> JRE runtime
├── fly.toml                        # The live demo on Fly.io
├── package.json                    # Root: `concurrently` wrapper used by start.sh
├── start.sh                        # One-command start: DB + backend + frontend
├── docs/                           # Self-hosting, development, testing and API docs
├── LICENSE
└── README.md
```

## Database migrations

The schema is owned by [Flyway](https://documentation.red-gate.com/flyway). Migrations live in `src/main/resources/db/migration` and run automatically on startup, before Hibernate starts. Hibernate runs with `ddl-auto=validate`: it checks that the entity classes match the migrated schema and refuses to start if they don't, but it never changes the database itself.

**Adding a migration.** Any change to an entity's columns, tables or constraints needs a matching migration:

1. Create `src/main/resources/db/migration/V<next number>__<what_it_does>.sql`, e.g. `V4__add_file_tags.sql`. Write plain PostgreSQL. (A migration that needs information SQL can't have goes in `src/main/java/db/migration` as a Java migration instead, like `V3__timestamps_with_time_zone`, which needs the JVM's time zone.)
2. Update the entity to match.
3. Run `./gradlew test`. The Flyway tests run every migration against a real PostgreSQL container and then validate the entities against the result, so a mismatch fails here instead of at startup.

Never edit a migration once it has been merged. Flyway checksums applied migrations and refuses to start if one changes; fix mistakes with a new migration.

**Existing installs.** Databases created before Flyway was introduced were built by Hibernate's `ddl-auto=update` and have no migration history. `spring.flyway.baseline-on-migrate=true` stamps them as version 1 instead of re-running the baseline, and `V1__baseline.sql` reproduces that Hibernate-generated schema exactly, constraint names included, so old and new databases converge on the same schema.

**What's there.** `V1` is the baseline. `V2` adds the constraints the app relies on (one metadata row per path, one account per username, cascading deletes for versions, history that outlives its file), cleaning up any duplicates the pre-V2 code could have created first. `V3` stores timestamps as `timestamptz`. `V4` allows one row per version number of a file, dropping duplicates left by concurrent replaces first. `V5` indexes `file_history.file_id`, so deleting files doesn't scan the whole history. `V6` stores share links on the server (`share_links`). `V7` gives each account its own files: everything stored so far goes to the first account (made an admin), paths become unique per account, `users` gains `enabled`, `quota_bytes` and `session_version`, and `account_links` holds invitations and password resets.

**Tests.** Every test runs on the schema the migrations build. Each Spring context gets an empty database of its own in one Testcontainers PostgreSQL shared by the whole run, which Flyway migrates and Hibernate validates with the production Flyway and `ddl-auto` settings unchanged (`TestDatabaseEnvironment`). The migration tests start containers of their own, to begin from an older schema, through `PostgresTestSupport`.

## Tests

```bash
./gradlew test             # backend; Docker must be running (PostgreSQL in Testcontainers)
cd frontend && npm test    # frontend
```

[testing.md](testing.md) describes what each suite covers.

## Code style

Java is formatted with **google-java-format** via Spotless. `./gradlew build` fails on unformatted code, so run this before committing:

```bash
./gradlew spotlessApply
```

Every Java file under `src/` is checked. The codebase was reformatted in one commit when this was switched on; that commit is listed in `.git-blame-ignore-revs`, which GitHub's blame view honours automatically. To make local `git blame` skip it too:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```
