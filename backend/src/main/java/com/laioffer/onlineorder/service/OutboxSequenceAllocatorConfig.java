package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.repository.OutboxAggregateSequenceRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;


import javax.sql.DataSource;
import java.sql.DatabaseMetaData;


/**
 * Picks the {@link OutboxSequenceAllocator} for the database the application is connected to, not for the Spring
 * profile: the h2 profile can be pointed at PostgreSQL with DATABASE_URL, and the statements differ per database.
 */
@Configuration(proxyBeanMethods = false)
public class OutboxSequenceAllocatorConfig {

    @Bean
    public OutboxSequenceAllocator outboxSequenceAllocator(
            DataSource dataSource,
            OutboxAggregateSequenceRepository repository
    ) {
        return forDatabase(databaseProductName(dataSource), repository);
    }


    /**
     * @param databaseProductName {@link DatabaseMetaData#getDatabaseProductName()}
     * @throws IllegalStateException for a database other than PostgreSQL and H2 (the migrations support only those)
     */
    public static OutboxSequenceAllocator forDatabase(String databaseProductName, OutboxAggregateSequenceRepository repository) {
        if ("PostgreSQL".equalsIgnoreCase(databaseProductName)) {
            return new PostgresOutboxSequenceAllocator(repository);
        }
        if ("H2".equalsIgnoreCase(databaseProductName)) {
            return new H2OutboxSequenceAllocator(repository);
        }
        throw new IllegalStateException(
                "No outbox sequence allocator for database '" + databaseProductName + "' (PostgreSQL or H2)"
        );
    }


    private static String databaseProductName(DataSource dataSource) {
        try {
            return JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
        } catch (MetaDataAccessException ex) {
            throw new IllegalStateException("Unable to read the database product name for the outbox sequences", ex);
        }
    }
}
