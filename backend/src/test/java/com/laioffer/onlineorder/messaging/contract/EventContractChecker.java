package com.laioffer.onlineorder.messaging.contract;


import com.laioffer.onlineorder.messaging.contract.EventSchemaCatalog.Compatibility;
import com.laioffer.onlineorder.messaging.contract.EventSchemaCatalog.EventTypeContract;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.dialect.DialectId;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;


import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * The evolution rules of the event contracts (docs/correctness.md "Event contracts"), checked over a schema set and its
 * sample events. Each method returns the problems it found, worded for the developer who has to fix them; none means
 * the set follows the rules.
 *
 * <ul>
 *     <li>{@link #manifestProblems}: published schema files never change and are never deleted; SHA256SUMS holds the
 *     hash of every one of them, and a new file is added to it;</li>
 *     <li>{@link #catalogProblems}: catalog.json lists exactly the schema files of the set, every schema is a valid
 *     draft 2020-12 schema, and the version before the last BREAKING version of a type is still listed (consumers
 *     accept N and N-1);</li>
 *     <li>{@link #sampleProblems}: every listed version has sample events, every sample is valid for its own version,
 *     and a BACKWARD version is compatible both ways with every version before it, back to the last BREAKING one: it
 *     accepts their samples (a new consumer reads old events), and they accept its samples (a consumer that does not
 *     know it yet checks its events against the latest version it knows, so producers may switch first).</li>
 * </ul>
 */
final class EventContractChecker {

    static final String MANIFEST_FILE = "SHA256SUMS";

    private static final Pattern SCHEMA_FILE = Pattern.compile("([a-z-]+)/([a-z]+\\.[a-z_]+)/v([0-9]+)\\.json");
    private static final Pattern SAMPLE_FILE = Pattern.compile("([a-z-]+)/([a-z]+\\.[a-z_]+)/v([0-9]+)/([^/]+)\\.json");
    private static final Pattern MANIFEST_LINE = Pattern.compile("([0-9a-f]{64}) [ *](.+)");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String schemaLocation;
    private final String sampleLocation;


    /**
     * @param schemaLocation classpath directory of the schemas, catalog.json and SHA256SUMS, with a trailing slash
     * @param sampleLocation classpath directory of the samples (&lt;topic&gt;/&lt;event_type&gt;/v&lt;N&gt;/*.json),
     *                       with a trailing slash
     */
    EventContractChecker(String schemaLocation, String sampleLocation) {
        this.schemaLocation = schemaLocation;
        this.sampleLocation = sampleLocation;
    }


    List<String> manifestProblems() {
        List<String> problems = new ArrayList<>();
        Map<String, String> published = readManifest(problems);
        Map<String, byte[]> files = schemaFiles();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String path = file.getKey();
            String hash = sha256(file.getValue());
            String publishedHash = published.get(path);
            if (publishedHash == null) {
                problems.add(path + " is not in " + MANIFEST_FILE + ". Add the line: " + hash + "  " + path);
            } else if (!publishedHash.equals(hash)) {
                problems.add(path + " changed after it was published (" + MANIFEST_FILE + ": " + publishedHash
                        + ", file: " + hash + "). Published schemas are immutable: revert the change and publish it as"
                        + " the next version of the event type instead");
            }
        }
        for (String path : published.keySet()) {
            if (!files.containsKey(path)) {
                problems.add(path + " is listed in " + MANIFEST_FILE + " but missing. Published schemas are never deleted:"
                        + " consumers may still receive events of that version (outbox backlog, stored dead letters)");
            }
        }
        return problems;
    }


    List<String> catalogProblems(EventSchemaCatalog catalog) {
        List<String> problems = new ArrayList<>();
        Set<String> listed = new TreeSet<>();
        listed.add(catalog.envelopePath());
        for (EventTypeContract contract : catalog.eventTypes().values()) {
            contract.versions().keySet().forEach(version -> listed.add(contract.schemaPath(version)));
            Integer lastBreaking = contract.versions().entrySet().stream()
                    .filter(version -> version.getValue() == Compatibility.BREAKING)
                    .map(Map.Entry::getKey)
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (lastBreaking != null && !contract.versions().containsKey(lastBreaking - 1)) {
                problems.add(contract.eventType() + " v" + lastBreaking + " is BREAKING but v" + (lastBreaking - 1)
                        + " is no longer listed: consumers must accept the previous major version too, so keep it until"
                        + " a later major version replaces it");
            }
        }

        Map<String, byte[]> files = schemaFiles();
        for (String path : files.keySet()) {
            Matcher eventTypeSchema = SCHEMA_FILE.matcher(path);
            if (eventTypeSchema.matches() && !listed.contains(path) && !retired(catalog, eventTypeSchema)) {
                problems.add(path + " is not listed in catalog.json");
            }
        }
        for (String path : listed) {
            if (!files.containsKey(path)) {
                problems.add("catalog.json lists " + path + ", which does not exist");
            }
        }
        problems.addAll(metaSchemaProblems(files));
        return problems;
    }


    List<String> sampleProblems(EventSchemaRegistry registry) {
        List<String> problems = new ArrayList<>();
        Map<String, NavigableMap<Integer, Map<String, JsonNode>>> samples = samples(problems);
        for (EventTypeContract contract : registry.catalog().eventTypes().values()) {
            NavigableMap<Integer, Map<String, JsonNode>> byVersion = samples.getOrDefault(contract.eventType(), new TreeMap<>());
            for (Map.Entry<Integer, Compatibility> version : contract.versions().entrySet()) {
                Map<String, JsonNode> ofVersion = byVersion.get(version.getKey());
                if (ofVersion == null || ofVersion.isEmpty()) {
                    problems.add(contract.eventType() + " v" + version.getKey() + " has no sample events under "
                            + sampleLocation + contract.topic().contractName() + "/" + contract.eventType() + "/v"
                            + version.getKey() + "/");
                    continue;
                }
                for (Map.Entry<String, JsonNode> sample : ofVersion.entrySet()) {
                    List<String> violations = registry.validate(contract.eventType(), version.getKey(), sample.getValue());
                    if (!violations.isEmpty()) {
                        problems.add(sample.getKey() + " is not a valid " + contract.eventType() + " v"
                                + version.getKey() + " event: " + violations);
                    }
                }
                if (version.getValue() == Compatibility.BACKWARD) {
                    problems.addAll(backwardProblems(registry, contract, version.getKey(), byVersion));
                }
            }
            // Samples of a retired version (below the oldest listed one) may stay.
            for (Integer version : byVersion.keySet()) {
                if (version > contract.latestVersion()) {
                    problems.add("Samples of " + contract.eventType() + " v" + version + " exist, but the catalog does"
                            + " not list that version");
                }
            }
        }
        for (String eventType : samples.keySet()) {
            if (!registry.catalog().eventTypes().containsKey(eventType)) {
                problems.add("Samples of " + eventType + " exist, but the catalog does not list that event type");
            }
        }
        return problems;
    }


    /**
     * A BACKWARD version must be compatible both ways with every earlier version back to (and including) the last
     * BREAKING one, or the oldest listed version:
     * <ul>
     *     <li>it accepts every sample of those versions: a consumer that knows it reads the events of the older ones
     *     (outbox backlog, stored dead letters);</li>
     *     <li>each of those versions accepts every sample of it: a consumer that does not know it yet validates its
     *     events against the latest version it knows ({@link EventSchemaRegistry#validate(EventTopic, JsonNode)}), so
     *     an event that only a relaxed constraint lets through would go to the DLT without retries there.</li>
     * </ul>
     * Adding an optional property passes both (the schemas are open); relaxing a constraint, such as a lower minimum or
     * a wider enum, fails the second and has to be published as BREAKING (consumers first).
     */
    private List<String> backwardProblems(
            EventSchemaRegistry registry,
            EventTypeContract contract,
            int version,
            NavigableMap<Integer, Map<String, JsonNode>> byVersion
    ) {
        int from = contract.versions().headMap(version, false).descendingMap().entrySet().stream()
                .filter(earlier -> earlier.getValue() == Compatibility.BREAKING)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(contract.oldestVersion());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, JsonNode>> earlier : byVersion.subMap(from, true, version, false).entrySet()) {
            for (Map.Entry<String, JsonNode> sample : earlier.getValue().entrySet()) {
                List<String> violations = registry.validate(contract.eventType(), version, sample.getValue());
                if (!violations.isEmpty()) {
                    problems.add(contract.eventType() + " v" + version + " is marked BACKWARD but rejects "
                            + sample.getKey() + " (a v" + earlier.getKey() + " event): " + violations + ". A compatible"
                            + " version may only add optional properties; publish a breaking change as BREAKING");
                }
            }
        }
        Map<String, JsonNode> ofVersion = byVersion.getOrDefault(version, Map.of());
        for (int earlier = from; earlier < version; earlier++) {
            if (!contract.versions().containsKey(earlier)) {
                continue;
            }
            for (Map.Entry<String, JsonNode> sample : ofVersion.entrySet()) {
                List<String> violations = registry.validate(contract.eventType(), earlier, sample.getValue());
                if (!violations.isEmpty()) {
                    problems.add(contract.eventType() + " v" + version + " is marked BACKWARD but v" + earlier
                            + " rejects " + sample.getKey() + ": " + violations + ". A consumer that only knows v"
                            + earlier + " checks v" + version + " events against v" + earlier + " and would dead-letter"
                            + " this one. A compatible version may only add optional properties; a relaxed constraint"
                            + " is BREAKING (consumers first)");
                }
            }
        }
        return problems;
    }


    /**
     * A version below the oldest one the catalog lists for its event type was retired: its file stays (published files
     * are never deleted), and consumers no longer accept events of it.
     */
    private static boolean retired(EventSchemaCatalog catalog, Matcher eventTypeSchema) {
        EventTypeContract contract = catalog.eventTypes().get(eventTypeSchema.group(2));
        return contract != null
                && contract.topic().contractName().equals(eventTypeSchema.group(1))
                && Integer.parseInt(eventTypeSchema.group(3)) < contract.oldestVersion();
    }


    private List<String> metaSchemaProblems(Map<String, byte[]> files) {
        Schema metaSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(SchemaLocation.of(DialectId.DRAFT_2020_12));
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            JsonNode schema = MAPPER.readTree(file.getValue());
            if (!DialectId.DRAFT_2020_12.equals(schema.path("$schema").asString(""))) {
                problems.add(file.getKey() + " does not declare \"$schema\": \"" + DialectId.DRAFT_2020_12 + "\"");
            }
            String expectedId = EventSchemaRegistry.BASE_IRI + file.getKey();
            if (!expectedId.equals(schema.path("$id").asString(""))) {
                problems.add(file.getKey() + " must have \"$id\": \"" + expectedId + "\"");
            }
            for (Error error : metaSchema.validate(schema)) {
                problems.add(file.getKey() + " is not a valid draft 2020-12 schema: " + error);
            }
        }
        return problems;
    }


    /**
     * @return by path relative to the schema location: every JSON file except catalog.json
     */
    private Map<String, byte[]> schemaFiles() {
        Map<String, byte[]> files = new TreeMap<>();
        for (Map.Entry<String, Resource> resource : resources(schemaLocation).entrySet()) {
            if (!resource.getKey().equals(EventSchemaRegistry.CATALOG_FILE)) {
                files.put(resource.getKey(), bytes(resource.getValue()));
            }
        }
        return files;
    }


    private Map<String, String> readManifest(List<String> problems) {
        Map<String, String> published = new LinkedHashMap<>();
        Resource manifest = new PathMatchingResourcePatternResolver().getResource("classpath:" + schemaLocation + MANIFEST_FILE);
        if (!manifest.exists()) {
            problems.add(schemaLocation + MANIFEST_FILE + " is missing");
            return published;
        }
        String content = new String(bytes(manifest), StandardCharsets.UTF_8);
        for (String line : content.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Matcher entry = MANIFEST_LINE.matcher(line.strip());
            if (!entry.matches()) {
                problems.add(MANIFEST_FILE + " has a malformed line: '" + line + "'");
            } else if (published.put(entry.group(2), entry.group(1)) != null) {
                problems.add(MANIFEST_FILE + " lists " + entry.group(2) + " twice");
            }
        }
        return published;
    }


    /**
     * @return event type -> version -> sample name -> event
     */
    private Map<String, NavigableMap<Integer, Map<String, JsonNode>>> samples(List<String> problems) {
        Map<String, NavigableMap<Integer, Map<String, JsonNode>>> samples = new TreeMap<>();
        for (Map.Entry<String, Resource> resource : resources(sampleLocation).entrySet()) {
            Matcher sample = SAMPLE_FILE.matcher(resource.getKey());
            if (!sample.matches()) {
                problems.add("Sample " + resource.getKey() + " is not at <topic>/<event_type>/v<N>/<name>.json");
                continue;
            }
            JsonNode event = MAPPER.readTree(bytes(resource.getValue()));
            int version = Integer.parseInt(sample.group(3));
            if (!sample.group(2).equals(event.path("event_type").asString(""))
                    || event.path("event_version").asInt(-1) != version) {
                problems.add("Sample " + resource.getKey() + " must have event_type " + sample.group(2)
                        + " and event_version " + version);
            }
            samples.computeIfAbsent(sample.group(2), type -> new TreeMap<>())
                    .computeIfAbsent(version, v -> new TreeMap<>())
                    .put(resource.getKey(), event);
        }
        return samples;
    }


    /**
     * @return by path relative to {@code location}: the JSON files under it on the classpath
     */
    private static Map<String, Resource> resources(String location) {
        try {
            Map<String, Resource> resources = new TreeMap<>();
            String root = new PathMatchingResourcePatternResolver().getResource("classpath:" + location).getURI().toString();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:" + location + "**/*.json")) {
                String uri = resource.getURI().toString();
                if (uri.startsWith(root)) {
                    resources.put(uri.substring(root.length()), resource);
                }
            }
            return resources;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    private static byte[] bytes(Resource resource) {
        try (InputStream content = resource.getInputStream()) {
            return content.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
