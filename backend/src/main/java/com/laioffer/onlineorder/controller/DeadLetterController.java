package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.model.DeadLetterReplayDto;
import com.laioffer.onlineorder.service.DeadLetterReplayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterController {

    private final DeadLetterReplayService deadLetterReplayService;


    public DeadLetterController(DeadLetterReplayService deadLetterReplayService) {
        this.deadLetterReplayService = deadLetterReplayService;
    }


    @PostMapping("/dead-letters/{deadLetterEventId}/replay")
    public DeadLetterReplayDto replay(@PathVariable Long deadLetterEventId) {
        return deadLetterReplayService.replay(deadLetterEventId);
    }
}
