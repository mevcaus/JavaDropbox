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

## Forgot your password?

Store a new bcrypt hash on your account. `htpasswd` makes one (run it from the `httpd` image as here, or use a local `htpasswd`, which can prompt for the password if you leave out `-b` and the password); replace `admin` with your username:

```bash
HASH=$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" 'my-new-password' | tr -d ':\n')
docker compose exec postgres psql -U postgres -d javadropbox \
  -c "UPDATE users SET password = '$HASH' WHERE username = 'admin'"
```

`UPDATE 1` means it worked; the new password applies from the next sign-in, without a restart, and your files are untouched. `SELECT username FROM users` lists the accounts if you've forgotten the name too.

## Behind a reverse proxy

Behind a reverse proxy that terminates TLS, forward `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`, so the sign-in throttle sees each client's real address and share links carry your public `https://` address. The app only believes these headers from the proxies in `server.tomcat.remoteip.internal-proxies`, a regular expression matched against the connecting address; from anyone else they are ignored, so a client cannot choose its own address. It trusts loopback only by default, which suits a proxy on the same host. For a proxy anywhere else, such as another container, set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to its address, e.g. `172\.18\.0\.2`. Don't widen it to a whole network that untrusted machines can connect from.

## Configuration

Every property can also be set as an environment variable (`javadropbox.serving.directory` → `JAVADROPBOX_SERVING_DIRECTORY`).

| Property | Default | Purpose |
|----------|---------|---------|
| `spring.datasource.url` / `.username` / `.password` | none (the dev profile uses `compose.yaml`'s Postgres) | Database connection; required in production |
| `javadropbox.serving.directory` | `./JDB` | Where files are stored; also `--directory=/path` or a bare path as the first argument |
| `javadropbox.versions.max-retained` | `10` | Previous versions kept per file (0 or more; a negative value stops startup) |
| `javadropbox.storage.max-total-size` | none | Cap on everything stored, previous versions included (e.g. `50MB`); an upload or restore that would go over it is refused with `507` |
| `javadropbox.share.max-expiration` | `7d` | Longest lifetime a share link can be given |
| `spring.servlet.multipart.max-file-size` | `1024MB` | Largest file a single upload can carry |
| `app.setup.code` | generated per start | Fixed setup code for scripted installs: at least 10 characters (not counting dashes), and not printed to the log |
| `app.cors.allowed-origins` | none | Origins allowed to call the API cross-origin, comma-separated |
| `server.tomcat.remoteip.internal-proxies` | loopback only | Regex of reverse-proxy addresses whose `X-Forwarded-*` headers are trusted |
| `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` | `false` (`true` in dev) | Publish the OpenAPI spec and Swagger UI |

`app.share.jwt-secret` (`APP_SHARE_JWT_SECRET`) is gone: share links are stored on the server and no longer signed. The app refuses to start while it is set, so remove it when upgrading. A `.javadropbox/share-jwt.key` left by an earlier version is deleted on startup.

## Observability

[Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/) exposes two endpoints and nothing else (no `env`, `beans`, `heapdump` and the like, which describe the server rather than its health):

| Endpoint | Access | What it reports |
|----------|--------|-----------------|
| `GET /actuator/health` | Public, even before setup | `UP`, or `DOWN` with `503` when the database is unreachable or the disk the files are stored on is nearly full. Signed in, it also shows each check (`db`, `diskSpace` for the serving directory, `ping`) |
| `GET /actuator/metrics` | Signed in | The names of all meters; `/actuator/metrics/<name>` gives one meter's values, filtered with `?tag=key:value` |

Health is public because load balancers and the Docker `HEALTHCHECK` call it without a session, and an anonymous caller learns only `UP` or `DOWN`. Metrics need a session. Every account is the owner today, and installs from before the setup code have a `ROLE_USER` owner, so this is a sign-in rule rather than an `ADMIN` one until there are accounts that aren't the owner's.

Beside the JVM, HTTP, Tomcat and connection-pool meters Spring Boot records, the app records what it is used for:

| Meter | Type | Counts |
|-------|------|--------|
| `javadropbox.files.served` | Counter, tagged `route` = `download`, `preview` or `share-link` | Files and zipped folders served. Requests for something missing aren't counted; each range request is, so a PDF viewer reading a file in parts counts more than once |
| `javadropbox.uploads.size` | Distribution summary, in bytes | One sample per stored file once its upload has committed: `COUNT` is files uploaded, `TOTAL` the bytes, `MAX` the largest |
| `javadropbox.share.links.created` | Counter | Share links created |

```bash
# Signed-in session cookie from the browser's dev tools
curl -b JSESSIONID=... 'http://localhost:8080/actuator/metrics/javadropbox.files.served?tag=route:share-link'
```

There is no Prometheus endpoint yet: a scraper has no way to sign in through the form login, and opening metrics to anonymous callers would publish usage figures. Scraping needs either a separate management port that only the monitoring network can reach, or a scrape credential that is throttled like sign-in.
