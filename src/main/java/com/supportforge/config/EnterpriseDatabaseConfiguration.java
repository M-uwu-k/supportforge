package com.supportforge.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;


@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "postgres-persistence",
        havingValue = "true"
)
public class EnterpriseDatabaseConfiguration {


    @Bean(destroyMethod = "close")
    public HikariDataSource supportForgeDataSource(
            DatabaseProperties properties
    ) {
        properties.validateForConnection();

        HikariConfig config = new HikariConfig();
        config.setPoolName("supportforge-postgres");
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(properties.maximumPoolSize());
        config.setMinimumIdle(properties.minimumIdle());

        config.setConnectionTimeout(
                properties.connectionTimeout().toMillis()
        );


        config.setAutoCommit(false);

        config.setInitializationFailTimeout(
                properties.connectionTimeout().toMillis()
        );

        return new HikariDataSource(config);
    }


    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(
            prefix = "supportforge.database",
            name = "migration-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public Flyway supportForgeFlyway(


            DataSource supportForgeDataSource,
            DatabaseProperties properties
    ) {


        return Flyway.configure()
                .dataSource(supportForgeDataSource)
                .schemas(properties.schema())
                .defaultSchema(properties.schema())
                .createSchemas(true)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
    }


    @Bean(name = "postgres")
    public HealthIndicator postgresHealthIndicator(

            DataSource supportForgeDataSource,

            DatabaseProperties properties
    ) {


        return () -> {


            try (Connection connection =
                         supportForgeDataSource.getConnection()) {


                boolean valid = connection.isValid(2);
                if (!valid) {


                    return Health.down()

                            .withDetail("schema", properties.schema())

                            .withDetail("reason", "connection validation failed")

                            .build();
                }

                return Health.up()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("schema", properties.schema())
                        .build();

            } catch (Exception exception) {


                return Health.down(exception)
                        .withDetail("schema", properties.schema())
                        .build();
            }
        };
    }
}

