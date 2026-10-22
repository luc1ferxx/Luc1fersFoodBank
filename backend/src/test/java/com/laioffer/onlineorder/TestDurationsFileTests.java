package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * config/test-durations.properties, which balances the CI test shards (-PtestShard in build.gradle), names only test
 * classes that still exist: a renamed or deleted class must leave the file (scripts/test-durations.py rebuilds it from
 * a green run's JUnit XML). A class the file does not name is fine, it is planned at the median, so a new class need
 * not be listed. Gradle runs the tests from the backend project directory, which the relative path relies on.
 */
class TestDurationsFileTests {

    private static final Path FILE = Path.of("config", "test-durations.properties");


    @Test
    void everyClassTheDurationsFileNames_shouldExistOnTheTestClasspath() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String name : durations().keySet()) {
            try {
                // No initialization: a test class may declare containers in static fields.
                Class.forName(name, false, TestDurationsFileTests.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                missing.add(name);
            }
        }

        Assertions.assertEquals(List.of(), missing,
                "classes named in " + FILE + " that no longer exist; refresh it with scripts/test-durations.py");
    }


    @Test
    void everyLineOfTheDurationsFile_shouldBeAClassWithNonNegativeSeconds() throws IOException {
        Map<String, BigDecimal> durations = durations();

        Assertions.assertFalse(durations.isEmpty(), FILE + " names no class");
        durations.forEach((name, seconds) ->
                Assertions.assertTrue(seconds.signum() >= 0, name + " has a negative duration: " + seconds));
    }


    private static Map<String, BigDecimal> durations() throws IOException {
        Assertions.assertTrue(Files.isRegularFile(FILE),
                FILE + " is missing (the tests run from the backend directory)");
        Map<String, BigDecimal> durations = new LinkedHashMap<>();
        for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int separator = entry.indexOf('=');
            Assertions.assertTrue(separator > 0, "not a class=seconds line: " + line);
            String name = entry.substring(0, separator).strip();
            Assertions.assertFalse(name.contains("$"),
                    name + " is a nested class; they fold into their outer class, refresh with scripts/test-durations.py");
            BigDecimal seconds = Assertions.assertDoesNotThrow(
                    () -> new BigDecimal(entry.substring(separator + 1).strip()), "not a class=seconds line: " + line);
            Assertions.assertNull(durations.put(name, seconds), name + " is listed twice");
        }
        return durations;
    }
}
