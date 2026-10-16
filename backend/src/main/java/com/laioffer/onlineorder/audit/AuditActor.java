package com.laioffer.onlineorder.audit;


import java.util.Objects;


/**
 * The login an audited action is attributed to.
 *
 * @param customerId the customers.id of the login; null for {@link #system()}
 * @param role       the role the login acted with, for example ROLE_ADMIN; SYSTEM for actions without a login
 */
public record AuditActor(Long customerId, String role) {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_SYSTEM = "SYSTEM";

    private static final AuditActor SYSTEM = new AuditActor(null, ROLE_SYSTEM);


    public AuditActor {
        Objects.requireNonNull(role, "role");
    }


    public static AuditActor admin(Long customerId) {
        return new AuditActor(customerId, ROLE_ADMIN);
    }


    /**
     * An action without a login, for example one started by a job or by a caller that did not pass its actor.
     */
    public static AuditActor system() {
        return SYSTEM;
    }
}
