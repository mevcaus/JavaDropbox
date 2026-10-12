package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.PostgresTestSupport.Database;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@DisplayName("Flyway - adopting a database built by ddl-auto=update")
class FlywayBaselineIntegrationTests {

  // Stands in for an install that predates Flyway: the tables already exist
  // and there is no flyway_schema_history. The schema is V1 itself, which is
  // identical to what ddl-auto=update used to create (compared with
  // pg_dump --schema-only when the baseline was written).
  private static final Database DATABASE = installedBeforeFlyway();

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    PostgresTestSupport.register(registry, DATABASE);
  }

  private static Database installedBeforeFlyway() {
    Database database = PostgresTestSupport.newDatabase();
    try (Connection c = database.connect()) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V1__baseline.sql"));
    } catch (SQLException e) {
      throw new IllegalStateException("could not build the pre-Flyway schema", e);
    }
    return database;
  }

  @Autowired private Flyway flyway;

  @Autowired private UserRepository userRepository;

  @Test
  @DisplayName("an existing schema is stamped as V1 instead of migrated")
  void existingSchemaIsBaselined() {
    MigrationInfo[] applied = flyway.info().applied();

    assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
    assertThat(applied[0].getType().isBaseline())
        .as("V1 must not run against tables that already exist")
        .isTrue();
    assertThat(flyway.info().pending()).isEmpty();
  }

  @Test
  @DisplayName("the adopted schema passes Hibernate validation and stays usable")
  void adoptedSchemaIsUsable() {
    // Getting here means the context started, so validation passed.
    User saved = userRepository.save(new User("adopted", "hash", "ROLE_ADMIN"));

    assertThat(userRepository.findById(saved.getId())).isPresent();

    userRepository.delete(saved);
  }
}
