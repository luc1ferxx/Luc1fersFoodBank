# syntax=docker/dockerfile:1.7

# Full source build: React frontend, Spring Boot jar, layered non-root runtime image.
# Base images are pinned to exact versions and updated by hand. A new tag must exist for linux/amd64 and linux/arm64
# (CI's config-lint checks it): gradle:9.9.2-jdk17-noble and eclipse-temurin:17.0.21.1_2-jre-noble were published
# without arm64, so both stay on the newest tag that has it.

# --- 1. Frontend (official Node image) ---
FROM node:24.22.3-bookworm-slim AS frontend

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY frontend/ ./
RUN npm run build


# --- 2. Backend jar with the frontend embedded ---
FROM gradle:9.9.1-jdk17-noble AS backend

RUN install -d -o gradle -g gradle /workspace /workspace/backend /workspace/frontend
WORKDIR /workspace/backend
COPY --chown=gradle:gradle backend/settings.gradle backend/build.gradle ./
COPY --chown=gradle:gradle backend/config config
COPY --chown=gradle:gradle backend/src src
# syncFrontendBuild copies ../frontend/build into the jar; the frontend stage already built it, so the npm-based
# buildFrontend task is skipped here.
COPY --from=frontend --chown=gradle:gradle /workspace/frontend/build /workspace/frontend/build

USER gradle
RUN --mount=type=cache,target=/home/gradle/.gradle/caches,uid=1000,gid=1000 \
    gradle bootJar -x buildFrontend --no-daemon --console=plain \
    && cp build/libs/OnlineOrder-*.jar /workspace/application.jar


# --- 3. Spring Boot layer extraction: dependencies change less often than the application ---
FROM eclipse-temurin:17.0.21_9-jre-noble AS layers

WORKDIR /layers
COPY --from=backend /workspace/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted


# --- 4. Runtime ---
FROM eclipse-temurin:17.0.21_9-jre-noble

RUN groupadd --system --gid 10001 onlineorder \
    && useradd --system --uid 10001 --gid onlineorder --home-dir /app --shell /usr/sbin/nologin onlineorder

WORKDIR /app
COPY --from=layers --chown=10001:10001 /layers/extracted/dependencies/ ./
COPY --from=layers --chown=10001:10001 /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=10001:10001 /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=10001:10001 /layers/extracted/application/ ./

# G1 is named because the JVM would otherwise choose by container size: below 1792 MB of memory (or with one CPU) it
# takes the Serial collector, so a 1Gi pod and a 2Gi pod ran different collectors. The heap follows the container
# memory limit; an OutOfMemoryError ends the process so the orchestrator restarts it. GC events go to a rotated file
# in /tmp (5 x 8 MB at most), which every deployment keeps writable, so stdout stays the application's JSON log.
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=5,filesize=8m"

# 8080: application and UI. 8081: actuator (probes, Prometheus) when the deployment sets MANAGEMENT_SERVER_PORT=8081,
# as Kubernetes and deploy/observability do; keep it inside the cluster. Unset, the actuator stays on 8080.
EXPOSE 8080 8081

USER 10001:10001

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar \"$@\"", "--"]
