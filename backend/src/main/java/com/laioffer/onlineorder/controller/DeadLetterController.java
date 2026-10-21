package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.audit.AuditActorResolver;
import com.laioffer.onlineorder.model.DeadLetterReplayDto;
import com.laioffer.onlineorder.service.DeadLetterReplayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;


@RestController
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterController {

    private final DeadLetterReplayService deadLetterReplayService;
    private final AuditActorResolver auditActorResolver;


    public DeadLetterController(DeadLetterReplayService deadLetterReplayService, AuditActorResolver auditActorResolver) {
        this.deadLetterReplayService = deadLetterReplayService;
        this.auditActorResolver = auditActorResolver;
    }


    @PostMapping("/dead-letters/{deadLetterEventId:\\d+}/replay")
    public DeadLetterReplayDto replay(Principal principal, @PathVariable Long deadLetterEventId) {
        return deadLetterReplayService.replay(deadLetterEventId, auditActorResolver.resolve(principal));
    }
}
