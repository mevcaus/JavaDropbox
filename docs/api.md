# API reference

All endpoints require authentication unless noted otherwise, and act on the signed-in account's own files: paths are relative to its folder, and another account's files are out of reach, by path or by id (a `404`, or a `400` for a path that leads out of the folder). For a live, interactive reference of all REST API endpoints, visit the Swagger UI at `http://localhost:8080/swagger-ui.html` while the backend runs with the `dev` profile (`./gradlew bootRun`; see [development.md](development.md)).

Errors come back as `{"message": "..."}` with a meaningful status: `400` for an invalid path or name, `403` for a wrong setup code or a user calling an admin endpoint, `404` when the item or version does not exist, `409` when something already exists or a concurrent change got in the way, `429` when sign-in or setup is throttled, `507` when an upload or restore would go over the account's quota or the server's cap.

### Authentication

| Method | Endpoint | Auth Required | Description |
|--------|----------|:---:|-------------|
| `POST` | `/setup` | ❌ | Create the first account, an admin (`code`, `username`, `password`); only while none exists |
| `POST` | `/login` | ❌ | Authenticate with `username` + `password` (form-encoded): `{username, role}`. A disabled account is refused like a wrong password (`401`) |
| `POST` | `/logout` | ❌ | Invalidate the session, if there is one (POST only, with the CSRF header) |
| `GET` | `/api/me` | ✅ | The signed-in user: `{username, role}`, where `role` is `ADMIN` or `USER` |
| `GET` | `/invite/{token}/info` | ❌ | What an invitation is for: `{username, expiresAt}`; `404` once used, withdrawn or expired |
| `POST` | `/invite/{token}` | ❌ | Accept an invitation: creates the account with `password` (at least 8 characters); the link is then used up |
| `GET` | `/reset-password/{token}/info` | ❌ | Whose password a reset link sets: `{username, expiresAt}`; `404` once used or expired |
| `POST` | `/reset-password/{token}` | ❌ | Set the account's new `password` and sign it out everywhere; the link is then used up |

A session ends on its next request once its account is disabled, or its role or password changes.
| `GET` | `/api/demo` | ❌ | Only in the `demo` profile: the shared account and the limits, for the sign-in page (`404` elsewhere) |

### Files and Folders

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/files` | Full tree as recursive JSON |
| `POST` | `/api/files` | Upload (`multipart/form-data`: one `files` part per file, each named by a bare file name, plus `path`, the folder they go into, created with any missing parents if it does not exist); replaced files keep their previous content as a version |
| `DELETE` | `/api/files?path=<path>` | Delete a file, or a folder with everything in it |
| `GET` | `/api/files/download?path=<path>` | Download a file, or a folder as a streamed `.zip` |
| `GET` | `/api/files/preview?path=<path>` | Serve a previewable file inline: images and PDFs as themselves, text and source files as `text/plain`; supports range requests. `400` for folders and other kinds of file |
| `POST` | `/api/folders` | Create a folder (`path` + `name`) |
| `GET` | `/api/storage` | What the account stores, previous versions included, and its quota: `{usedBytes, quotaBytes}` (`quotaBytes` is `null` for no limit) |

### Search

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/search?q=<words>&path=<folder>&limit=<n>` | Items below the folder at `path` (the root when left out; not the folder itself) with every word of `q` in their name or their text, best first: `{results, total, indexing}`. Each result is `{name, relativePath, isDirectory, size, lastModified, previewType, snippet}`, where `snippet` is the passage of text that matched, `{text, highlights: [{start, end}]}`, or `null` when only the name did. `limit` is 1 to 200 (default 50) and `total` counts every match. `indexing` is `true` until the index has caught up after a restart, when some files may be missing. `400` for an empty `q` or one over 200 characters, `404` for a folder that does not exist |

### Admin

Admins only; anyone else gets a `403`. Admins see how much each account stores, never its files.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/admin/users` | Every account by username: `[{id, username, role, enabled, quotaBytes, usedBytes}]` |
| `PUT` | `/api/admin/users/{id}/enabled?enabled=<true\|false>` | Disable an account (it can't sign in, its sessions end, its share links stop opening) or enable it again. `409` for your own account or the last admin who can sign in |
| `PUT` | `/api/admin/users/{id}/role?role=<ADMIN\|USER>` | Change an account's role; its sessions end. `409` for your own account or the last admin who can sign in |
| `PUT` | `/api/admin/users/{id}/quota?quota=<size>` | Set an account's quota, previous versions included, as `500MB`, `5GB` or a number of bytes; empty for no limit |
| `POST` | `/api/admin/users/{id}/password-reset` | A one-time link, valid for a day, to set a new password: `{url, expiresAt}`. Only returned here; a new one replaces the previous one |
| `GET` | `/api/admin/invites` | Invitations not yet used or expired, newest first: `[{id, username, role, quotaBytes, createdAt, expiresAt, createdBy}]` |
| `POST` | `/api/admin/invites` | Invite someone (`username`, `role` = `ADMIN` or `USER`, default `USER`, and `quota` as above, default none): `{url, expiresAt}`, valid for 7 days and only returned here. A new invitation for the same username replaces the previous one; `409` if an account has the username |
| `DELETE` | `/api/admin/invites/{id}` | Withdraw an invitation; its link stops working at once |
| `GET` | `/actuator/metrics` | The server's meters (see [self-hosting.md](self-hosting.md#observability)) |

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
| `GET` | `/share/{token}` | ❌ | Opened in a browser (`Accept: text/html`), the link's page, which shows what the link opens before anything is downloaded. Any other client gets the file, or the folder as a ZIP, as from `/download` |
| `GET` | `/share/{token}/info` | ❌ | What the link opens, by name only: `{name, isDirectory, size, lastModified, previewType, expiresAt, contents}`, where a folder's `contents` is `[{name, isDirectory, size, lastModified, children}]` |
| `GET` | `/share/{token}/preview` | ❌ | The file inline, with the same types and headers as `/api/files/preview`; range requests work. A folder, or a file that cannot be previewed, is a `404` |
| `GET` | `/share/{token}/download` | ❌ | Download the shared file, or the folder as a ZIP |

A link that does not open (unknown, expired or revoked, its item deleted, moved or changed between file and folder, or its account disabled) is a bare `404` with no body on every `/share` route, so the public learns nothing about paths. Its page says the link doesn't work.

### History

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/history?page=<n>&size=<n>` | One page of the account's audit log, newest first (`size` ≤ 200, default 50): `{items, page, size, totalItems, totalPages}` |
