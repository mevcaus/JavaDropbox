# API reference

All endpoints require authentication unless noted otherwise. For a live, interactive reference of all REST API endpoints, visit the Swagger UI at `http://localhost:8080/swagger-ui.html` while the backend runs with the `dev` profile (`./gradlew bootRun`; see [development.md](development.md)).

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

### Search

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/search?q=<words>&path=<folder>&limit=<n>` | Items below the folder at `path` (the root when left out; not the folder itself) with every word of `q` in their name or their text, best first: `{results, total, indexing}`. Each result is `{name, relativePath, isDirectory, size, lastModified, previewType, snippet}`, where `snippet` is the passage of text that matched, `{text, highlights: [{start, end}]}`, or `null` when only the name did. `limit` is 1 to 200 (default 50) and `total` counts every match. `indexing` is `true` until the index has caught up after a restart, when some files may be missing. `400` for an empty `q` or one over 200 characters, `404` for a folder that does not exist |

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

A link that does not open (unknown, expired or revoked, or its item deleted, moved or changed between file and folder) is a bare `404` with no body on every `/share` route, so the public learns nothing about paths. Its page says the link doesn't work.

### History

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/history?page=<n>&size=<n>` | One page of the audit log, newest first (`size` ≤ 200, default 50): `{items, page, size, totalItems, totalPages}` |
