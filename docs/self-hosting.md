# Self-hosting JavaDropbox

How to run, configure and operate your own instance. For working on the code, see [development.md](development.md).

## Run it

The Docker image bundles the frontend into the backend, so one container serves the whole app, next to PostgreSQL:

```bash
git clone https://github.com/mevcaus/JavaDropbox.git
cd JavaDropbox
docker compose --profile app up --build
```

The logos and favicon are stored in Git LFS (run `git lfs install` once before cloning). Cloned without it, they are small text pointers instead of images: run `git lfs pull` to fetch them. The Docker build stops with a message saying so rather than ship broken images.

Open `http://localhost:8080` and complete setup with the code from `docker compose logs app`. Files and their versions live in the `javadropbox-data` volume, and the database in `postgres-data`. Set `POSTGRES_PASSWORD` for anything beyond local use, before the first start: Postgres only reads it when it creates the `postgres-data` volume. To change it later, change it in the database as well, with `docker compose exec postgres psql -U postgres -c "ALTER USER postgres PASSWORD 'new-password'"`, then start again with the new `POSTGRES_PASSWORD`. The app service sits behind the `app` profile so that `./gradlew bootRun`, which starts `compose.yaml` for its database, doesn't also start a second copy of the app.

The container runs as uid and gid `10001`. To keep the files in a host directory instead of the volume, mount it at `/data` and hand it to that user first, e.g. `sudo chown -R 10001:10001 /srv/javadropbox`. A volume created by an image from before the uid was fixed belongs to a different uid; hand it over once with `docker compose run --rm --no-deps --user root --entrypoint chown app -R 10001:10001 /data`.

## First-run setup

While no account exists, every page redirects to the setup page. Creating the first account needs the **setup code** the server prints to its log on startup (a banner reading *"No account exists yet…"* with a code like `K7QMT-9XH2C`), so only whoever can read the server's log can claim the server. Choose a username and a password of at least 8 characters, then sign in. Five wrong codes from one address lock that address out for 15 minutes.

The first account is an **admin**, and anything already in the serving directory becomes its files.

## Accounts

Every account has files of its own, which nobody else can see: not other users, and not admins either. Each has its own file tree, versions, history, share links and search. Admins manage the accounts from **Users** in the sidebar:

- **Invite someone.** Choose a username, a role and a quota, and the app makes a one-time link to send them however you like (it is shown once). Whoever opens it chooses the password, and the account exists from then on. A link works for 7 days; making a new one for the same username replaces it, and an unused one can be withdrawn.
- **Roles.** An *admin* manages the accounts and sees the server's metrics, as well as having files. A *user* only has files. Changing someone's role signs them out, so they sign back in with the new one.
- **Quotas.** The most an account may store, previous versions included, such as `5 GB`; empty means no limit. An upload or restore that would take the account over its quota is refused with `507` and leaves nothing behind. A quota below what the account already stores is allowed: nothing more fits until enough is deleted.
- **Disable and enable.** A disabled account can't sign in, is signed out at once wherever it is signed in, and its share links stop opening. Enabling it brings all of that back; its files are untouched either way. Invitations and password reset links a disabled admin made are withdrawn for good, though: whoever has the account shouldn't be able to use them to get back in. Accounts are never deleted.
- **Reset a password.** A one-time link, valid for a day, at which the account's owner chooses a new password; using it signs them out everywhere. Their old password keeps working until then.

An admin can't disable their own account or change their own role, and the app always keeps at least one admin who can sign in, so nobody can lock everyone out.

**On disk**, each account's files are in `.users/<id>/` inside the serving directory, where `<id>` is the account's number (`SELECT id, username FROM users` lists them). Files copied in there by hand show up for that account, and search finds them within ten minutes. Previous versions stay in the shared `.versions/`, named by file. [In S3](#storing-files-in-s3) the layout is the same, in the bucket.

**Upgrading** from a version without accounts of their own: at the first start, the database migration gives every file, version, share link and history entry to the first account (the one setup created), makes that account an admin, and the app moves everything at the top of the serving directory into its folder. Paths, versions and share links keep working. Other accounts start with nothing. Anything put at the top of the serving directory later is moved into the first account's folder at the next start, unless that folder already has something by the same name.

**`javadropbox.storage.max-total-size` is gone.** It capped everything the server stored, every account's files together; give each account a quota in the app instead (see above). A server still configured with it refuses to start and says so, rather than running without the cap you set: remove the setting (or `JAVADROPBOX_STORAGE_MAX_TOTAL_SIZE`) once the accounts have quotas.

## Storing files in S3

Files are kept in the serving directory on the server's disk unless told otherwise. They can be kept in a bucket instead: in Amazon S3, or in any service with the same API, such as MinIO, Cloudflare R2, Backblaze B2, Wasabi or Garage. Everything works the same either way, versions, share links, search, quotas, previews and zipped folders included.

```properties
javadropbox.storage.type=s3
javadropbox.storage.s3.bucket=my-javadropbox
# For a service other than AWS:
javadropbox.storage.s3.endpoint=https://minio.example.com
javadropbox.storage.s3.path-style-access=true
javadropbox.storage.s3.access-key=...
javadropbox.storage.s3.secret-key=...
```

With Docker Compose, the same settings go in the app's `environment`:

```yaml
      JAVADROPBOX_STORAGE_TYPE: s3
      JAVADROPBOX_STORAGE_S3_BUCKET: my-javadropbox
      JAVADROPBOX_STORAGE_S3_REGION: eu-central-1
      AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID}
      AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY}
```

| Property | Default | Purpose |
|----------|---------|---------|
| `javadropbox.storage.s3.bucket` | none | The bucket; required, and it must exist |
| `javadropbox.storage.s3.prefix` | none | Where in the bucket the files go, such as `javadropbox/`, to share a bucket with something else. Without one, a bucket holding anything that is not the app's is refused |
| `javadropbox.storage.s3.endpoint` | AWS | The service's URL, for anything but AWS itself |
| `javadropbox.storage.s3.region` | `AWS_REGION`, the AWS config, or the instance's; `us-east-1` with an endpoint | The region to sign requests for; R2 takes `auto` |
| `javadropbox.storage.s3.path-style-access` | `false` | Address the bucket as `https://endpoint/bucket/` rather than `https://bucket.endpoint/`; MinIO and most self-hosted services need `true` |
| `javadropbox.storage.s3.access-key` / `.secret-key` | where the AWS SDK looks | Credentials. Without them: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, `~/.aws/credentials`, or the role of the EC2 instance, ECS task or EKS pod |

The credentials need `s3:ListBucket` on the bucket, and `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` and `s3:AbortMultipartUpload` on its objects (below the prefix, with one). The bucket can, and should, stay private: browsers never talk to it, since every download and preview goes through the app, with the same headers as from the disk.

At startup the app checks that it can reach the bucket, and stops with a message saying why if it cannot: no such bucket, credentials refused, or no answer. It also stops if the bucket, or the folder the prefix names, already holds objects that are not its own, since what is at the top would be moved into the first account's folder as it is on disk. A store the app has used, or a serving directory copied in, is recognised by its `.users/`, `.versions/` or welcome file. An admin sees it as the `storage` component of `/actuator/health` from then on, which is `DOWN` while the bucket cannot be reached.

**In the bucket**, files are laid out as in the serving directory: `.users/<id>/` for each account, `.versions/<file id>/v<n>` for previous versions. Folders follow the convention S3's own console uses: a folder is there while anything is stored below it, and an empty one is an empty object whose name ends in `/`. Objects put there with another tool, such as `aws s3 cp`, show up for that account, and search finds them within ten minutes. An empty bucket is given the welcome file, which setup gives to the first account, as a new serving directory is.

**The serving directory is still used**, for the app's own state: the search index, in `.javadropbox/search-index`. It holds nothing that cannot be rebuilt from the bucket, so it can be on a disk that does not last, but every start on an empty one reads every file to build the index again.

**Moving an existing install to S3**: stop the app, copy the serving directory into the bucket, leaving out `.javadropbox`, and start it with the settings above. The database stays as it is.

```bash
aws s3 sync /path/to/serving-directory s3://my-javadropbox/ --exclude '.javadropbox/*'
```

Add `--endpoint-url https://...` for a service other than AWS, and the prefix to the bucket's URL if there is one. `sync` copies files, not folders, so empty folders are not carried over; everything else is, previous versions included.

**Good to know:**

- Uploads go on to S3 as they arrive, 64 MB at a time: each part waits in a temporary file until S3 has it, so a part that fails is sent again rather than the whole upload. That takes up to 64 MB of temporary space for each upload under way. Downloads ask S3 for only the range a client asked for, so resuming a download, or a preview reading the first part of a large text file, does not fetch the rest.
- S3 cannot rename, so replacing a file copies it within S3 and deletes the original. If the server stops between the two, the startup clean-up removes what is left, as it does on disk. A lifecycle rule that aborts incomplete multipart uploads after a day clears up the parts of an upload cut short the same way.
- With versioning turned on for the bucket, S3 keeps every object the app replaces or deletes, and they cost as much as any other; the app keeps its own versions anyway. Turn it off, or give noncurrent versions a lifecycle rule.
- Each change takes a handful of requests to S3, and the file tree, the quota and search each read the account's folder with a listing of a thousand objects a request, which suits the sizes the app is meant for.

## Forgot your password?

An admin can make you a reset link (see [Accounts](#accounts)). If you are the only admin, store a new bcrypt hash on your account. `htpasswd` makes one (run it from the `httpd` image as here, or use a local `htpasswd`, which can prompt for the password if you leave out `-b` and the password); replace `admin` with your username:

```bash
HASH=$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" 'my-new-password' | tr -d ':\n')
docker compose exec postgres psql -U postgres -d javadropbox \
  -c "UPDATE users SET password = '$HASH' WHERE username = 'admin'"
```

`UPDATE 1` means it worked; the new password applies from the next sign-in, without a restart, and your files are untouched. `SELECT username FROM users` lists the accounts if you've forgotten the name too. A disabled admin can be enabled again the same way, with `UPDATE users SET enabled = true WHERE username = 'admin'`.

## Behind a reverse proxy

Behind a reverse proxy that terminates TLS, forward `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`, so the sign-in throttle sees each client's real address and share links carry your public `https://` address. The app only believes these headers from the proxies in `server.tomcat.remoteip.internal-proxies`, a regular expression matched against the connecting address; from anyone else they are ignored, so a client cannot choose its own address. It trusts loopback only by default, which suits a proxy on the same host. For a proxy anywhere else, such as another container, set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to its address, e.g. `172\.18\.0\.2`. Don't widen it to a whole network that untrusted machines can connect from.

## Configuration

Every property can also be set as an environment variable (`javadropbox.serving.directory` → `JAVADROPBOX_SERVING_DIRECTORY`).

| Property | Default | Purpose |
|----------|---------|---------|
| `spring.datasource.url` / `.username` / `.password` | none (the dev profile uses `compose.yaml`'s Postgres) | Database connection; required in production |
| `javadropbox.serving.directory` | `./JDB` | Where files are stored, each account's in `.users/<id>/`, and the app's own state such as the search index (only that, with the files in S3); also `--directory=/path` or a bare path as the first argument |
| `javadropbox.storage.type` | `local` | `local` for the serving directory, or `s3` for a bucket (see [Storing files in S3](#storing-files-in-s3)) |
| `javadropbox.versions.max-retained` | `10` | Previous versions kept per file (0 or more; a negative value stops startup) |
| `javadropbox.share.max-expiration` | `7d` | Longest lifetime a share link can be given |
| `javadropbox.search.max-file-size` | `50MB` | Files larger than this are found by name only, without their text being read; `0` searches names alone |
| `javadropbox.search.index-directory` | `.javadropbox/search-index` in the serving directory | Where the search index is kept |
| `spring.servlet.multipart.max-file-size` | `1024MB` | Largest file a single upload can carry |
| `app.setup.code` | generated per start | Fixed setup code for scripted installs: at least 10 characters (not counting dashes), and not printed to the log |
| `app.cors.allowed-origins` | none | Origins allowed to call the API cross-origin, comma-separated |
| `server.tomcat.remoteip.internal-proxies` | loopback only | Regex of reverse-proxy addresses whose `X-Forwarded-*` headers are trusted |
| `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` | `false` (`true` in dev) | Publish the OpenAPI spec and Swagger UI |

`app.share.jwt-secret` (`APP_SHARE_JWT_SECRET`) is gone: share links are stored on the server and no longer signed. The app refuses to start while it is set, so remove it when upgrading. A `.javadropbox/share-jwt.key` left by an earlier version is deleted on startup.

## Search

The search box looks through the name of every file and folder you have, and the text of text and source files, PDFs and Word documents (`.docx`), keeping the first 200,000 characters of each (about 80 pages). The index lives in `.javadropbox/search-index` in the serving directory, so it is in the same volume and backups as the files, and stays on the server's disk when the files are in S3. It does not count toward any account's quota.

The files stored are the source of truth, and the index only mirrors them:

- Changes made through the app are searchable a moment after they are saved: a background thread reads the new text, so uploads don't wait for it.
- Files added, changed or removed outside the app, such as copied into the serving directory by hand or into the bucket with another tool, are picked up at startup and then every ten minutes, when each file's size and modification time are compared with the index.
- The first start after upgrading reads every file once to build the index, logging `Building the search index` and then how many items it indexed. Until that finishes, search results say some files may be missing.
- To rebuild the index from scratch, stop the server, delete the `search-index` folder and start it again. When an upgrade changes what is indexed, the old index is rebuilt the same way on its own.

A PDF or Word document that cannot be read, such as a damaged or password-protected one, is logged at `WARN` and is still found by its name.

## Observability

[Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/) exposes two endpoints and nothing else (no `env`, `beans`, `heapdump` and the like, which describe the server rather than its health):

| Endpoint | Access | What it reports |
|----------|--------|-----------------|
| `GET /actuator/health` | Public, even before setup | `UP`, or `DOWN` with `503` when the database is unreachable, the disk the files are stored on is nearly full, or the bucket they are stored in cannot be reached. To an admin, it also shows each check (`db`, `diskSpace` for the serving directory, `storage` for the bucket, `ping`) |
| `GET /actuator/metrics` | Admins | The names of all meters; `/actuator/metrics/<name>` gives one meter's values, filtered with `?tag=key:value` |

Health is public because load balancers and the Docker `HEALTHCHECK` call it without a session, and an anonymous caller learns only `UP` or `DOWN`. The details and the metrics describe the whole server, so they are for admins: anyone else gets `UP` or `DOWN`, and a `403` for metrics.

Beside the JVM, HTTP, Tomcat and connection-pool meters Spring Boot records, the app records what it is used for:

| Meter | Type | Counts |
|-------|------|--------|
| `javadropbox.files.served` | Counter, tagged `route` = `download`, `preview` or `share-link` | Files and zipped folders served. Requests for something missing aren't counted; each range request is, so a PDF viewer reading a file in parts counts more than once |
| `javadropbox.uploads.size` | Distribution summary, in bytes | One sample per stored file once its upload has committed: `COUNT` is files uploaded, `TOTAL` the bytes, `MAX` the largest |
| `javadropbox.share.links.created` | Counter | Share links created |

```bash
# An admin's session cookie from the browser's dev tools
curl -b JSESSIONID=... 'http://localhost:8080/actuator/metrics/javadropbox.files.served?tag=route:share-link'
```

There is no Prometheus endpoint yet: a scraper has no way to sign in through the form login, and opening metrics to anonymous callers would publish usage figures. Scraping needs either a separate management port that only the monitoring network can reach, or a scrape credential that is throttled like sign-in.
