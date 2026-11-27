# syntax=docker/dockerfile:1.7

# Image from a jar built beforehand on the host: cd backend && ./gradlew bootJar, then docker build backend.
# The root Dockerfile builds from source and is the one CI and the release use; this one shares its runtime:
# the project's Java 17 on a pinned JRE base, Spring Boot layers, a non-root user and container-aware JVM flags.
# For another architecture than the host's, pass it to the build: docker build --platform linux/amd64 backend

# --- 1. Spring Boot layer extraction ---
FROM eclipse-temurin:17.0.21_9-jre-noble AS layers

WORKDIR /layers
COPY build/libs/OnlineOrder-0.0.1-SNAPSHOT.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted


# --- 2. Runtime ---
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

# 8080: application and UI. 8081: actuator when MANAGEMENT_SERVER_PORT=8081 is set; unset, it stays on 8080.
EXPOSE 8080 8081

USER 10001:10001

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar \"$@\"", "--"]
