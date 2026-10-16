-- Operations audit log (com.laioffer.onlineorder.audit): one row per privileged action, written by AuditLogService in
-- the same transaction as the action, so an action that rolls back leaves no row and a committed one always has its row.
--
-- actor_customer_id / actor_role: the login that acted and the role it acted with (ROLE_ADMIN for the admin
-- endpoints); both NULL / SYSTEM for actions without a login. No foreign key to customers: the log must outlive the
-- accounts it mentions. action is a dotted verb such as order.status.update or dead_letter.replay; target_type and
-- target_id name the row acted on (target_id is text so that non-numeric targets fit too). details is a JSON object
-- with the action's parameters and outcome. trace_id is the OpenTelemetry trace id of the request.
CREATE TABLE audit_log
(
    id                BIGSERIAL PRIMARY KEY NOT NULL,
    actor_customer_id BIGINT,
    actor_role        TEXT                  NOT NULL,
    action            TEXT                  NOT NULL,
    target_type       TEXT                  NOT NULL,
    target_id         TEXT,
    details           TEXT,
    trace_id          TEXT,
    occurred_at       TIMESTAMP             NOT NULL
);


-- GET /admin/audit-log pages newest first by id (keyset: WHERE id < before_id ORDER BY id DESC); the primary key
-- serves it. These serve lookups of one target's or one actor's actions.
CREATE INDEX idx_audit_log_target_id
ON audit_log (target_type, target_id, id);

CREATE INDEX idx_audit_log_actor_id
ON audit_log (actor_customer_id, id);
