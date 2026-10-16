# syntax=docker/dockerfile:1.7

# Full source build: React frontend, Spring Boot jar, layered non-root runtime image.
# Base images are pinned to exact versions; Dependabot (docker ecosystem) proposes updates.

# --- 1. Frontend (official Node image) ---
FROM node:26.11.1-bookworm-slim AS frontend

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY frontend/ ./
RUN npm run build


# --- 2. Backend jar with the frontend embedded ---
FROM gradle:8.14.3-jdk17-noble AS backend

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
FROM eclipse-temurin:17.0.20.1_1-jre-noble AS layers

WORKDIR /layers
COPY --from=backend /workspace/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted


# --- 4. Runtime ---
FROM eclipse-temurin:17.0.20.1_1-jre-noble

RUN groupadd --system --gid 10001 onlineorder \
    && useradd --system --uid 10001 --gid onlineorder --home-dir /app --shell /usr/sbin/nologin onlineorder

WORKDIR /app
COPY --from=layers --chown=10001:10001 /layers/extracted/dependencies/ ./
COPY --from=layers --chown=10001:10001 /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=10001:10001 /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=10001:10001 /layers/extracted/application/ ./

# The heap follows the container memory limit; an OutOfMemoryError ends the process so the orchestrator restarts it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"

# 8080: application and UI. 8081: actuator (probes, Prometheus) when the deployment sets MANAGEMENT_SERVER_PORT=8081,
# as Kubernetes and deploy/observability do; keep it inside the cluster. Unset, the actuator stays on 8080.
EXPOSE 8080 8081

USER 10001:10001

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar \"$@\"", "--"]
