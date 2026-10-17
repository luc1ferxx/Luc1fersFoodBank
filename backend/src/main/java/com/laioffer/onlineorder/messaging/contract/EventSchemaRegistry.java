package com.laioffer.onlineorder.messaging.contract;


import com.laioffer.onlineorder.messaging.contract.EventSchemaCatalog.EventTypeContract;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;


import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;


/**
 * The published event contracts: the catalog and every schema version it lists, compiled once (JSON Schema draft
 * 2020-12, networknt json-schema-validator). Schemas refer to each other by their {@code $id} under {@link #BASE_IRI},
 * which is mapped to the classpath, so loading them never reaches the network. A schema that is missing or does not
 * compile fails the load, and with it the application start.
 *
 * <p>Thread-safe: validation only reads the compiled schemas.
 */
public final class EventSchemaRegistry {

    /**
     * The {@code $id} prefix of every schema; the {@code .example} domain is reserved, so it can never resolve.
     */
    public static final String BASE_IRI = "https://onlineorder.example/event-schemas/";

    /**
     * Where the published schemas live on the classpath (backend/src/main/resources/event-schemas).
     */
    public static final String DEFAULT_LOCATION = "event-schemas/";

    public static final String CATALOG_FILE = "catalog.json";

    private final EventSchemaCatalog catalog;
    private final Schema envelope;
    private final Map<String, NavigableMap<Integer, Schema>> schemasByType;


    private EventSchemaRegistry(
            EventSchemaCatalog catalog,
            Schema envelope,
            Map<String, NavigableMap<Integer, Schema>> schemasByType
    ) {
        this.catalog = catalog;
        this.envelope = envelope;
        this.schemasByType = schemasByType;
    }


    /**
     * The published contracts, loaded once per class loader.
     */
    public static EventSchemaRegistry published() {
        return Published.INSTANCE;
    }


    /**
     * Loads the catalog and the schemas under {@code location} on the classpath (with a trailing slash), mapping
     * {@link #BASE_IRI} there. Tests use it for schema sets of their own.
     */
    public static EventSchemaRegistry load(String location) {
        ClassLoader classLoader = EventSchemaRegistry.class.getClassLoader();
        EventSchemaCatalog catalog = EventSchemaCatalog.parse(readCatalog(classLoader, location));
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(BASE_IRI, "classpath:" + location))
        );

        Schema envelope = registry.getSchema(SchemaLocation.of(BASE_IRI + catalog.envelopePath()));
        Map<String, NavigableMap<Integer, Schema>> schemasByType = new HashMap<>();
        for (EventTypeContract contract : catalog.eventTypes().values()) {
            NavigableMap<Integer, Schema> versions = new TreeMap<>();
            for (Integer version : contract.versions().keySet()) {
                versions.put(version, registry.getSchema(SchemaLocation.of(BASE_IRI + contract.schemaPath(version))));
            }
            schemasByType.put(contract.eventType(), Collections.unmodifiableNavigableMap(versions));
        }
        return new EventSchemaRegistry(catalog, envelope, Collections.unmodifiableMap(schemasByType));
    }


    public EventSchemaCatalog catalog() {
        return catalog;
    }


    /**
     * Checks an event read from (or about to be written to) {@code topic}:
     * <ul>
     *     <li>a published event type and version: against that version's schema, and the type must belong to the
     *     topic;</li>
     *     <li>a version newer than any this instance knows: against the latest known version. Schemas are open and a
     *     BACKWARD version only adds optional properties (EventSchemaContractTests checks that the versions before it
     *     accept its sample events), so such an event passes unless it broke the contract. A BREAKING version fails
     *     here, which is why consumers learn it before producers write it;</li>
     *     <li>a version older than the oldest one still listed: rejected;</li>
     *     <li>an unknown event type: against the envelope only, so a consumer can skip a type it does not handle.</li>
     * </ul>
     */
    public EventValidation validate(EventTopic topic, JsonNode event) {
        String eventType = textOrNull(event.get("event_type"));
        Integer version = intOrNull(event.get("event_version"));
        EventTypeContract contract = eventType == null ? null : catalog.eventTypes().get(eventType);
        if (contract == null) {
            return new EventValidation(eventType, version, catalog.envelopePath(), false, check(envelope, event));
        }

        List<String> violations = new ArrayList<>();
        if (contract.topic() != topic) {
            violations.add("/event_type: " + eventType + " is published on " + contract.topic().contractName()
                    + ", not on " + topic.contractName());
        }
        if (version != null && version < contract.oldestVersion()) {
            violations.add("/event_version: version " + version + " of " + eventType
                    + " is no longer supported (oldest supported: " + contract.oldestVersion() + ")");
            return new EventValidation(eventType, version, contract.schemaPath(contract.oldestVersion()), false, violations);
        }
        boolean exactVersion = version != null && version <= contract.latestVersion();
        int schemaVersion = exactVersion ? version : contract.latestVersion();
        violations.addAll(check(schemasByType.get(eventType).get(schemaVersion), event));
        return new EventValidation(eventType, version, contract.schemaPath(schemaVersion), exactVersion, violations);
    }


    /**
     * Checks an event against one published schema version, whatever version the event declares.
     *
     * @throws IllegalArgumentException when the event type or version is not published
     */
    public List<String> validate(String eventType, int version, JsonNode event) {
        NavigableMap<Integer, Schema> versions = schemasByType.get(eventType);
        if (versions == null || !versions.containsKey(version)) {
            throw new IllegalArgumentException("No published schema for " + eventType + " version " + version);
        }
        return check(versions.get(version), event);
    }


    private static List<String> check(Schema schema, JsonNode event) {
        List<Error> errors = schema.validate(event);
        List<String> violations = new ArrayList<>(errors.size());
        for (Error error : errors) {
            violations.add(error.toString());
        }
        return violations;
    }


    private static String textOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }


    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() ? node.asInt() : null;
    }


    private static JsonNode readCatalog(ClassLoader classLoader, String location) {
        String resource = location + CATALOG_FILE;
        try (InputStream catalog = classLoader.getResourceAsStream(resource)) {
            if (catalog == null) {
                throw new IllegalStateException("Event schema catalog " + resource + " is not on the classpath");
            }
            return JsonMapper.builder().build().readTree(catalog);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read the event schema catalog " + resource, ex);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Event schema catalog " + resource + " is not valid JSON", ex);
        }
    }


    private static final class Published {

        private static final EventSchemaRegistry INSTANCE = load(DEFAULT_LOCATION);
    }
}
