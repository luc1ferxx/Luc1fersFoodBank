package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.CustomerEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;


public interface CustomerRepository extends ListCrudRepository<CustomerEntity, Long> {


    List<CustomerEntity> findByFirstName(String firstName);


    List<CustomerEntity> findByLastName(String lastName);


    CustomerEntity findByEmail(String email);


    @Modifying
    @Query("""
            UPDATE customers
            SET first_name = :firstName,
                last_name = :lastName,
                updated_at = CURRENT_TIMESTAMP
            WHERE email = :email
            """)
    void updateNameByEmail(String email, String firstName, String lastName);


    @Modifying
    @Query("""
            UPDATE customers
            SET failed_login_attempts = CASE
                    WHEN locked_until IS NOT NULL AND locked_until > CURRENT_TIMESTAMP THEN failed_login_attempts
                    WHEN locked_until IS NOT NULL AND locked_until <= CURRENT_TIMESTAMP THEN 1
                    ELSE failed_login_attempts + 1
                END,
                locked_until = CASE
                    WHEN locked_until IS NOT NULL AND locked_until > CURRENT_TIMESTAMP THEN locked_until
                    WHEN locked_until IS NOT NULL AND locked_until <= CURRENT_TIMESTAMP THEN NULL
                    WHEN failed_login_attempts + 1 >= :lockThreshold THEN :lockedUntil
                    ELSE NULL
                END,
                updated_at = CURRENT_TIMESTAMP
            WHERE LOWER(email) = LOWER(:email)
            """)
    void recordFailedLoginAttempt(String email, LocalDateTime lockedUntil, int lockThreshold);


    @Modifying
    @Query("""
            UPDATE customers
            SET failed_login_attempts = 0,
                locked_until = NULL,
                last_login_at = :lastLoginAt,
                updated_at = :lastLoginAt
            WHERE LOWER(email) = LOWER(:email)
            """)
    void recordSuccessfulLogin(String email, LocalDateTime lastLoginAt);
}
