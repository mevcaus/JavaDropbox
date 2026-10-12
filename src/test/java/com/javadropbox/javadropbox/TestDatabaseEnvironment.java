package com.javadropbox.javadropbox;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Runs every application the tests start on a PostgreSQL database of its own, which Flyway migrates
 * from empty with production's settings (application.properties reaches the tests as it is) and
 * Hibernate then validates the entities against, as on a new install. So the tests see the schema a
 * real install has, with its constraints, partial indexes and cascades, rather than one generated
 * from the entities. Listed in src/test/resources/META-INF/spring.factories, so it covers each
 * context a {@code @SpringBootTest} loads and any application a test starts by hand.
 *
 * <p>A database per context, rather than one for the whole run, because Spring keeps the contexts
 * it loads, to reuse for later test classes with the same configuration, so several are alive at
 * once, and they use the database outside of any test: at startup the first account is given the
 * loose files in the serving directory, and the search index works on a background thread. On one
 * database, whatever one context's tests left behind would be the next context's data from the
 * moment it started. The test classes that share a context still share its database, and wipe it
 * between tests ({@link TestDatabase}).
 *
 * <p>These are the lowest-precedence settings, so anything a test sets wins: the suites that need
 * their database in another state first (migration tests that start from an old schema) point
 * spring.datasource.* at one of their own. They still get an empty database here that they never
 * use, because this runs before their settings are visible. Creating it only when a context first
 * asks for its datasource would spare that, but Spring Boot ignores an exception thrown while it
 * looks a setting up, so a failure (Docker not running, say) would surface as a missing JDBC driver
 * instead of its real cause.
 */
class TestDatabaseEnvironment implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    Database database = PostgresTestSupport.newDatabase();
    Map<String, Object> settings =
        Map.of(
            "spring.datasource.url", database.url(),
            "spring.datasource.username", database.username(),
            "spring.datasource.password", database.password());
    environment.getPropertySources().addLast(new MapPropertySource("testDatabase", settings));
  }
}
