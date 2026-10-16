package com.laioffer.onlineorder.audit;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;


import java.time.LocalDateTime;


/**
 * One entry of GET /admin/audit-log. Null fields are written as null, so every entry has the same keys.
 *
 * @param details the action's parameters and outcome as a JSON object
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditLogDto(
        Long id,
        Long actorCustomerId,
        String actorRole,
        String action,
        String targetType,
        String targetId,
        JsonNode details,
        String traceId,
        LocalDateTime occurredAt
) {
}
