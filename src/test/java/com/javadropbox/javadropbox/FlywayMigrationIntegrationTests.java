package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@DisplayName("Flyway - migrating an empty database")
class FlywayMigrationIntegrationTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestSupport.IMAGE);

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    PostgresTestSupport.register(registry, POSTGRES);
  }

  @Autowired private Flyway flyway;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Autowired private UserRepository userRepository;

  @Test
  @DisplayName("every migration runs against an empty database")
  void migratesEmptyDatabase() {
    MigrationInfo[] applied = flyway.info().applied();

    assertThat(applied).isNotEmpty();
    assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
    assertThat(applied[0].getType().isBaseline())
        .as("an empty database runs V1 rather than being stamped as a baseline")
        .isFalse();
    assertThat(applied).allSatisfy(m -> assertThat(m.getState().isApplied()).isTrue());
    assertThat(flyway.info().pending()).isEmpty();
  }

  @Test
  @DisplayName("Hibernate validates the entities against the migrated schema")
  void hibernateValidatesInsteadOfGenerating() {
    // The context only starts if validation passed, but that proves nothing
    // unless validation is what actually ran.
    assertThat(entityManagerFactory.getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
  }

  @Test
  @DisplayName("identity columns created by the migration generate ids")
  void migratedSchemaIsWritable() {
    User saved = userRepository.save(new User("migrated", "hash", "ROLE_ADMIN"));

    assertThat(saved.getId()).isNotNull();
    assertThat(userRepository.findById(saved.getId()))
        .hasValueSatisfying(u -> assertThat(u.getUsername()).isEqualTo("migrated"));

    userRepository.delete(saved);
  }
}
