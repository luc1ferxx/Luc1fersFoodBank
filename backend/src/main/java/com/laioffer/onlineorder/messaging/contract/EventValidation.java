package com.laioffer.onlineorder.messaging.contract;


import java.util.List;


/**
 * The outcome of checking one event against its contract.
 *
 * @param eventType    the event's event_type, or null when it has none
 * @param version      the event's event_version, or null when it has none
 * @param schemaPath   the schema the event was checked against, relative to the schema root
 * @param exactVersion true when the event type and the version the event declares are both published; false when the
 *                     event was checked against the envelope only (an unknown event type) or against the latest known
 *                     version of its type (a newer version than this instance knows)
 * @param violations   empty when the event is valid
 */
public record EventValidation(
        String eventType,
        Integer version,
        String schemaPath,
        boolean exactVersion,
        List<String> violations
) {

    public EventValidation {
        violations = List.copyOf(violations);
    }


    public boolean valid() {
        return violations.isEmpty();
    }
}
