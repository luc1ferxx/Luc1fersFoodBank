# Spring Boot 4 migration

The backend moved from Spring Boot 3.5.16 (Spring Framework 6.2, Jackson 2) to Spring Boot 4.1.1 (Spring Framework
7.0.9, Spring Security 7.1, Spring Data 2026.0, Spring Kafka 4.1 with kafka-clients 4.2, Spring Session 4.1,
Micrometer 1.17 / Micrometer Tracing 1.7, OpenTelemetry 1.62, Jackson 3.1, Flyway 12.4, Testcontainers 2.0,
JUnit 6). Java stays at 17 and the Gradle wrapper at 8.14.3 (Spring Boot 4 supports Gradle 8.14 and later).

This file is the checklist for porting code written against Spring Boot 3 (other branches, old snippets). Apply every
row that matches; the compiler finds most of them, the ones marked **runtime** it does not.

To find what still needs porting after a merge, run from the repository root (no output means nothing left):

```sh
git grep -nE 'com\.fasterxml\.jackson\.(databind|core)|JsonProcessingException|findAndRegisterModules|Jackson2JsonRedisSerializer|boot\.test\.web\.client|boot\.test\.autoconfigure\.(web|actuate)|@MockBean|boot\.test\.mock\.mockito|boot\.autoconfigure\.(security|jdbc|flyway|kafka|session)\.|actuate\.autoconfigure\.security|boot\.actuate\.metrics\.cache|boot\.web\.context\.|containers\.PostgreSQLContainer|PostgreSQLContainer<|management\.otlp|tracing\.opentelemetry\.export|session\.redis\.namespace|" receive"' -- backend
```

The grep cannot find two runtime changes; review them by hand in the merged code: every `getAuthorities()` that
lists roles or falls back to "the first authority" (Spring Security 7 adds `FACTOR_PASSWORD`, see below), and every
JSON input record with a primitive component (Jackson 3, `JsonInputTypesTests` finds those it can see).

Then run the CI gate (`./gradlew test jacocoTestReport jacocoTestCoverageVerification spotbugsMain -x buildFrontend
-x syncFrontendBuild --no-daemon`) with Docker running: a test class that uses `TestRestTemplate` without
`@AutoConfigureTestRestTemplate`, or asserts trace ids without `@AutoConfigureTracing`, fails there, not at compile
time.

## 1. Build (`backend/build.gradle`)

| Before | After |
| --- | --- |
| plugin `org.springframework.boot` `3.5.16` | `4.1.1` (`io.spring.dependency-management` stays `1.1.7`) |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `org.springframework.kafka:spring-kafka` | `spring-boot-starter-kafka` (the Kafka auto-configuration is its own module now) |
| `org.flywaydb:flyway-core` | `spring-boot-starter-flyway` (`flyway-database-postgresql` stays) |
| `org.springframework.session:spring-session-data-redis` | `spring-boot-starter-session-data-redis` |
| `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` | `spring-boot-starter-opentelemetry`, excluding `io.micrometer:micrometer-registry-otlp` (metrics stay on the Prometheus scrape) |
| `com.h2database:h2` | `spring-boot-h2console` (brings H2; the `h2` profile's `/h2-console` needs the module) |
| `springdoc-openapi-starter-webmvc-ui:2.8.17` | `3.1.1` (the line for Spring Boot 4.1) |
| `org.testcontainers:{junit-jupiter,postgresql,kafka,testcontainers}:1.21.4` | `org.testcontainers:testcontainers-{junit-jupiter,postgresql,kafka}`, version from Spring Boot (2.0.5) |
| test classpath | adds `spring-boot-starter-webmvc-test` (MockMvc, `TestRestTemplate`), `spring-boot-restclient` (`TestRestTemplate` needs `RestTemplateBuilder`), `spring-boot-micrometer-metrics-test` (`@AutoConfigureMetrics`), `spring-boot-micrometer-tracing-test` (`@AutoConfigureTracing`), `io.micrometer:micrometer-tracing-bridge-otel` (tests build `OtelTracer` themselves; the starter has it at runtime only) |

Version overrides at the top of the file:

| Override | Status |
| --- | --- |
| `tomcat.version` 10.1.61 | now `11.0.27`: Spring Boot 4.1.1 manages Tomcat 11.0.24, which has three fixable CRITICAL findings (CVE-2026-65182, CVE-2026-65905, CVE-2026-68525, fixed in 11.0.25). Never set a 10.1.x version here again |
| `netty.version` 4.1.139.Final | removed: Spring Boot manages Netty 4.2.17 |
| `jackson-bom.version` 2.21.7 | In Spring Boot 4 `jackson-bom.version` is Jackson 3: now `3.1.7` (Spring Boot manages 3.1.5; CVE-2026-89407, CVE-2026-89425, CVE-2026-68497, CVE-2026-91776, CVE-2026-91777). The Jackson 2 pin moved to `jackson-2-bom.version` 2.21.7: Jackson 2 is still on the runtime classpath through springdoc's swagger-core, and the 2.21.5 Spring Boot manages has the same five CVEs |
| `at.yawk.lz4:lz4-java` 1.11.4 | kept: kafka-clients 4.2.1 still asks for 1.10.1 |
| `org.postgresql:postgresql:42.7.14` | kept (Spring Boot manages 42.7.13) |
| `h2.version` 2.3.232 | **new**. Spring Boot 4.1.1 manages H2 2.4.240, where a `CHECK (col IN (...))` constraint keeps a reference to the session that created it; once that connection closes (Flyway migrates over its own connection), every insert into `orders`, `idempotency_requests` and the other tables with such a constraint fails with "Check constraint invalid ... The database has been closed". 2.3.232 is what Spring Boot 3.5 used. Drop the override once an H2 release fixes it (run `H2CartCheckoutIntegrationTests`). |

`spring-boot-properties-migrator` was on the runtime classpath during the upgrade and has been removed again. With
it, the application YAML loaded without a report, while the old keys passed through the environment or the command
line were reported, so it was active.

`.trivyignore`: CVE-2026-47884 and CVE-2026-47890 (spring-webmvc 6.2) are fixed by Spring Framework 7.0.9 and were
removed. The image built from the root `Dockerfile` scans clean with Trivy 0.75.0 (`HIGH,CRITICAL`, fixable only,
empty ignore list): 0 findings for the ubuntu 24.04 base and 0 for the jars.

## 2. Packages and APIs

### Jackson 3

| Before | After |
| --- | --- |
| `com.fasterxml.jackson.databind.*` (`ObjectMapper`, `JavaType`, `json.JsonMapper`, `type.TypeFactory`, `PropertyNamingStrategies`, ...) | `tools.jackson.databind.*` |
| `com.fasterxml.jackson.core.type.TypeReference` | `tools.jackson.core.type.TypeReference` |
| `com.fasterxml.jackson.core.JsonProcessingException` (checked) | `tools.jackson.core.JacksonException` (unchecked); `catch (IOException ...)` around `readValue` becomes `catch (JacksonException ...)` |
| `com.fasterxml.jackson.annotation.*` | unchanged (Jackson 3 keeps the 2.x annotations) |
| `new ObjectMapper().findAndRegisterModules()` | `JsonMapper.builder().build()` (java.time support is built in; mappers are immutable) |
| `mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)` | `JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()` |
| injecting `ObjectMapper` | inject `tools.jackson.databind.ObjectMapper` (Spring Boot's bean is a `JsonMapper`); a `com.fasterxml` `ObjectMapper` bean no longer exists |
| `Jackson2JsonRedisSerializer` | `JacksonJsonRedisSerializer` (same `(ObjectMapper, JavaType)` constructor) |

**Runtime** - Jackson 3 changes several defaults. What this project does about each:

| Jackson 3 default | Effect here | Decision |
| --- | --- | --- |
| `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` on | record components keep their declaration order anyway (every DTO and entity here is a record); fields of any other class would come out alphabetically | switched off for both mappers (`spring.jackson.mapper.sort-properties-alphabetically: false`, `.disable(...)` in `TwoLevelCaches.objectMapper()`), so a non-record type added later keeps Jackson 2's order too. Field order of API bodies, error bodies, outbox payloads, catalog ETags and Redis entries is unchanged (`ApiContractIntegrationTests`, `CatalogCacheSerializationTests`) |
| `FAIL_ON_UNKNOWN_PROPERTIES` off | the cache mapper would read an entry of another shape as a half-empty DTO | switched on in `TwoLevelCaches.objectMapper()` (an unreadable entry stays a miss). Spring Boot's mapper had it off before and still has |
| `FAIL_ON_NULL_FOR_PRIMITIVES` on | a primitive record component (`int`, `long`, `boolean`, ...) whose value is **missing** from the JSON, or `null`, no longer binds to `0`/`false`: a request body is `400 BAD_REQUEST`, and a Kafka event written before such a field existed fails to read and is dead-lettered | accepted, with boxed types (`Long`, `Integer`, `Boolean`) in every JSON input record; `@NotNull` where a value is required. `JsonInputTypesTests` fails on a primitive component in any `@RequestBody` type or consumed event envelope, nested records included |
| `FAIL_ON_TRAILING_TOKENS` on | trailing content after the JSON body (`{...} trailing`) is now `400 BAD_REQUEST` | accepted |
| `READ/WRITE_ENUMS_USING_TO_STRING` on | none: no enum overrides `toString()` | accepted; keep it that way or annotate the enum |
| java.time built in, dates as ISO strings | none for Spring Boot's mapper (it already wrote ISO strings) | accepted |

`spring.jackson.use-jackson2-defaults` (all Jackson 2 defaults) is not used.

### Spring Boot module packages

| Before | After |
| --- | --- |
| `org.springframework.boot.autoconfigure.security.servlet.PathRequest` | `org.springframework.boot.security.autoconfigure.web.servlet.PathRequest` |
| `org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest` | `org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest` |
| `org.springframework.boot.web.context.WebServerApplicationContext` | `org.springframework.boot.web.server.context.WebServerApplicationContext` |
| `org.springframework.boot.actuate.metrics.cache.CacheMeterBinderProvider` | `org.springframework.boot.cache.metrics.CacheMeterBinderProvider` |
| `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` | `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` |
| `org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration` | `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration` |
| `org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration` (and `KafkaProperties`) | `org.springframework.boot.kafka.autoconfigure.*` |
| `org.springframework.boot.autoconfigure.session.SessionAutoConfiguration` | `org.springframework.boot.session.autoconfigure.SessionAutoConfiguration` |
| (Redis sessions were part of `SessionAutoConfiguration`) | `org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration` |

Unchanged: `org.springframework.boot.autoconfigure.condition.*`, `AutoConfigurations`,
`org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration`, `@SpringBootTest`, `@LocalServerPort`,
`@LocalManagementPort`, `ApplicationContextRunner`, `ConfigDataApplicationContextInitializer`, `OutputCaptureExtension`.

**Runtime** - `spring.autoconfigure.exclude` strings are not compiled. Excluding Spring Boot 3's
`SessionAutoConfiguration` used to switch Redis sessions off too; now exclude both
`org.springframework.boot.session.autoconfigure.SessionAutoConfiguration` and
`org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration`, otherwise the Redis
session filter stays registered. Spring Boot ignores an excluded class that does not exist, so a stale name fails
silently.

### Tests

| Before | After |
| --- | --- |
| `org.springframework.boot.test.web.client.TestRestTemplate` | `org.springframework.boot.resttestclient.TestRestTemplate`, and the test class (or its base class) needs `@AutoConfigureTestRestTemplate` (`org.springframework.boot.resttestclient.autoconfigure`) |
| `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` |
| `@MockBean` (`org.springframework.boot.test.mock.mockito`) | `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) |
| `@AutoConfigureObservability(tracing = false)` | `@AutoConfigureMetrics` (`org.springframework.boot.micrometer.metrics.test.autoconfigure`) |
| `@AutoConfigureObservability(metrics = false)` | `@AutoConfigureTracing` (`org.springframework.boot.micrometer.tracing.test.autoconfigure`) |
| `org.testcontainers.containers.PostgreSQLContainer<?>` / `new PostgreSQLContainer<>(...)` | `org.testcontainers.postgresql.PostgreSQLContainer` / `new PostgreSQLContainer(...)` (not generic) |
| `org.testcontainers.kafka.KafkaContainer`, `GenericContainer`, `@Testcontainers`, `@Container` | unchanged |

**Runtime** - Spring Boot tests set `management.tracing.export.enabled=false` unless the class has
`@AutoConfigureTracing`. That property replaces Spring Boot 3's `management.tracing.enabled` and also switches W3C
propagation off, so a test that asserts trace ids (`X-Trace-Id`, `trace_id`, outbox `trace_parent`, Kafka spans)
needs `@AutoConfigureTracing`. OTLP export still stays off there (`management.tracing.export.otlp.enabled`).

### Spring Security 7

- `SecurityFilterChain`, `JdbcUserDetailsManager`, form login, the session-fixation and logout configuration and
  `hasRole(...)` compiled and behave unchanged.
- **Runtime** - every password login now carries an extra authority: Spring Security 7.1's
  `AbstractUserDetailsAuthenticationProvider` adds `FactorGrantedAuthority` `FACTOR_PASSWORD` (multi-factor
  support). For `demo@laifood.com`, `Authentication.getAuthorities()` is `FACTOR_PASSWORD`, `ROLE_ADMIN`,
  `ROLE_USER` (Spring Boot 3.5: the two roles). `hasRole`/`hasAnyRole` checks are unaffected. Code that turns the
  authorities into a list of roles (a `roles` field in `/me`, the actor of an audit entry, any "first authority"
  fallback) must keep only the `ROLE_` ones, for example
  `.map(GrantedAuthority::getAuthority).filter(authority -> authority.startsWith("ROLE_"))`, so API responses keep
  their `ROLE_*` contract. `RedisSessionFormatTests` asserts the authorities of a stored login.
- **Runtime** - the stored session changes with it, see "Sessions across releases" in section 4.

### Spring Kafka 4

- **Runtime** - the listener (consumer) span is named `<topic> process` (Spring Kafka 3: `<topic> receive`). The
  producer span stays `<topic> send`. No dashboard or alert reads span names.
- `KafkaTemplate`, `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, `ConcurrentKafkaListenerContainerFactory`
  and the observation switches compiled unchanged. Test brokers stay `apache/kafka-native:3.8.0` (kafka-clients 4
  needs brokers 2.1 or later).

## 3. Configuration properties (`application*.yml`, test properties, environment)

| Spring Boot 3 | Spring Boot 4 |
| --- | --- |
| `spring.session.redis.namespace` | `spring.session.data.redis.namespace`, value now `${REDIS_SESSION_NAMESPACE:onlineorder:session}:v2` (see "Sessions across releases") |
| `management.otlp.tracing.endpoint` | `management.opentelemetry.tracing.export.otlp.endpoint` |
| `management.otlp.tracing.export.enabled` | `management.tracing.export.otlp.enabled` |
| `management.tracing.opentelemetry.export.schedule-delay` (and the other `...opentelemetry.export.*`) | `management.opentelemetry.tracing.export.schedule-delay` |
| `management.tracing.enabled` | `management.tracing.export.enabled` (also controls propagation, see Tests) |
| - | `spring.jackson.mapper.sort-properties-alphabetically: false` (new, see Jackson 3) |

The environment variables in front of them (`REDIS_SESSION_NAMESPACE`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`,
`TRACING_EXPORT_ENABLED`, `TRACING_SAMPLING_PROBABILITY`) are unchanged, so `deploy/` needed no change.
`REDIS_SESSION_NAMESPACE` now sets only the prefix: the `:v2` format version is always appended. The
deployment's own Spring variables (`MANAGEMENT_SERVER_PORT`, `SPRING_DATA_REDIS_PASSWORD`,
`SPRING_KAFKA_PROPERTIES_*`, `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE`) still bind.

`spring.session.store-type` (in `application*.yml` and several tests) was already removed in Spring Boot 3.0 and does
nothing; it is left as it was.

## 4. Runtime comparison with Spring Boot 3.5

Both jars (base `bf824ba` and this branch) ran the `h2` profile with a throwaway Redis, the management port split
and, in a second run, Kafka on; the same requests went to each and the normalized responses were diffed.

### Sessions across releases

Spring Session stores the `SecurityContext` JDK-serialized in Redis, and pods of two releases share that store
during a rolling update (`deploy/k8s/base`: `RollingUpdate`, `maxSurge: 1`, no session affinity) and after a
rollback. A Spring Boot 4 login session contains `FactorGrantedAuthority` (and a `java.time.Instant` for its issue
time); Spring Security 6.5 has no such class. Measured with both jars against one Redis, before the fix: a Spring
Boot 3.5 pod that received a Spring Boot 4 session cookie threw `SerializationException` (`ClassNotFoundException`)
in `SessionRepositoryFilter` and answered every request with Tomcat's HTML 500 page, public catalog reads and
`POST /logout` included, until the session's 30-minute timeout. The other direction worked (the class
`serialVersionUID`s match).

Fix: the session namespace carries a format version, like the catalog cache keys. Spring Boot 3.5 used
`onlineorder:session`; this release uses `onlineorder:session:v2`
(`spring.session.data.redis.namespace: ${REDIS_SESSION_NAMESPACE:onlineorder:session}:v2`). Old and new pods never
read each other's sessions: a cookie of the other release is an unknown session, so protected requests get the JSON
`401 UNAUTHORIZED` and public ones work. Measured again with both jars: Spring Boot 4 cookie on the 3.5 pod ->
`/me` and `/cart` 401 JSON, `/restaurants/menu` 200, `/logout` 200, no `SerializationException`; 3.5 cookie on the
Spring Boot 4 pod -> 401 JSON.

Cost: everyone signs in once after the upgrade (and again after a rollback); during the rollout itself a user whose
requests alternate between old and new pods is signed out each time they switch. Old `onlineorder:session:*` keys
expire on their own; nothing needs flushing.

`RedisSessionFormatTests` pins every class (with its `serialVersionUID`) in a stored login session. When a
dependency upgrade changes that set, the test fails: bump the suffix (`:v3`) in `application.yml` and the test, then
update the set.

### Other differences

Unchanged: status, headers and body of the catalog reads (including `ETag` and the `304` answer), 401 and 404 error
bodies, signup validation errors, login success and failure, `/me`, cart, orders, notifications, a full
login -> cart -> checkout -> PAID flow through the outbox and Kafka, the OpenAPI paths, health groups on the
management port, the security headers (CSP, frame options, referrer policy, `Permissions-Policy`, cache headers), the
session cookie flags (`HttpOnly`, `SameSite=Lax`), and the catalog cache in Redis (the Spring Boot 4 instance started
against the Redis the Spring Boot 3.5 instance had filled and logged no cache read error). Every Prometheus metric that
`deploy/observability` dashboards and alerts read is still published under the same name.

Differences (none breaks a dashboard or an alert; clients only sign in again once, see above):

| Where | Spring Boot 3.5 | Spring Boot 4.1 |
| --- | --- | --- |
| request body with content after the JSON (`{...} trailing`) | accepted | `400 BAD_REQUEST` (Jackson 3 `FAIL_ON_TRAILING_TOKENS`) |
| `GET /` (static `index.html`) | `Vary: Origin, Access-Control-Request-Method, Access-Control-Request-Headers` | no `Vary` header (no CORS configuration; Spring Framework 7) |
| `POST /logout` cookie removal | `Max-Age=0; Expires=<past>` | `Expires=<past>` (Tomcat 11); the cookies are still removed |
| small JSON responses | `Transfer-Encoding: chunked` | `Content-Length` |
| Lettuce metrics | `lettuce_command_completion_seconds`, `lettuce_command_firstresponse_seconds` | `lettuce_seconds`, `lettuce_active_seconds` (Lettuce 7 observation) |
| Kafka client metrics | `kafka_{consumer,producer}_io{,_wait}time_total`, `kafka_producer_bufferpool_wait_time_total` | removed in kafka-clients 4 (`kafka_producer_txn_prepare_time_ns_total` is new) |
| JVM metrics | - | `jvm_classes_loaded_count_classes_total` added |
| listener span name | `<topic> receive` | `<topic> process` |
| Redis command spans | none (Lettuce commands were only timed) | every Lettuce command is a `CLIENT` span (`db.system=redis`) under the current span: an authenticated `GET /cart` has 9 spans instead of 5 (`exists`, `hgetall`, `hmset`, `pexpireat` for the session); a catalog read that misses the local L1 adds the Redis cache read, a rate-limited request the token-bucket script (`evalsha`). Kept: they show each request's Redis latency in Tempo. `docs/operations.md` has the volume and the switch (`OutboxKafkaTraceContinuityTests`) |
| session namespace | `onlineorder:session` | `onlineorder:session:v2` (see "Sessions across releases") |

## 5. Things checked that did not change

- JSON: snake_case, `non_null`, error body (`code`, `message`, `trace_id`, `status`, `path`, `timestamp`), ISO-8601
  instants, field order.
- Security: CSP, frame options, referrer policy, `Permissions-Policy`, session cookie flags, the management-port split
  (`ManagementPortIntegrationTests`), `/actuator/**` behind `ROLE_ADMIN` on the application port.
- Catalog cache: Redis keys and JSON are byte for byte the same, so the key versions stay `v1`.
- Outbox tracing continuity (`OutboxKafkaTraceContinuityTests`).
