# syntax=docker/dockerfile:1

# 1. The frontend bundle.
FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2. The backend jar, with the bundle inside it so one container serves everything.
FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# Resolve dependencies in their own layer so source changes do not re-download them.
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
COPY --from=frontend /frontend/dist frontend/dist
RUN ./gradlew --no-daemon bootJar -PbundleFrontend

# 3. The runtime image.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S javadropbox && adduser -S javadropbox -G javadropbox \
    && mkdir /data && chown javadropbox:javadropbox /data
WORKDIR /app
COPY --from=backend /app/build/libs/*.jar app.jar

USER javadropbox
# Stored files, their versions and the generated share-link key all live here.
ENV JAVADROPBOX_SERVING_DIRECTORY=/data
VOLUME /data
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
