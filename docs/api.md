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
