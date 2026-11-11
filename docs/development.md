# Developing JavaDropbox

Running the app from source, how the code is laid out, and the conventions the build enforces. To run your own instance in Docker, see [self-hosting.md](self-hosting.md).

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **JDK** | 17–24 to run Gradle; the build uses JDK 21 and downloads it if missing | [Download](https://adoptium.net/temurin/releases/?version=21) |
| **Node.js** | 22.22.2+, 24.15+ or 26+ | [Download](https://nodejs.org/) |
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

Everything else is as above, with the app's own state still in `./JDB/.javadropbox`: the search index, uploads while they arrive, and temporary files (the store's, and PDFBox's while it reads a PDF). [self-hosting.md](self-hosting.md#storing-files-in-s3) has every S3 setting.

## Project structure

```
JavaDropbox/
├── .github/workflows/
│   ├── gradle.yml                  # Backend build + tests, Docker image build, dependency graph
│   ├── frontend-ci.yml             # Frontend lint, type check, tests, production build
│   ├── deploy-demo.yml             # Deploys the live demo once CI passes on main
│   ├── codeql.yml                  # CodeQL on Java, JavaScript and the workflows
│   └── coverage-badges.yml         # The README's coverage badges, once CI passes on main
├── .github/scripts/                # coverage.py (job summaries, badge files), publish-badge.sh
├── .github/trivy/Dockerfile        # The Trivy image the image scan uses, for Dependabot
├── .trivyignore                    # Vulnerabilities the image scan accepts, with why
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
│   │   ├── features/               # Redux slices and helpers
│   │   │   ├── authSlice.js        #   Login/logout/session thunks + state, the role
│   │   │   └── files.js            #   The file tree's helpers, and the endpoints linked to
│   │   ├── hooks/                  # useFileSearch (search as you type), useToast, useDemoInfo
│   │   ├── layouts/MainLayout.jsx  # Auth-guarded layout wrapper
│   │   ├── pages/                  # Dashboard, Login, Setup, Admin (accounts), Shared (a share
│   │   │                           #   link's public page), AccountLink (invitation and reset pages)
│   │   ├── services/api.js         # Axios instance: CSRF priming, 401 handler hook
│   │   ├── services/apiSchema.ts   # API types, generated from openapi.json (npm run api:types)
│   │   ├── services/generatedApi.ts # RTK Query endpoints, generated too (npm run api:client)
│   │   ├── services/apiSlice.ts    #   Their cache tags and hooks, and the folder upload the spec can't describe
│   │   ├── services/baseQuery.ts   #   Sends them through api.js, each body as its endpoint takes it
│   │   ├── services/baseApi.ts     #   The empty slice the endpoints are generated into
│   │   ├── services/requestBodies.ts # How each endpoint takes its body, generated (npm run api:client)
│   │   ├── utils/                  # date, errors (readableError), format (formatSize), quota, clipboard
│   │   ├── App.jsx                 # Route definitions
│   │   └── main.jsx                # Entry point; registers the 401 handler
│   ├── vite.config.js              # Dev proxy to Spring Boot backend
│   ├── openapi-config.cjs          # What npm run api:client generates (scripts/request-bodies.mjs too)
│   ├── tsconfig.json               # Strict type check; allowJs while the frontend moves to TypeScript
│   ├── openapi.json                # The API spec, written from the controllers (./gradlew openApiSpec)
│   └── package.json
├── src/main/java/
│   ├── com/javadropbox/javadropbox/
│   │   ├── JavadropboxApplication.java  # Entry point, --directory shorthand
│   │   ├── config/
│   │   │   ├── SecurityConfig.java      # Filter chain, CORS, form login, SPA routes
│   │   │   ├── SetupFilter.java         # First-run redirect filter
│   │   │   ├── SessionConfig.java       # Sessions in PostgreSQL: unreadable ones sign out, not fail
│   │   │   ├── LoginAttemptLimiter.java # Failed sign-in counting per address
│   │   │   ├── LoginThrottleFilter.java # 429 for locked-out addresses
│   │   │   ├── SpaFallbackFilter.java   # App shell for pages the server has no route for
│   │   │   ├── StartupBanner.java       # Logs the bound port and where files are stored
│   │   │   ├── StorageConfig.java       # The file store: the serving directory, or an S3 bucket
│   │   │   ├── SearchConfig.java        # Which search index: Lucene inside the app, or Elasticsearch
│   │   │   ├── UploadSpoolConfig.java   # Uploads arrive in .javadropbox/uploads, not java.io.tmpdir
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
│   │   ├── dto/                    # FileTreeNode, FileVersionDto, FileHistoryDto, HistoryPage, Download, Preview, ShareLinkDto, SharedItemDto, SearchResults, SearchResult, Snippet, AccountDto, InviteDto, AccountLinkInfo, ImportResult
│   │   ├── exception/              # BadRequest (400), Forbidden (403), NotFound (404), Conflict (409)
│   │   ├── model/                  # JPA entities: User, FileMetadata, FileVersion, FileHistory, ShareLink, AccountLink; PreviewType
│   │   ├── repository/             # Spring Data repositories
│   │   └── service/
│   │       ├── StoragePaths.java        # The one place client paths become keys in the store, in an account's folder
│   │       ├── FileStore.java           # Where the bytes are, behaving like a filesystem on any storage
│   │       ├── LocalFileStore.java      # The serving directory on disk, with symlinks refused before each operation
│   │       ├── S3FileStore.java         # A bucket in S3 or a service with its API: folder markers, moves as copies
│   │       ├── FirstAccountImport.java  # Gives an upgraded install's files to the first account, once
│   │       ├── FileService.java         # Upload, delete, create folder, restore, download, preview
│   │       ├── FileTreeService.java     # The browsable tree, from the account's rows
│   │       ├── ImportService.java       # Gives files copied into an account's folder rows of their own
│   │       ├── SearchIndex.java         # What a search index does: changes, reconciles, searches
│   │       ├── BackgroundSearchIndex.java # Keeps any search index in step: a background worker, reconciles
│   │       ├── LuceneSearchIndex.java   # Lucene index of names and text, kept in step with the store
│   │       ├── ElasticsearchSearchIndex.java # The same index on Elasticsearch, for several app servers
│   │       ├── IndexableItems.java      # What any search index may index: the accounts' items, and their text
│   │       ├── SearchService.java       # Checks searches and their hits against the store
│   │       ├── TextExtractor.java       # Text of text files, PDFs (PDFBox) and .docx
│   │       ├── FileVersionService.java  # Archiving, pruning and looking up versions
│   │       ├── FileHistoryService.java  # Audit log, including failures
│   │       ├── FolderArchive.java       # Streams a folder as a zip
│   │       ├── SetupService.java        # Setup code + first account
│   │       ├── AccountService.java      # Admins: list, disable, roles, quotas
│   │       ├── AccountLinkService.java  # Invitations and password resets: one-time links
│   │       ├── AccountSessions.java     # Deletes an account's sessions when it changes, checks sign-ins
│   │       ├── ShareLinkService.java    # Share links: random tokens, stored hashed, bound to their item
│   │       ├── StorageQuota.java        # What each account stores, charged against its quota at commit
│   │       ├── StorageRecount.java      # Sets right at startup any total that drifted from the rows
│   │       ├── BlobStore.java           # Each distinct content kept once, under its SHA-256 in .blobs/
│   │       ├── FileRows.java            # Rows for items already stored, counted as they are added
│   │       ├── DemoService.java         # Live demo: shared account, daily reset, sample files
│   │       ├── Credentials.java, Roles.java, Tokens.java  # Username and password rules, role names, link tokens
│   │       └── AuthService.java         # Current user, setup state
│   └── db/migration/
│       └── V3__timestamps_with_time_zone.java  # Java migration (needs the JVM's zone)
├── src/main/resources/
│   ├── db/migration/               # Flyway SQL migrations (V1 to V9)
│   ├── demo/                       # The live demo's sample files
│   ├── application.properties      # Production defaults
│   ├── application-dev.properties  # Local development (bootRun)
│   └── application-demo.properties # The live demo: shared account, small limits
├── src/test/                       # See testing.md
├── build.gradle                    # Dependencies, Spring Boot plugin, Spotless, JaCoCo, -PbundleFrontend
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

A migration that disables accounts, changes their role or their password also has to delete their sessions (`DELETE FROM spring_session WHERE principal_name = ...`). Sessions are only checked against their account when they sign in, so nothing else would end them.

**Existing installs.** Databases created before Flyway was introduced were built by Hibernate's `ddl-auto=update` and have no migration history. `spring.flyway.baseline-on-migrate=true` stamps them as version 1 instead of re-running the baseline, and `V1__baseline.sql` reproduces that Hibernate-generated schema exactly, constraint names included, so old and new databases converge on the same schema.

**What's there.** `V1` is the baseline. `V2` adds the constraints the app relies on (one metadata row per path, one account per username, cascading deletes for versions, history that outlives its file), cleaning up any duplicates the pre-V2 code could have created first. `V3` stores timestamps as `timestamptz`. `V4` allows one row per version number of a file, dropping duplicates left by concurrent replaces first. `V5` indexes `file_history.file_id`, so deleting files doesn't scan the whole history. `V6` stores share links on the server (`share_links`). `V7` gives each account its own files: everything stored so far goes to the first account (made an admin), paths become unique per account, `users` gains `enabled`, `quota_bytes` and `session_version`, and `account_links` holds invitations and password resets. `V8` drops `file_metadata.last_accessed`, which downloads and previews wrote and nothing read. `V9` adds Spring Session's tables (`spring_session`, `spring_session_attributes`), so sessions survive a restart; `spring.session.jdbc.initialize-schema=never` keeps Spring Session from creating them itself. `V10` adds `one_time_steps`, where the app records work it must do only once, such as giving an upgraded install's files to the first account. `V11` gives a row to every folder that had none, which uploads made on the way before folders got rows of their own, so the file tree, which lists rows, still shows them. `V12` adds `users.used_bytes`, the running total of what each account stores, started from its rows and their versions. `V13` adds `content_hash` to files and versions: the SHA-256 of what an upload stored, computed as it is written, carried to the version when the file is replaced and back when a version is restored, only while the stored content still has its size and hasn't been modified since; null where the app never read the content as it stored it. A hash on content that isn't a blob yet is a hint: content changed outside the app, or by a release from before V13 run against the same database, doesn't match it, so it is checked before anything is keyed by it. `V14` adds `blobs`: one row per distinct content, its size and how many files and versions point at it, which nothing does yet.

**Tests.** Every test runs on the schema the migrations build. Each Spring context gets an empty database of its own in one Testcontainers PostgreSQL shared by the whole run, which Flyway migrates and Hibernate validates with the production settings unchanged (`TestDatabaseEnvironment`): the tests read `application.properties` as it is, and `src/test/resources/config/application.properties` only adds what they change. The migration tests take a database of their own in the same container, to begin from an older schema, through `PostgresTestSupport`.

## Tests

```bash
./gradlew test             # backend; Docker must be running (PostgreSQL, S3Mock and Elasticsearch in Testcontainers)
./gradlew check            # the tests, plus the coverage minimums and the format check
cd frontend && npm test    # frontend; add -- --coverage for the report and its thresholds
```

[testing.md](testing.md) describes what each suite covers, and the coverage minimums of the [backend](testing.md#backend-coverage-minimums) and the [frontend](testing.md#frontend-coverage-thresholds).

## Code style

Java is formatted with **google-java-format** via Spotless. `./gradlew build` fails on unformatted code, so run this before committing:

```bash
./gradlew spotlessApply
```

Every Java file under `src/` is checked. The codebase was reformatted in one commit when this was switched on; that commit is listed in `.git-blame-ignore-revs`, which GitHub's blame view honours automatically. To make local `git blame` skip it too:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

ESLint checks every frontend file, JavaScript and TypeScript, with the same hooks rules. The TypeScript compiler, with `strict` on, checks the TypeScript files. CI runs both:

```bash
cd frontend && npm run lint && npm run typecheck
```

The frontend is moving from JavaScript to TypeScript a part at a time ([#206](https://github.com/mevcaus/JavaDropbox/issues/206)). Until that's done, `allowJs` lets TypeScript files import the JavaScript ones. Those aren't type-checked themselves, but TypeScript infers types from them, and those types count. A component's destructured props without a default become required, for example, so a `.tsx` page that leaves one out fails until that component is converted and says which props are optional. Converting the files that others import first, components before the pages that use them, avoids that. Once a file has been converted, keep it TypeScript.

TypeScript stays on 5.x for now (`~5.9.3`, and Dependabot skips its majors). `openapi-typescript` takes only 5.x, `typescript-eslint` nothing past 6.0, and TypeScript 7, the native compiler, has no JavaScript API for either.

### API types

The frontend's types for requests and responses are generated from the backend, so the two can't drift apart unnoticed. Two files are committed for it:

- `frontend/openapi.json`, the OpenAPI spec springdoc builds from the controllers. It's built with the `demo` profile, so the demo's endpoint is in it. An endpoint switched off for the demo (`@Profile("!demo")`) would drop out of it, and out of the frontend's types.
- `frontend/src/services/apiSchema.ts`, the types `openapi-typescript` generates from that spec. Import them as types, which `verbatimModuleSyntax` requires (`import type { components } from './apiSchema'`), e.g. `components['schemas']['FileTreeNode']`. Never edit the file by hand.

After changing an endpoint, a request or a response, write them all again and commit them:

```bash
./gradlew openApiSpec                      # writes frontend/openapi.json; needs Docker, like the tests
cd frontend && npm run api:types           # writes src/services/apiSchema.ts
npm run api:client                         # writes src/services/generatedApi.ts and requestBodies.ts
```

Do the same when updating springdoc, `openapi-typescript` or `@rtk-query/codegen-openapi`, whose output can change with the version. The codegen's output also depends on the TypeScript compiler it prints with, so check `npm run api:client` when TypeScript changes too. Dependabot bumps `openapi-typescript` and `@rtk-query/codegen-openapi` in pull requests of their own. If the new version writes different output, that pull request fails the frontend CI until `npm run api:types` or `npm run api:client` is run and committed on its branch.

`./gradlew build` fails while `openapi.json` doesn't match the controllers (`OpenApiSpecTests`), and the frontend CI fails while the generated files don't match `openapi.json`.

**Loading data.** Read and change server data through the RTK Query hooks `apiSlice.ts` exports, not by calling `api.js` yourself ([#334](https://github.com/mevcaus/JavaDropbox/issues/334) moves the rest of the screens over). `@rtk-query/codegen-openapi` generates the endpoints into `generatedApi.ts`, named after the operation ids. `apiSlice.ts` adds their cache tags, and requests the spec can't describe, such as uploading a folder one subfolder at a time. A change invalidates the tag of what it changed, which refetches it, failed changes included, since a failure can leave part of the change stored. `baseQuery.ts` sends every request through the axios client in `api.js`, so it keeps its CSRF priming and its sign-out on a 401. It encodes each body as a form or multipart, as `requestBodies.ts`, generated from the spec, says the endpoint takes it. The five GETs that serve a file are left out of the generated endpoints, because the app links to them rather than loading them. Lint refuses an import of `generatedApi` outside `services/`, since its endpoints have no tags; re-export from `apiSlice.ts` any generated type a screen needs. A change that fails with an error from the server refetches what it tagged, but one that fails on a bug in the app (a thrown exception, which RTK logs) refetches nothing. The store (`redux/store.js`, `makeStore()` for tests) drops everything cached when a session ends or starts. The dashboard lists the files again each time it opens, so a change made elsewhere shows up.

**Null in responses.** Jackson writes every field of a response, nulls included, so every field is required in the spec. Whether a field can be null comes from JSpecify's annotations, which `ResponseFieldsConverter` puts in the spec:

- Every response type is `@NullMarked`: the `dto` package is, through its `package-info.java`, and a response record kept elsewhere needs the annotation itself. In a `@NullMarked` type, an unmarked field is never null.
- A field that can be null is marked `@Nullable` on its type: on a record component (`@Nullable Long quotaBytes`) or on a getter's return type. For a nested type the annotation goes before the inner name, as in `FileHistory.@Nullable ChangeType`. It comes out as `number | null` in the types.
- Mark one whenever the code or a nullable column can leave the field empty. Rows from older releases count, too.

Only JSpecify's `@Nullable` counts. The spec can't be built when a response type isn't `@NullMarked`, when a field is marked with another `@Nullable` (Spring's or Jakarta's, which an IDE often imports instead), or when two types would share a schema name. `/v3/api-docs` then fails with a list of what to fix, and so do `OpenApiSpecTests` and `./gradlew openApiSpec`. springdoc would otherwise leave such a response out of the spec with only a warning in the log. The schemas are for responses only: `OpenApiSpecTests` fails if one is used as a request body, since a client need not send every field of a request.

**Operation ids.** Every endpoint sets its own id, `@Operation(operationId = "...")`, and `OpenApiSpecTests` fails for one that doesn't, or for two that share one. A client generated from the spec names its functions after these ids, so renaming a Java method doesn't rename them, and an endpoint mapped to two paths needs a method for each. Name an id with a verb and what it acts on: `list` for a collection, `get` for one thing, `create`, `set`, `revoke`, `describe` for a link's or token's details, and so on, as in `listFileVersions`, `setAccountQuota` or `downloadSharedItem`.

**Typed responses.** Return a record, never a `Map`: a map's keys aren't in the spec, so renaming one wouldn't fail anything, and `OpenApiSpecTests` fails on a map or an untyped `Object` anywhere in a successful response, fields of the records it returns included. `Message` covers the common `{"message": "..."}`. Error bodies from `ApiExceptionHandler` are still maps, and aren't in the spec at all.

**Form and multipart requests.** Spring reads a `@RequestParam` from the query string and from a form alike, so declare on the mapping how the fields come: `consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE` for a form, `MULTIPART_FORM_DATA_VALUE` for files. A request with any other content type then gets a `415`. `FormRequestBodies` turns those fields into the operation's request body in the spec, so a generated client sends them as a body. `consumes` checks only the header, and Spring would still merge a query string into the form, so `SecretsInUrlFilter` refuses a `password` or `code` in the URL of any change, sign-in included, matching names after percent-decoding as Tomcat does. `OpenApiSpecTests` fails if a `POST`, `PUT` or `PATCH` takes any field in its URL, apart from a restore's `mode`. In MockMvc tests, give such a request `.contentType(MediaType.APPLICATION_FORM_URLENCODED)`, or use `multipart(...)`.
