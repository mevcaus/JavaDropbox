package com.javadropbox.javadropbox;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A new database with the app's schema, for tests outside this package that work on the database
 * without starting the app.
 */
public final class MigratedDatabase {

  private MigratedDatabase() {}

  /** A new database in the shared PostgreSQL, migrated to the newest version. */
  public static DataSource create() {
    PostgresTestSupport.Database database = PostgresTestSupport.newDatabase();
    database.migrateTo("latest");
    return new DriverManagerDataSource(database.url(), database.username(), database.password());
  }
}
