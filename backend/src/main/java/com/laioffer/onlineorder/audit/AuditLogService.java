package com.laioffer.onlineorder.audit;


import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.StringNode;
import com.laioffer.onlineorder.observability.TraceIds;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * The operations audit log: who did which privileged action to what, with the request's trace id.
 *
 * <p>{@link #record} only runs inside the caller's transaction (propagation MANDATORY), so the entry commits or rolls
 * back with the action it describes. Call it from the transactional service method that performs the action, after
 * the action's own checks, once per action actually performed (not for idempotent replays of an earlier request).
 */
@Service
public class AuditLogService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<Tracer> tracer;


    public AuditLogService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper, ObjectProvider<Tracer> tracer) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }


    /**
     * @param actor      who acted; {@link AuditActor#system()} when there is no login
     * @param action     one of {@link AuditActions}, for example {@link AuditActions#ORDER_STATUS_UPDATE}
     * @param targetType the kind of row acted on, for example {@link AuditActions#TARGET_ORDER}
     * @param targetId   the id of that row (stored as text), or null
     * @param details    the action's parameters and outcome; keys in snake_case, values serializable by Jackson
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditActor actor, String action, String targetType, Object targetId, Map<String, ?> details) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        auditLogRepository.save(new AuditLogEntity(
                null,
                actor.customerId(),
                actor.role(),
                action,
                targetType,
                targetId == null ? null : String.valueOf(targetId),
                writeDetails(details),
                TraceIds.current(tracer.getIfAvailable()),
                LocalDateTime.now()
        ));
    }


    /**
     * Newest entries first. Keyset pagination: pass the smallest id of a page as {@code beforeId} to read the next
     * (older) page; a page shorter than {@code limit} is the last one.
     *
     * @param beforeId only entries with a smaller id; null for the newest entries
     * @param limit    page size, clamped to 1..200
     */
    public List<AuditLogDto> getEntries(Long beforeId, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<AuditLogEntity> entries = beforeId == null
                ? auditLogRepository.findNewest(normalizedLimit)
                : auditLogRepository.findNewestBefore(beforeId, normalizedLimit);
        return entries.stream().map(this::toDto).toList();
    }


    /**
     * Writes the details as a JSON object, leaving out top-level keys whose value is null.
     */
    private String writeDetails(Map<String, ?> details) {
        if (details == null) {
            return null;
        }
        Map<String, Object> present = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (value != null) {
                present.put(key, value);
            }
        });
        if (present.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(present);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Audit details are not serializable", ex);
        }
    }


    private AuditLogDto toDto(AuditLogEntity entity) {
        return new AuditLogDto(
                entity.id(),
                entity.actorCustomerId(),
                entity.actorRole(),
                entity.action(),
                entity.targetType(),
                entity.targetId(),
                readDetails(entity.details()),
                entity.traceId(),
                entity.occurredAt()
        );
    }


    private JsonNode readDetails(String details) {
        if (details == null) {
            return null;
        }
        try {
            return objectMapper.readTree(details);
        } catch (JacksonException ex) {
            // Written by hand or by an older version: show it as it is stored.
            return StringNode.valueOf(details);
        }
    }
}
