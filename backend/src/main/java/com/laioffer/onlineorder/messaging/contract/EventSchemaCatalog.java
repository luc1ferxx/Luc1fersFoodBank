package com.laioffer.onlineorder.messaging.contract;


import tools.jackson.databind.JsonNode;


import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;


/**
 * {@code event-schemas/catalog.json}: every event type with a published contract, its topic and its schema versions.
 *
 * @param envelopePath the envelope schema an event of an unknown type is checked against, relative to the schema root
 * @param eventTypes   by event type, in catalog order
 */
public record EventSchemaCatalog(String envelopePath, Map<String, EventTypeContract> eventTypes) {

    /**
     * How a schema version relates to the version before it.
     */
    public enum Compatibility {

        /**
         * The first version of the event type.
         */
        INITIAL,

        /**
         * Only adds optional properties (or relaxes a constraint): it accepts every event of the versions before it,
         * back to the last {@link #BREAKING} one, so producers may move to it before consumers know it.
         */
        BACKWARD,

        /**
         * A new major version (a removed, renamed or newly required property, a changed type or meaning). Consumers
         * must accept it and the version before it before any producer writes it.
         */
        BREAKING
    }


    /**
     * @param versions schema versions, oldest first; the latest is the one producers write
     */
    public record EventTypeContract(String eventType, EventTopic topic, NavigableMap<Integer, Compatibility> versions) {

        public int latestVersion() {
            return versions.lastKey();
        }


        public int oldestVersion() {
            return versions.firstKey();
        }


        /**
         * @return the schema file of {@code version}, relative to the schema root
         */
        public String schemaPath(int version) {
            return topic.contractName() + "/" + eventType + "/v" + version + ".json";
        }
    }


    public EventSchemaCatalog {
        eventTypes = Collections.unmodifiableMap(new LinkedHashMap<>(eventTypes));
    }


    /**
     * @throws IllegalStateException when the catalog is malformed: an unknown topic or compatibility, no versions, a gap
     *                               between versions, or INITIAL on any version but 1
     */
    public static EventSchemaCatalog parse(JsonNode catalog) {
        String envelopePath = catalog.path("envelope").asString("");
        if (envelopePath.isBlank()) {
            throw new IllegalStateException("Event schema catalog names no envelope schema");
        }
        Map<String, EventTypeContract> eventTypes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : catalog.path("event_types").properties()) {
            String eventType = entry.getKey();
            JsonNode contract = entry.getValue();
            String topicName = contract.path("topic").asString("");
            EventTopic topic = EventTopic.ofContractName(topicName).orElseThrow(() -> new IllegalStateException(
                    "Event type " + eventType + " names an unknown topic: '" + topicName + "'"
            ));
            NavigableMap<Integer, Compatibility> versions = new TreeMap<>();
            for (Map.Entry<String, JsonNode> version : contract.path("versions").properties()) {
                versions.put(parseVersion(eventType, version.getKey()), parseCompatibility(eventType, version));
            }
            checkVersions(eventType, versions);
            eventTypes.put(eventType, new EventTypeContract(eventType, topic, Collections.unmodifiableNavigableMap(versions)));
        }
        if (eventTypes.isEmpty()) {
            throw new IllegalStateException("Event schema catalog lists no event types");
        }
        return new EventSchemaCatalog(envelopePath, eventTypes);
    }


    private static int parseVersion(String eventType, String version) {
        try {
            int parsed = Integer.parseInt(version);
            if (parsed < 1) {
                throw new NumberFormatException(version);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Event type " + eventType + " lists an invalid version: '" + version + "'", ex);
        }
    }


    private static Compatibility parseCompatibility(String eventType, Map.Entry<String, JsonNode> version) {
        String value = version.getValue().asString("");
        try {
            return Compatibility.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Version " + version.getKey() + " of " + eventType + " has an unknown compatibility: '" + value + "'",
                    ex
            );
        }
    }


    private static void checkVersions(String eventType, NavigableMap<Integer, Compatibility> versions) {
        if (versions.isEmpty()) {
            throw new IllegalStateException("Event type " + eventType + " lists no schema versions");
        }
        int expected = versions.firstKey();
        for (Map.Entry<Integer, Compatibility> version : versions.entrySet()) {
            if (version.getKey() != expected) {
                throw new IllegalStateException("Event type " + eventType + " has no version " + expected);
            }
            if ((version.getValue() == Compatibility.INITIAL) != (version.getKey() == 1)) {
                throw new IllegalStateException(
                        "Version " + version.getKey() + " of " + eventType + " cannot be " + version.getValue()
                                + ": exactly version 1 is INITIAL"
                );
            }
            expected++;
        }
    }
}
