# syntax=docker/dockerfile:1

# 1. The frontend bundle.
FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
# Cache mounts keep downloaded packages between builds without putting them in a layer.
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
# The PNGs are Git LFS objects. A clone without git-lfs has small text pointers in their
# place, which build without complaint and ship an app with every logo broken.
RUN pointers=$(find src public -name '*.png' -exec grep -l '^version https://git-lfs' {} +); \
    if [ -n "$pointers" ]; then \
      printf 'Git LFS pointers instead of images:\n%s\nInstall git-lfs, run "git lfs pull" and build again.\n' "$pointers" >&2; \
      exit 1; \
    fi
RUN npm run build

# 2. The backend jar, with the bundle inside it so one container serves everything.
FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
COPY --from=frontend /frontend/dist frontend/dist
# The Gradle distribution and dependency jars stay in the cache mount, so a source change
# does not download them again. (A "dependencies" layer would only hold the POMs.)
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -PbundleFrontend

# 3. The runtime image.
FROM eclipse-temurin:21-jre-alpine
# A fixed uid/gid, so a bind-mounted directory can be chowned to it and a base-image change
# cannot hand the volume's files to a different user.
RUN addgroup -S -g 10001 javadropbox && adduser -S -u 10001 -G javadropbox javadropbox \
    && mkdir /data && chown javadropbox:javadropbox /data
WORKDIR /app
COPY --from=backend /app/build/libs/*.jar app.jar

# Numeric, so runtimes can verify it is not root without reading /etc/passwd.
USER 10001:10001
# Stored files, their versions and the generated share-link key all live here.
ENV JAVADROPBOX_SERVING_DIRECTORY=/data
VOLUME /data
EXPOSE 8080
# The app shell is served without signing in, even before setup. Startup includes the migrations.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/index.html || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
