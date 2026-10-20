package com.bhumi.commander.remediation;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One throwaway Postgres shared by the plain JUnit database tests. */
final class SharedPostgres {

    private static final PostgreSQLContainer CONTAINER =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    static {
        CONTAINER.start();
    }

    private SharedPostgres() {}

    static DataSource dataSource() {
        return new DriverManagerDataSource(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
    }
}