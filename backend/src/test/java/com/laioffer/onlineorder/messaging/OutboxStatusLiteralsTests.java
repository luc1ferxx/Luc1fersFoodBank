package com.laioffer.onlineorder.messaging;


import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.service.DeadLetterReplayService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.repository.query.Query;


import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * The outbox statuses spelled out as SQL literals (the queries and partial indexes that PostgreSQL must match, and
 * the CHECK of V13) are the names of {@link OutboxStatus}, and the CHECK on dead_letter_events.replay_status lists the
 * replay statuses DeadLetterReplayService writes.
 */
class OutboxStatusLiteralsTests {

    private static final Pattern QUOTED_STATUS = Pattern.compile("'([A-Z_]+)'");
    private static final String V13 = "db/migration/V13__outbox_claim_token.sql";


    @Test
    void theStatusLiteralsOfTheRepositoryQueries_shouldBeOutboxStatuses() {
        Set<String> literals = new TreeSet<>();
        for (Method method : OutboxEventRepository.class.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query != null) {
                literals.addAll(quotedStatuses(query.value()));
            }
        }

        Assertions.assertEquals(Set.of("FAILED", "PENDING", "PROCESSING"), literals);
        Assertions.assertTrue(outboxStatusNames().containsAll(literals), literals.toString());
    }


    @Test
    void thePartialIndexPredicatesOfV3AndV9_1_shouldNameOutboxStatuses() throws IOException {
        for (String migration : List.of(
                "V3__Add_outbox_unpublished_index.java",
                "V9_1__Add_outbox_aggregate_head_index.java"
        )) {
            String source = Files.readString(Path.of("src/main/java/db/migration", migration));
            Set<String> literals = quotedStatuses(source);

            Assertions.assertFalse(literals.isEmpty(), migration);
            Assertions.assertTrue(outboxStatusNames().containsAll(literals), migration + ": " + literals);
        }
    }


    @Test
    void theOutboxStatusCheckOfV13_shouldListExactlyTheOutboxStatuses() throws IOException {
        Assertions.assertEquals(outboxStatusNames(), checkedValues("chk_outbox_events_status"));
    }


    @Test
    void theReplayStatusCheckOfV13_shouldListExactlyTheReplayStatusesTheCodeWrites() throws Exception {
        Set<String> replayStatuses = new TreeSet<>();
        for (Field field : DeadLetterReplayService.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("REPLAY_STATUS_")) {
                field.setAccessible(true);
                replayStatuses.add((String) field.get(null));
            }
        }

        Assertions.assertEquals(Set.of("PENDING", "REPLAYING", "REPLAYED", "REPLAY_FAILED"), replayStatuses);
        Assertions.assertEquals(replayStatuses, checkedValues("chk_dead_letter_events_replay_status"));
    }


    private static Set<String> checkedValues(String constraint) throws IOException {
        String sql;
        try (InputStream migration = OutboxStatusLiteralsTests.class.getClassLoader().getResourceAsStream(V13)) {
            Assertions.assertNotNull(migration, V13);
            sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher check = Pattern.compile(constraint + "\\s+CHECK \\(\\w+ IN \\(([^)]*)\\)\\)").matcher(sql);
        Assertions.assertTrue(check.find(), constraint);
        return quotedStatuses(check.group(1));
    }


    private static Set<String> quotedStatuses(String sql) {
        Set<String> statuses = new TreeSet<>();
        Matcher matcher = QUOTED_STATUS.matcher(sql);
        while (matcher.find()) {
            statuses.add(matcher.group(1));
        }
        return statuses;
    }


    private static Set<String> outboxStatusNames() {
        Set<String> names = new TreeSet<>();
        Arrays.stream(OutboxStatus.values()).map(OutboxStatus::name).forEach(names::add);
        return names;
    }
}
