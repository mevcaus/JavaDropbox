# Testing

The backend uses **JUnit 5** with **Spring Boot Test** and **MockMvc** for integration testing. Every test that uses a database runs against **PostgreSQL 15 via Testcontainers**, on the schema the Flyway migrations build, so Docker must be running.

```bash
# Run all backend tests
./gradlew test
```

### Backend Test Coverage

| Test Suite | What It Covers |
|-----------|----------------|
| `AccountIsolationIntegrationTests` | Two accounts: each one's files in its own folder and the same path in both, and nothing of the other's reachable by path, by a way out of the folder or by id: listing, download, preview, sharing (creating, listing and revoking links), versions, restores, deletes, search, history and storage use; a disabled account's share links stop opening and open again once it is enabled |
| `AdminIntegrationTests` | Accounts listed with role, status, quota and use; invitations creating their account once with its role and quota, refused for a username in use, replaced by a new one, withdrawn or expired, and only good for their own purpose; disabling ending sessions and sign-ins until enabled again; admins unable to disable themselves or change their own role, and the last admin who can sign in kept; a new role ending sessions; quotas as sizes or bytes, cleared or refused; password resets used once and ending sessions |
| `UpgradeToAccountsIntegrationTests` | An install from before accounts had folders, at V6 with its files at the top of the serving directory: afterwards they are the first account's, in its folder, with their ids, versions and share links working, the first account is an admin, and the other account has nothing |
| `FileOperationsIntegrationTests` | Delete-then-recreate, folder deletes removing child rows and versions, restore in place and as a copy (twice), versions kept per path, pruning, untracked files versioned before replace, failed uploads keeping the old content, failures recorded despite rollback, changes stored in and attributed to the signed-in user's folder, 404s, history paging |
| `FileIntegrityIntegrationTests` | Concurrent replaces, restores and deletes of one file, the disk put back when a replace or restore fails before, after or at commit, folder deletes in a fixed number of statements |
| `MultipartErrorIntegrationTests` | On a real Tomcat: a server fault storing an upload is a 500, a malformed upload a 400 |
| `StoragePathSecurityTests` | The root under every spelling, `..` traversal out of the account's folder (into another's, or the version store), symlink escapes and loops, in-root symlinks aliasing the root or a reserved folder, the reserved `.versions`/`.javadropbox` directories in any letter case, dot-named uploads and folders, single-segment upload names |
| `StoragePathsTests`, `SymlinkSwapTests`, `StorageFilesTests`, `DownloadResponsesTests`, `LooseFileAdoptionTests` | Path resolution inside an account's folder on disk and on an in-memory case-insensitive filesystem (Jimfs), no way out of it into another account's folder or the app's, by `..`, an absolute path or a symlink, files at the top of the serving directory moved into the first account's folder (hidden ones and name clashes left alone), symlinks swapped in after the check for zip, delete, upload and download, upload scratch-file permissions |
| `PreviewIntegrationTests`, `PreviewTypeTests` | Inline serving and the `Content-Type` per kind, markup served as plain text, the sandbox on images and text, PDFs frameable by the app only while downloads stay unframeable, range requests, `400` for folders and other files, traversal, `401` when signed out, `previewType` in the tree, the extension rules |
| `DownloadIntegrationTests` | Folder zips (without symlinks), shared folder downloads, `Content-Disposition` for awkward names, range requests, links to deleted items, `Content-Security-Policy: sandbox` on every download |
| `FolderArchiveTests` | Zips leave out hidden files, FIFOs and entries that vanish while zipping, and keep empty folders |
| `ClientAbortIntegrationTests` / `IoExceptionHandlingTests` | Cancelled file and zip downloads are logged at debug only (on real Tomcat); nothing is written into a response already under way |
| `DownloadConnectionIntegrationTests` | A paused download holds no database connection, with a one-connection pool (on real Tomcat) |
| `SecurityIntegrationTests` | 401 for unauthenticated users and for a session whose account is gone, the file API open to any signed-in account whatever its role while the admin API is not, storage use without server paths, logout, JSON errors, the SPA shell served for client-side routes and for any other page a browser opens, but not for API calls, share links, missing files or non-HTML requests |
| `AuthIntegrationTests` | CSRF cookie round trip, any account can sign in, sign-in throttling |
| `LoginThrottleIntegrationTests` | Percent-encoded login URLs are throttled, a parallel burst gets no more than five password checks (on real Tomcat) |
| `TrustedProxyIntegrationTests` / `UntrustedForwardedHeadersIntegrationTests` | `X-Forwarded-*` headers only count from a trusted proxy: the throttled address, share-link URLs, the `Secure` session cookie (on real Tomcat) |
| `MultipartCsrfIntegrationTests` | Uploads that fail the CSRF check, or come from an anonymous client, write nothing to disk |
| `SetupIntegrationTests` | First-run redirects, the setup code and its throttling, validation, the app shell during setup, 409 after setup |
| `PasswordRecoveryIntegrationTests` | The [password recovery procedure](self-hosting.md#forgot-your-password), with uploaded files and the real foreign keys: an `htpasswd` hash set by `UPDATE` signs in |
| `StorageQuotaIntegrationTests` | Account quotas: uploads and restores that would go over one are a `507` naming it and leave nothing behind, previous versions count toward it, and neither another account's files nor the search index do |
| `SearchIntegrationTests` | Finding files by the words in their text, PDFs and Word documents included, and by part of their name whatever the case and accents; the passage shown with the words marked, and cut around the match in a long text; every word needed, the last one also as the start of a word; matches in the name first and words together ahead of apart; only the given folder searched, not including itself; the index following uploads over a file, restores, deletes and the folders an upload creates; previous versions, hidden files, the app's own folders and symlinks left out; files changed outside the app picked up by a reconcile; items gone from disk left out and dropped; the limit and total; `400` for empty or overlong searches and paths outside the serving directory, `404` for a missing folder, `401` when signed out |
| `SearchIndexTests`, `TextExtractorTests` | A search finding the account's own files only, never another account's or files beside the accounts' folders; a reconcile reading again only files whose size or modification time changed; the last index searched while catching up, and said to be incomplete; unreadable indexes and those another version wrote rebuilt; wildcards matching only themselves; waiting for another writer's lock rather than losing changes; text files read as UTF-8 and cut at 200,000 characters, every page of a PDF, a Word document's paragraphs without its deleted text, entities in a document not resolved, size limits, and damaged files refused |
| `DemoIntegrationTests`, `DemoServiceTests` | The `demo` profile: the account signs in with no setup, `/api/demo` is public, the sample files (one with two versions) are stored, a due reset deletes everything and stores them again (and search forgets what was deleted and finds the samples, the PDF's text included) while one that is not due changes nothing, share links of at most 15 minutes, the account's 50 MB quota (put back at start if changed); when the daily reset falls due |
| `ShareLinkIntegrationTests` | Link creation, expiry, listing and revoking, public download; a browser opening a link getting its page while other clients get the file; the page's description of a file or folder by name with no paths, owners or ids; inline previews with the private preview's headers and none for folders or other files; a bare 404 on every route of a link that does not open; tokens that reveal no path and are stored only hashed; links dying with their item even when the path is reused or the item changes type |
| `RetiredShareKeyTests` / `FlywayShareLinksMigrationTests` | No signing key is created, a leftover (even empty) key file is deleted, a configured secret stops startup; links are deleted with their file and token hashes are unique |
| `ObservabilityIntegrationTests` | Health answering before setup and to anyone, its details and the metrics for admins only (a `403` for other accounts, a `401` signed out, also for a browser), nothing else exposed, uploads counted with their size, files served counted by route, share links counted when created, refusals not counted |
| `CorsIntegrationTests` | Configured origins allowed, others refused |
| `SwaggerIntegrationTests` | Docs reachable with the setup filter active, spec lists every tag and endpoint |
| `FlywayMigrationIntegrationTests` / `FlywayBaselineIntegrationTests` | Migrations build an empty PostgreSQL database, and a pre-Flyway database is adopted; Hibernate validates both |
| `FlywayFileVersionMigrationTests` | V4 drops duplicate version rows before making versions unique; V5 lets deletes find history rows through an index |
| `FlywayAccountsMigrationTests` | V7 gives every file and history entry to the first account and makes it an admin, makes paths unique per account, checks roles and quotas, allows one open invitation per username and one reset per account, and drops rows when there is no account at all |
| `FlywayIntegrityMigrationTests` / `FlywayTimestampMigrationTests` | V2 cleans up duplicate rows before adding constraints; V3 keeps each timestamp's instant under UTC, region and offset-style (`GMT+01:00`) JVM zones |
| `SetupServiceTests`, `LoginAttemptLimiterTests`, `JavadropboxApplicationArgumentsTests` | Setup codes throttled per client, lockout timing and bounds, command-line shorthands |
| `FileVersionServiceTests`, `ApiExceptionHandlerTests` | A negative retention limit stops startup; statuses for lost races and for upload parsing failures |

### Test Design Highlights
- **The production schema everywhere**: One PostgreSQL 15 container serves the whole run, and each Spring context gets an empty database of its own in it, which Flyway migrates and Hibernate validates with the production settings (`TestDatabaseEnvironment`, registered in `src/test/resources/META-INF/spring.factories`). Every test sees the real constraints, partial unique indexes and cascades, and the migrations run on every build
- **Test isolation**: Contexts never share a database, so nothing one leaves behind reaches another, even from work done outside a test (startup, the search index's background thread). Test classes with the same configuration share one context and its database, so each wipes every table after a test through `TestDatabase.wipe`
- **Containers of their own**: The migration and upgrade tests start their own PostgreSQL, to begin from an older schema or none, and point their context at it through `PostgresTestSupport`

### Frontend Tests

Component tests run under **Vitest** in a **jsdom** environment using **Testing Library**. They render the real component and drive it with real user events (typing, clicking, keyboard) rather than mocking its internals, and assert against the rendered DOM.

```bash
cd frontend
npm test
```

| Test Suite | What It Covers |
|-----------|----------------|
| `Dashboard.test.jsx`, `App.test.jsx` | Invitation pages and the accounts page routed (admins only), downloads through a link rather than into memory, previewing a file in the open folder by its full path, re-uploading the same file, uploading a folder (its subfolders kept below the open folder, dot-named files and folders left out and each named once, failures reported), keeping the table during refreshes, the open folder in the URL (reload and Back), dot-named uploads, unknown URLs redirecting, share pages shown without a session |
| `Modal.test.jsx` | Escape, focus trap (including focus outside the panel or on a removed control) and focus restore with a fallback |
| `CreateFolderModal.test.jsx` | Closing only once the folder exists, the pending state, inline server errors, the dot-name rule |
| `ShareModal.test.jsx` | Expiry selection, errors, double-submit guard, clipboard fallback over plain http, ignoring a slow answer for the previous item, listing and revoking active links |
| `PreviewModal.test.jsx` | Images and PDFs from the preview endpoint (through `FilePreview`, which share pages use too), text fetched by range and shown unrendered, the cut-short notice ending on a whole line, empty files, server errors, starting over for the next file |
| `Shared.test.jsx` | A share link's page: a file described and previewed (text, image, PDF) with nothing downloaded until the Download link, a note for files with no preview, a folder's contents browsed in place, dead links explained, other errors reported |
| `VersionHistoryModal.test.jsx` | Listing versions, restoring in either mode, a restore for one file not affecting the next file's dialog |
| `Login.test.jsx`, `Setup.test.jsx`, `Navbar.test.jsx` | Offering setup only while no account exists, errors announced as alerts, setup code and password checks, a failed logout keeping the user signed in, the notice a page sends to sign-in with |
| `Admin.test.jsx` | Non-admins sent to their files; accounts listed with role, status and storage against their quota; no way to change your own role or disable yourself; disabling, enabling and role changes, with the server's refusals shown; inviting with a role and a quota in bytes and the link shown once; quotas that are not positive numbers refused before asking; changing and clearing a quota; password reset links; listing and withdrawing invitations |
| `AccountLink.test.jsx` | An invitation's and a reset link's pages: whose account, the password checked and sent, on to sign-in with a notice, used or expired links explained, the server's refusals shown |
| `authSlice.test.js`, `filesSlice.test.js`, `api.test.js`, `errors.test.js`, `quota.test.js` | Session handling (logout is a POST, no password in the console, the role from sign-in or the session check and never kept in localStorage), quotas shown in their largest whole unit and sent in bytes, only the newest file listing applied, refreshing after failed mutations, folder uploads sent a folder at a time, parents first, with bare file names, stopping at the first failure, CSRF priming, 401 handling, readable error messages |
| `ToastContext.test.jsx` | Errors announced assertively, toasts held while hovered or focused |
| `FileTable.test.jsx`, `Breadcrumbs.test.jsx`, `Sidebar.test.jsx` | The storage meter against the quota, asked again when the files change, and the Users link for admins only; default folders-before-files ordering; search asking the server about the open folder once typing pauses, results best first with path labels, the matching passage with its words marked and markup in it shown as text, results shown as the tree has them (so tracked files offer versions) or as the server sent them, counts, the best-of note, the still-indexing note, failures, answers to an older search dropped, searching again when the files change, sorting results by a column and back to relevance; sorting by name/size/last-modified with direction toggling, `aria-sort` annotation and keyboard activation of headers, search clearing on folder navigation, actions reachable on touch screens and named after their file, previews offered only for files the server marks previewable, visible keyboard focus, the breadcrumb landmark |

### End-to-End Tests

The backend and frontend suites each mock the other side, so a mismatch between them (an endpoint path, a CSRF header, a response shape) passes both. The **Playwright** tests in `e2e/` close that gap: they drive the real app in Chromium against the Docker Compose stack, the same image, PostgreSQL database and storage volume a user runs.

```bash
# Start a fresh stack: setup only happens once, on an empty database
docker compose --profile app down --volumes
docker compose --profile app up --build --wait

# Once: the test dependencies and the browser
npm install --prefix e2e
npx --prefix e2e playwright install chromium

# Run the suite, then open the report (with a trace of any failure)
npm run e2e
npm run report --prefix e2e
```

The `setup` project runs first. On a fresh stack it reads the setup code from `docker compose logs app`, as the README tells a user to, creates the account and signs in; on a stack already set up it only signs in. Every other test starts from that signed-in session, in a folder of its own created through the API, so the tests run in parallel and can run again on the same stack. Arguments after `--` go to Playwright, such as `npm run e2e -- --headed` or `npm run e2e -- tests/share.spec.js`.

| Variable | Default | Use |
|----------|---------|-----|
| `E2E_BASE_URL` | `http://localhost:8080` | Where the app is running |
| `E2E_USERNAME`, `E2E_PASSWORD` | A fixed test account | The account setup creates, or the one to sign in with on a stack set up by hand |
| `E2E_SETUP_CODE` | Read from the container log | The setup code for a server Docker Compose did not start, such as `./gradlew bootRun` with `app.setup.code` set |

Docker Compose's own `COMPOSE_PROJECT_NAME` and `COMPOSE_FILE` are honoured when reading the log, for a stack started under another name or with an override file.

CI runs the suite in the `docker` job of `.github/workflows/gradle.yml` after the stack is up, and uploads the HTML report as the `playwright-report` artifact when a test fails.

| Test Suite | What It Covers |
|-----------|----------------|
| `account.setup.js` | First-run setup with the code from the server log, signing in as the admin, the session the other tests share |
| `auth.spec.js` | Signed-out visitors sent to sign-in with no setup link, a wrong password refused, signing in, a session that survives a reload, signing out ending the session on the server |
| `files.spec.js` | Creating a folder and opening it (kept across a reload), uploading a file and downloading the same bytes, uploading a folder with its subfolders (a `.DS_Store` left out), downloading a folder as a zip, deleting a file and a folder for good |
| `preview.spec.js` | An image decoded in the preview, a PDF served inline and frameable, a text file shown as text with its markup unrendered, downloading from the preview |
| `share.spec.js` | A link opened in a signed-out browser showing a page that previews the file and downloads it from there, a folder's page listing its contents and downloading a zip, a script fetching the link getting the file until it is revoked (a 404 straight after, and a page saying so), links listed again when the dialog is reopened |
| `versions.spec.js` | Uploading over a file, restoring the earlier version in place (the replaced content kept as a version) and as a copy |
| `accounts.spec.js` | An admin inviting someone with a quota, who opens the link in a browser of their own, chooses a password, signs in to an empty file list with their quota in the sidebar and no Users page, finds the link used up, and is signed out once the admin disables them |
| `search.spec.js` | Finding a text file and a PDF by a word inside them, with the word marked in each result, and previewing one from the results; a file deleted from the results dropping out of them |
