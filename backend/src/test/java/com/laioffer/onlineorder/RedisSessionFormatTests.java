package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;


/**
 * Pods of two releases share the Redis session store during a rolling update and after a rollback. Sessions are
 * JDK-serialized, so a pod that reads a session holding a class it does not have (or one with another
 * serialVersionUID) fails inside the session filter and answers every request carrying that cookie with an HTML
 * 500, public endpoints and /logout included. Spring Security 7 adds a {@link FactorGrantedAuthority} to every
 * password login, a class Spring Boot 3.5 (Spring Security 6.5) does not have. The session namespace therefore
 * carries a format version, like the catalog cache keys: releases with different formats never read each other's
 * sessions, and their users sign in again once (a JSON 401) instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestRestTemplate
class RedisSessionFormatTests {

    /**
     * The namespace of this release's sessions. Bump the version suffix in application.yml
     * (spring.session.data.redis.namespace) and here whenever {@link #SESSION_FORMAT} changes.
     */
    private static final String NAMESPACE = "onlineorder:session:v2";

    /**
     * The namespace Spring Boot 3.5 releases (Spring Security 6.5) stored their sessions under.
     */
    private static final String UNVERSIONED_NAMESPACE = "onlineorder:session";

    /**
     * Every class (with the serialVersionUID it is written with) in a signed-in session of this release. A pod of
     * another release can read the session only if it has each class with the same serialVersionUID. When a
     * dependency upgrade changes this set, bump the namespace version, then update the set.
     */
    private static final Set<String> SESSION_FORMAT = Set.of(
            "java.lang.Integer 1360826667806852920",
            "java.lang.Long 4290774380558885855",
            "java.lang.Number -8742448824652078965",
            "java.time.Ser -7683839454370182990",
            "java.util.ArrayList 8683452581122892189",
            "java.util.Collections$UnmodifiableCollection 1820017752578914078",
            "java.util.Collections$UnmodifiableList -283967356065247728",
            "java.util.Collections$UnmodifiableSet -9215047833775013803",
            "java.util.TreeSet -2479143000061671589",
            "org.springframework.security.authentication.AbstractAuthenticationToken -3194696462184782834",
            "org.springframework.security.authentication.UsernamePasswordAuthenticationToken 620",
            // New in Spring Security 7 (FACTOR_PASSWORD, with its issue time as a java.time.Ser): the reason for v2.
            "org.springframework.security.core.authority.FactorGrantedAuthority 1998010439847123984",
            "org.springframework.security.core.authority.SimpleGrantedAuthority 620",
            "org.springframework.security.core.context.SecurityContextImpl 620",
            "org.springframework.security.core.userdetails.User 620",
            "org.springframework.security.core.userdetails.User$AuthorityComparator 620",
            "org.springframework.security.web.authentication.WebAuthenticationDetails 620"
    );

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    @Autowired
    private Environment environment;


    @Test
    void login_shouldStoreTheSessionUnderTheVersionedNamespaceOnly() {
        String sessionId = loginAndGetSessionId();

        Assertions.assertEquals(NAMESPACE, environment.getProperty("spring.session.data.redis.namespace"));
        Assertions.assertFalse(sessionHash(NAMESPACE, sessionId).isEmpty(), "session should be stored under " + NAMESPACE);
        Assertions.assertTrue(sessionHash(UNVERSIONED_NAMESPACE, sessionId).isEmpty());

        // A Spring Boot 3.5 pod looks the session up under its own namespace and does not find it: the request is
        // anonymous there (a JSON 401 on protected paths) instead of failing to deserialize the session.
        Assertions.assertNull(repositoryOf(UNVERSIONED_NAMESPACE).findById(sessionId));
        Assertions.assertEquals(HttpStatus.OK, get("/me", sessionId).getStatusCode());
    }


    @Test
    void storedSession_shouldKeepTheFormatOfItsNamespaceVersion() {
        String sessionId = loginAndGetSessionId();

        Set<String> classes = new TreeSet<>();
        sessionHash(NAMESPACE, sessionId).values().forEach(value -> classes.addAll(serializedClasses(value)));

        Assertions.assertEquals(
                new TreeSet<>(SESSION_FORMAT),
                classes,
                "The classes in a stored session changed, so pods of the previous release could fail to read it. Bump "
                        + "the version suffix of spring.session.data.redis.namespace in application.yml and NAMESPACE "
                        + "here, then update SESSION_FORMAT."
        );
    }


    /**
     * Spring Security 7 adds FACTOR_PASSWORD to the authorities of every password login (the reason for v2). Code
     * that lists a user's roles must keep only the ROLE_ authorities.
     */
    @Test
    void passwordLogin_shouldAddTheFactorAuthorityNextToTheRoles() {
        String sessionId = loginAndGetSessionId();

        Session session = sessionRepository.findById(sessionId);
        Assertions.assertNotNull(session);
        SecurityContext context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        Assertions.assertNotNull(context);
        Set<String> authorities = new TreeSet<>();
        for (GrantedAuthority authority : context.getAuthentication().getAuthorities()) {
            authorities.add(authority.getAuthority());
        }

        Assertions.assertEquals(Set.of("FACTOR_PASSWORD", "ROLE_ADMIN", "ROLE_USER"), authorities);
        Assertions.assertTrue(context.getAuthentication().getAuthorities().stream()
                .anyMatch(FactorGrantedAuthority.class::isInstance));
    }


    @Test
    void sessionOfTheUnversionedNamespace_shouldNotAuthenticate() {
        // A session a Spring Boot 3.5 pod stored for a signed-in user, read by this release after the rollout.
        RedisSessionRepository previousRelease = repositoryOf(UNVERSIONED_NAMESPACE);
        String legacySessionId = saveSignedInSession(previousRelease);
        Assertions.assertNotNull(previousRelease.findById(legacySessionId));

        ResponseEntity<String> me = get("/me", legacySessionId);

        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, me.getStatusCode());
        Assertions.assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(me.getHeaders().getContentType()), me::toString);
        Assertions.assertTrue(me.getBody().contains("\"code\":\"UNAUTHORIZED\""), me::toString);
        Assertions.assertEquals(HttpStatus.OK, get("/restaurants/menu", legacySessionId).getStatusCode());
    }


    @Test
    void namespaceOverride_shouldKeepTheFormatVersion() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("REDIS_SESSION_NAMESPACE=staging:session")
                .run(context -> Assertions.assertEquals(
                        "staging:session:v2",
                        context.getEnvironment().getProperty("spring.session.data.redis.namespace")
                ));
    }


    private String loginAndGetSessionId() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "demo@laifood.com");
        form.add("password", "demo123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<String> response = restTemplate.postForEntity(url("/login"), new HttpEntity<>(form, headers), String.class);

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        String cookie = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .map(setCookie -> setCookie.split(";", 2)[0])
                .filter(value -> value.startsWith("SESSION="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SESSION cookie: " + response.getHeaders()));
        return new String(Base64.getDecoder().decode(cookie.substring("SESSION=".length())), StandardCharsets.UTF_8);
    }


    private static <S extends Session> String saveSignedInSession(SessionRepository<S> repository) {
        S session = repository.createSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                        "demo@laifood.com",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                ))
        );
        repository.save(session);
        return session.getId();
    }


    private ResponseEntity<String> get(String path, String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + Base64.getEncoder()
                .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }


    private Map<byte[], byte[]> sessionHash(String namespace, String sessionId) {
        byte[] key = (namespace + ":sessions:" + sessionId).getBytes(StandardCharsets.UTF_8);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Map<byte[], byte[]> hash = connection.hashCommands().hGetAll(key);
            return hash == null ? Map.of() : hash;
        }
    }


    /**
     * A session repository configured the way Spring Boot sets one up (String keys, JDK-serialized values), under
     * the given namespace.
     */
    private RedisSessionRepository repositoryOf(String namespace) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setDefaultSerializer(new JdkSerializationRedisSerializer(getClass().getClassLoader()));
        template.afterPropertiesSet();
        RedisSessionRepository repository = new RedisSessionRepository(template);
        repository.setRedisKeyNamespace(namespace);
        return repository;
    }


    private static Set<String> serializedClasses(byte[] value) {
        Set<String> classes = new TreeSet<>();
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(value)) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
                classes.add(descriptor.getName() + " " + descriptor.getSerialVersionUID());
                return super.resolveClass(descriptor);
            }
        }) {
            in.readObject();
        } catch (IOException | ClassNotFoundException ex) {
            throw new AssertionError("Unreadable session value", ex);
        }
        return classes;
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
