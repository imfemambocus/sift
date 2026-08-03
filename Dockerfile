# syntax=docker/dockerfile:1

# the bundle is baked into the jar and served by spring, so the whole app is one container on one
# origin. that is what keeps the session cookie and the csrf handshake identical to development.

FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk AS backend
WORKDIR /build
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle gradle
# resolve dependencies in their own layer, so editing source does not re-download them
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath >/dev/null
COPY backend/src src
COPY --from=frontend /frontend/dist src/main/resources/static
RUN ./gradlew --no-daemon bootJar

# alpine for the runtime layer only: it is a pure JVM workload with no native dependencies, and it
# took the image from 605MB to 417MB. most of what is left is the JRE plus the fat jar.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# nothing here needs root, and the app holds other people's tokens
RUN addgroup -S sift && adduser -S -G sift -h /app sift
COPY --from=backend /build/build/libs/*.jar app.jar
USER sift
EXPOSE 7777
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
