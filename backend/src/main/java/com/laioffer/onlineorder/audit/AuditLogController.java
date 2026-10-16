package com.laioffer.onlineorder.audit;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;


/**
 * The operations audit log (ROLE_ADMIN only, see AppConfig), newest first. Page with {@code before_id}: the id of the
 * last (oldest) entry of the previous page.
 */
@RestController
public class AuditLogController {

    private final AuditLogService auditLogService;


    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }


    @GetMapping("/admin/audit-log")
    public List<AuditLogDto> getAuditLog(
            @RequestParam(defaultValue = "" + AuditLogService.DEFAULT_LIMIT) int limit,
            @RequestParam(name = "before_id", required = false) Long beforeId
    ) {
        return auditLogService.getEntries(beforeId, limit);
    }
}
