# syntax=docker/dockerfile:1.7

FROM gradle:8.14.3-jdk17 AS build

USER root
RUN apt-get update \
    && apt-get install -y curl ca-certificates gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

USER gradle
WORKDIR /workspace

COPY --chown=gradle:gradle frontend frontend
COPY --chown=gradle:gradle backend backend

WORKDIR /workspace/frontend
RUN npm ci --legacy-peer-deps

WORKDIR /workspace/backend
RUN sh gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/backend/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
