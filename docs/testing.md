# Testing

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
| `PasswordRecoveryIntegrationTests` | The [password recovery procedure](self-hosting.md#forgot-your-password), on PostgreSQL with uploaded files: an `htpasswd` hash set by `UPDATE` signs in |
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
