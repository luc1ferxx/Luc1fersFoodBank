package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;


@Table("customers")
public record CustomerEntity(
        @Id
        @Column("id")
        Long id,
        @Column("email")
        String email,
        @Column("password")
        String password,
        @Column("enabled")
        boolean enabled,
        @Column("first_name")
        String firstName,
        @Column("last_name")
        String lastName,
        @Column("account_status")
        String accountStatus,
        @Column("email_verified")
        boolean emailVerified,
        @Column("failed_login_attempts")
        Integer failedLoginAttempts,
        @Column("locked_until")
        LocalDateTime lockedUntil,
        @Column("last_login_at")
        LocalDateTime lastLoginAt,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
